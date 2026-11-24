package vn.signa.sign.internal;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static vn.signa.shared.storage.Bucket.SIGN_CROPS;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.shared.storage.MinioObjectStorage;
import vn.signa.support.Containers;
import vn.signa.support.IntegrationTest;

/** HTTP, Redis, MySQL và MinIO thật. Chạy qua integrationTest trên CI. */
@TestPropertySource(
    properties = {
      "signa.catalog.import-on-startup=true",
      "signa.clustering.worker.enabled=true",
      "signa.clustering.worker.consumer=submission-it",
      "signa.clustering.worker.sweep-interval=1s",
      "signa.clustering.crop-cleanup.enabled=false",
      "spring.data.redis.timeout=2s"
    })
class SubmissionIT extends IntegrationTest {

  private static final String SUBMISSIONS = "/api/v1/submissions";
  private static final String MINE = "/api/v1/me/submissions";
  private static final byte[] JPEG = jpeg();
  private final HttpClient http = HttpClient.newHttpClient();

  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired StringRedisTemplate redis;
  @Autowired ClusteringWorker worker;
  @MockitoSpyBean MinioObjectStorage storage;
  @MockitoSpyBean SubmissionStream stream;

  @Test
  void itB01RepeatingClientIdKeepsOneSubmission() throws Exception {
    Session user = signIn();
    String clientId = uuid();
    String first = body(post(user, clientId, 14, 105, 0.88, 1), 201).get("id").asString();
    String second = body(post(user, clientId, 14, 105, 0.88, 1), 200).get("id").asString();

    assertThat(second).isEqualTo(first);
    assertThat(submissionCount(user, clientId)).isEqualTo(1);
  }

  @Test
  void itB02StoresCropAndCreatesGroupedSignWithSystemHistory() throws Exception {
    Session user = signIn();
    JsonNode receipt = body(post(user, uuid(), 15, 105, 0.88, 1), 201);
    assertThat(receipt.get("status").asString()).isEqualTo("RECEIVED");
    String id = receipt.get("id").asString();

    Map<String, Object> row = awaitGrouped(id);
    String signId = row.get("sign_id").toString();
    String cropKey = row.get("crop_key").toString();
    assertThat(cropKey).isEqualTo(id + ".jpg");
    assertCrop(cropKey);
    assertThat(jdbc.queryForMap("SELECT status, cover_crop_key FROM signs WHERE id = ?", signId))
        .containsEntry("status", "PENDING")
        .containsEntry("cover_crop_key", cropKey);
    assertThat(history(signId))
        .singleElement()
        .satisfies(event ->
            assertThat(event)
                .containsEntry("action", "CREATED")
                .containsEntry("actor_type", "SYSTEM")
                .containsEntry("actor_id", null));
    assertThat(assertStatus(request("GET", "/actuator/prometheus", user.token(), null), 200))
        .contains(
            "signa_clustering_stream_length",
            "signa_clustering_pending",
            "signa_clustering_received_count",
            "signa_clustering_received_oldest_seconds",
            "signa_clustering_process_duration_seconds_count");
  }

  @Test
  void itB03TwentyConcurrentSubmittersShareOneSign() throws Exception {
    List<Session> users = new ArrayList<>();
    for (int i = 0; i < 20; i++) users.add(signIn());

    List<CompletableFuture<HttpResponse<String>>> responses = new ArrayList<>();
    for (int i = 0; i < users.size(); i++) {
      // Ở 60° vĩ bắc, chênh 0,0003° kinh độ vẫn nằm trong 20 m.
      double lng = i % 2 == 0 ? 105 : 105.0003;
      double confidence = i == users.size() - 1 ? 0.99 : 0.88;
      responses.add(sendAsync(upload(users.get(i), uuid(), 60, lng, confidence, 1)));
    }

    List<String> ids = new ArrayList<>();
    for (var response : responses)
      ids.add(body(response.get(40, TimeUnit.SECONDS), 201).get("id").asString());
    List<String> signIds = new ArrayList<>();
    for (String id : ids) signIds.add(awaitGrouped(id).get("sign_id").toString());

    assertThat(signIds.stream().distinct()).hasSize(1);
    Map<String, Object> sign =
        jdbc.queryForMap(
            """
            SELECT distinct_submitters, cover_crop_key, version FROM signs WHERE id = ?
            """,
            signIds.getFirst());
    assertThat(sign)
        .containsEntry("distinct_submitters", 20)
        .containsEntry("cover_crop_key", ids.getLast() + ".jpg")
        .containsEntry("version", 19);
  }

  @Test
  @SuppressWarnings("unchecked") // StreamOperations nhận varargs StreamOffset<K>.
  void itB12RedisOutageIsRecoveredAndPendingReplayIsIdempotent() throws Exception {
    Session user = signIn();
    AtomicBoolean redisPaused = new AtomicBoolean(false);
    var redisContainer = Containers.REDIS;
    doAnswer(
            invocation -> {
              if (!Thread.currentThread().getName().equals("signa-clustering")
                  && !redisPaused.get()) {
                // Dừng Redis sau bước xác thực, ngay khi request thử ghi stream sau commit.
                redisContainer
                    .getDockerClient()
                    .pauseContainerCmd(redisContainer.getContainerId())
                    .exec();
                redisPaused.set(true);
              }
              return invocation.callRealMethod();
            })
        .when(stream)
        .add(anyString());

    String id;
    try {
      id = body(post(user, uuid(), 16, 105, 0.88, 1), 201).get("id").asString();
      assertThat(redisPaused).isTrue();
      assertThat(submission(id).get("status")).isEqualTo("RECEIVED");
      jdbc.update(
          "UPDATE submissions SET received_at = UTC_TIMESTAMP(3) - INTERVAL 61 SECOND WHERE id = ?",
          id);
    } finally {
      if (redisPaused.get()) {
        redisContainer
            .getDockerClient()
            .unpauseContainerCmd(redisContainer.getContainerId())
            .exec();
      }
    }

    var grouped = awaitGrouped(id);
    String signId = grouped.get("sign_id").toString();
    assertThat((BigDecimal) grouped.get("weight")).isEqualByComparingTo("0.88");
    drain();
    worker.stop();
    try {
      stream.add(id);
      assertThat(
              redis
                  .opsForStream()
                  .read(
                      Consumer.from(SubmissionStream.GROUP, "consumer-cu"),
                      StreamReadOptions.empty().count(1),
                      StreamOffset.create(SubmissionStream.KEY, ReadOffset.lastConsumed())))
          .hasSize(1);
      assertThat(stream.pending()).isEqualTo(1);
    } finally {
      worker.start();
    }
    drain();

    assertThat(submission(id)).isEqualTo(grouped);
    assertThat(jdbc.queryForObject("SELECT version FROM signs WHERE id = ?", Integer.class, signId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT weight FROM sign_code_weights WHERE sign_id = ?", BigDecimal.class, signId))
        .isEqualByComparingTo("0.88");
    assertThat(history(signId))
        .singleElement()
        .satisfies(event -> assertThat(event).containsEntry("action", "CREATED"));
  }

  @Test
  void itB12RecoversMultipleBatchesWithoutDuplicatingQueuedIds() throws Exception {
    Session user = signIn();
    drain();
    worker.stop();
    try {
      redis.delete(List.of(SubmissionStream.KEY, SubmissionStream.QUEUED));
      int count = ClusteringWorker.SWEEP_BATCH_SIZE + 3;
      List<Object[]> rows = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        String id = uuid();
        rows.add(new Object[] {id, user.id(), uuid(), id + ".jpg"});
      }
      jdbc.batchUpdate(
          """
          INSERT INTO submissions
            (id, user_id, client_id, sign_type_code, confidence, location, source, crop_key,
             model_name, model_version, label_map_version, captured_at, received_at, status)
          VALUES (?, ?, ?, 'P.102', 0.88, ST_GeomFromText('POINT(105 22)', 4326, 'axis-order=long-lat'),
                  'CAMERA', ?, 'yolov8n', 'it', 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3) - INTERVAL 2 MINUTE, 'RECEIVED')
          """,
          rows);
      Instant cutoff = Instant.now().minusSeconds(60);
      var start = new SubmissionRepository.Received("", Instant.EPOCH);
      var next = worker.sweepBatch(cutoff, start);
      assertThat(stream.length()).isEqualTo(ClusteringWorker.SWEEP_BATCH_SIZE);
      worker.sweepBatch(cutoff, start);
      assertThat(stream.length()).isEqualTo(ClusteringWorker.SWEEP_BATCH_SIZE);
      worker.sweepBatch(cutoff, next);
      assertThat(stream.length()).isEqualTo(count);
      // Mất riêng stream cũng phải phục hồi được khi dấu trong Redis còn tồn tại.
      redis.delete(SubmissionStream.KEY);
    } finally {
      worker.start();
    }
    await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM submissions WHERE user_id = ? AND status = 'GROUPED'",
            Integer.class, user.id())).isEqualTo(ClusteringWorker.SWEEP_BATCH_SIZE + 3));
    drain();
    assertThat(redis.hasKey(SubmissionStream.QUEUED)).isFalse();
  }

  @Test
  void categoryChangeKeepsApprovedSignEligibleForMapAndClustering() throws Exception {
    Session admin = signIn("ADMIN");
    Session user = signIn();
    String code = "IT." + uuid().substring(0, 8);
    jdbc.update(
        """
        INSERT INTO sign_types
          (code, category_code, name, label_id, label_map_version, active, created_at, updated_at)
        VALUES (?, 'PROHIBITORY', 'Biển đổi nhóm', 30000, 1, TRUE, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3))
        """,
        code);
    String signId =
        body(request("POST", "/api/v1/admin/signs", admin.token(),
                json.writeValueAsString(Map.of(
                    "signTypeCode", code, "location", Map.of("lat", 20, "lng", 105),
                    "headingDeg", 90))), 201).get("id").asString();

    body(request("PATCH", "/api/v1/admin/sign-types/" + code, admin.token(),
        "{\"categoryCode\":\"WARNING\"}"), 200);
    String map = "/api/v1/signs?bbox=104.9,19.9,105.1,20.1&signTypeCodes=" + code + "&categories=";
    assertThat(body(request("GET", map + "WARNING", null, null), 200).toString())
        .contains(signId, "WARNING");
    assertThat(body(request("GET", map + "PROHIBITORY", null, null), 200).toString())
        .doesNotContain(signId);

    String submissionId =
        body(http.send(upload(user, uuid(), 20, 105, 0.88, 1, code),
                HttpResponse.BodyHandlers.ofString()), 201).get("id").asString();
    await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
        assertThat(submission(submissionId))
            .containsEntry("status", "ACCEPTED")
            .containsEntry("sign_id", signId));
  }

  @Test
  void itB14ConcurrentDuplicateDeletesLosingCropAndKeepsWinningCrop() throws Exception {
    Session user = signIn();
    String clientId = uuid();
    CyclicBarrier uploads = new CyclicBarrier(2);
    var cropKeys = new ConcurrentLinkedQueue<String>();
    doAnswer(
            invocation -> {
              cropKeys.add(invocation.getArgument(1, String.class));
              uploads.await(10, TimeUnit.SECONDS);
              return invocation.callRealMethod();
            })
        .when(storage)
        .put(eq(SIGN_CROPS), anyString(), any(byte[].class), eq("image/jpeg"));

    var a = sendAsync(upload(user, clientId, 17, 105, 0.88, 1));
    var b = sendAsync(upload(user, clientId, 17, 105, 0.88, 1));
    var first = a.get(30, TimeUnit.SECONDS);
    var second = b.get(30, TimeUnit.SECONDS);
    assertThat(List.of(first.statusCode(), second.statusCode()))
        .containsExactlyInAnyOrder(201, 200);
    String id = json.readTree(first.body()).get("id").asString();
    assertThat(json.readTree(second.body()).get("id").asString()).isEqualTo(id);
    assertThat(submissionCount(user, clientId)).isEqualTo(1);

    String winner = submission(id).get("crop_key").toString();
    assertThat(cropKeys).hasSize(2).contains(winner);
    String loser = cropKeys.stream().filter(key -> !key.equals(winner)).findFirst().orElseThrow();
    assertCrop(winner);
    assertThat(storage.exists(SIGN_CROPS, loser)).isFalse();
  }

  @Test
  void listReturnsOnlyOwnSubmissionsInReceivedOrder() throws Exception {
    Session owner = signIn();
    Session other = signIn();
    String older = body(post(owner, uuid(), 18, 105, 0.88, 1), 201).get("id").asString();
    String newer = body(post(owner, uuid(), 18, 105, 0.88, 1), 201).get("id").asString();
    String foreign = body(post(other, uuid(), 18, 105, 0.88, 1), 201).get("id").asString();

    JsonNode page = body(request("GET", MINE + "?page=0&size=1", owner.token(), null), 200);
    assertThat(page.get("page").intValue()).isZero();
    assertThat(page.get("size").intValue()).isEqualTo(1);
    assertThat(page.get("total").longValue()).isEqualTo(2);
    assertItem(page.get("items").get(0), newer);
    assertThat(page.get("items").size()).isEqualTo(1);
    assertItem(
        body(request("GET", MINE + "?page=1&size=1", owner.token(), null), 200).get("items").get(0),
        older);

    JsonNode others = body(request("GET", MINE, other.token(), null), 200);
    assertThat(others.get("total").longValue()).isEqualTo(1);
    assertItem(others.get("items").get(0), foreign);
  }

  @Test
  void unknownLabelMapVersionReturns422() throws Exception {
    Session user = signIn();
    String clientId = uuid();
    JsonNode error = body(post(user, clientId, 19, 105, 0.88, 999), 422);

    assertThat(error.get("code").asString()).isEqualTo("BUSINESS_RULE");
    assertThat(submissionCount(user, clientId)).isZero();
  }

  private void assertItem(JsonNode item, String id) {
    assertThat(item.get("id").asString()).isEqualTo(id);
    assertThat(item.get("signTypeCode").asString()).isEqualTo("P.102");
    assertThat(item.get("location").get("lat").doubleValue()).isCloseTo(18, within(1e-9));
    assertThat(item.get("location").get("lng").doubleValue()).isCloseTo(105, within(1e-9));
    assertThat(item.get("capturedAt").asString()).isEqualTo("2026-11-24T00:00:00Z");
    assertThat(item.get("receivedAt").asString()).isNotBlank();
    assertThat(item.has("rejectReason")).isTrue();
    assertThat(item.has("signId")).isTrue();
    assertThat(item.get("status").asString()).isIn("RECEIVED", "GROUPED");
  }

  private void assertCrop(String key) throws IOException, InterruptedException {
    String url = storage.presignedGet(SIGN_CROPS, key, Duration.ofMinutes(10));
    var response =
        http.send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo(JPEG);
  }

  private Map<String, Object> awaitGrouped(String id) {
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> assertThat(submission(id)).containsEntry("status", "GROUPED"));
    return submission(id);
  }

  private void drain() {
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> {
          assertThat(stream.length()).isZero();
          assertThat(stream.pending()).isZero();
        });
  }

  private Map<String, Object> submission(String id) {
    return jdbc.queryForMap(
        "SELECT status, sign_id, crop_key, weight FROM submissions WHERE id = ?", id);
  }

  private int submissionCount(Session user, String clientId) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM submissions WHERE user_id = ? AND client_id = ?",
        Integer.class,
        user.id(),
        clientId);
  }

  private List<Map<String, Object>> history(String signId) {
    return jdbc.queryForList(
        """
        SELECT action, actor_type, actor_id FROM audit_logs
        WHERE entity_type = 'SIGN' AND entity_id = ? ORDER BY id
        """,
        signId);
  }

  private Session signIn() throws Exception {
    return signIn("USER");
  }

  private Session signIn(String role) throws Exception {
    String email = uuid() + "@example.vn";
    String password = "mat-khau-it-05";
    String register =
        json.writeValueAsString(
            Map.of("email", email, "password", password, "displayName", "Người gửi đề xuất"));
    String userId =
        body(request("POST", "/api/v1/auth/register", null, register), 201).get("id").asString();
    if (!role.equals("USER")) {
      jdbc.update("UPDATE users SET role_code = ? WHERE id = ?", role, userId);
    }
    String login =
        json.writeValueAsString(Map.of("email", email, "password", password, "client", "MOBILE"));
    String token =
        body(request("POST", "/api/v1/auth/login", null, login), 200).get("token").asString();
    return new Session(userId, token);
  }

  private HttpResponse<String> post(
      Session user, String clientId, double lat, double lng, double confidence, int labelVersion)
      throws IOException, InterruptedException {
    return http.send(
        upload(user, clientId, lat, lng, confidence, labelVersion),
        HttpResponse.BodyHandlers.ofString());
  }

  private HttpRequest upload(
      Session user, String clientId, double lat, double lng, double confidence, int labelVersion) {
    return upload(user, clientId, lat, lng, confidence, labelVersion, "P.102");
  }

  private HttpRequest upload(
      Session user, String clientId, double lat, double lng, double confidence, int labelVersion,
      String code) {
    String boundary = uuid();
    String metadata =
        json.writeValueAsString(
            Map.of(
                "clientId",
                clientId,
                "signTypeCode",
                code,
                "confidence",
                confidence,
                "location",
                Map.of("lat", lat, "lng", lng),
                "headingDeg",
                90,
                "source",
                "CAMERA",
                "flags",
                List.of(),
                "model",
                Map.of("name", "yolov8n", "version", "it", "labelMapVersion", labelVersion),
                "capturedAt",
                "2026-11-24T00:00:00Z"));
    byte[] prefix =
        ("--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"metadata\"\r\n"
                + "Content-Type: application/json\r\n\r\n"
                + metadata
                + "\r\n--"
                + boundary
                + "\r\nContent-Disposition: form-data; name=\"crop\"; filename=\"crop.jpg\"\r\n"
                + "Content-Type: image/jpeg\r\n\r\n")
            .getBytes(UTF_8);
    byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(UTF_8);
    return HttpRequest.newBuilder(uri(SUBMISSIONS))
        .timeout(Duration.ofSeconds(30))
        .header("Authorization", "Bearer " + user.token())
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .POST(HttpRequest.BodyPublishers.ofByteArrays(List.of(prefix, JPEG, suffix)))
        .build();
  }

  private CompletableFuture<HttpResponse<String>> sendAsync(HttpRequest request) {
    return http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> request(String method, String path, String token, String data)
      throws IOException, InterruptedException {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30));
    if (token != null) request.header("Authorization", "Bearer " + token);
    if (data != null) request.header("Content-Type", "application/json");
    return http.send(
        request
            .method(
                method,
                data == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(data))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private JsonNode body(HttpResponse<String> response, int expected) {
    return json.readTree(assertStatus(response, expected));
  }

  private static String assertStatus(HttpResponse<String> response, int expected) {
    assertThat(response.statusCode())
        .as("HTTP %s: %s", response.uri().getPath(), response.body())
        .isEqualTo(expected);
    return response.body();
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private static byte[] jpeg() {
    try {
      var bytes = new ByteArrayOutputStream();
      if (!ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "JPEG", bytes)) {
        throw new AssertionError("Không có ImageIO JPEG writer");
      }
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private static String uuid() {
    return UUID.randomUUID().toString();
  }

  private record Session(String id, String token) {}
}
