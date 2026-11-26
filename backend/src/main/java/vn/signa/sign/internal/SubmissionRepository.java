package vn.signa.sign.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.sign.clustering.ClusteringOutcome;
import vn.signa.sign.clustering.GeoPoint;
import vn.signa.sign.clustering.MemberSubmission;
import vn.signa.sign.clustering.MemberUpdate;
import vn.signa.sign.clustering.SubmissionFlag;
import vn.signa.sign.clustering.SubmissionInput;
import vn.signa.sign.clustering.SubmissionStatus;
import vn.signa.sign.internal.SubmissionService.Item;
import vn.signa.sign.internal.SubmissionService.Receipt;

@Repository
public class SubmissionRepository {

  private final JdbcClient jdbc;
  private final JsonMapper json;

  SubmissionRepository(JdbcClient jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  Optional<Receipt> find(String user, String clientId) {
    return jdbc.sql(
            "SELECT id, status FROM submissions WHERE user_id = :user AND client_id = :clientId")
        .param("user", user)
        .param("clientId", clientId)
        .query((rs, row) ->
            new Receipt(rs.getString("id"), SubmissionStatus.valueOf(rs.getString("status"))))
        .optional();
  }

  void insert(
      String id,
      String user,
      String code,
      SubmissionMetadata metadata,
      String cropKey,
      Instant receivedAt) {
    jdbc.sql(
            """
            INSERT INTO submissions
              (id, user_id, client_id, sign_type_code, confidence, location, heading_deg, source, flags,
               crop_key, model_name, model_version, label_map_version, captured_at, received_at, status)
            VALUES (:id, :user, :clientId, :code, :confidence,
                    ST_GeomFromText(:location, 4326, 'axis-order=long-lat'),
                    :heading, :source, :flags, :crop, :name, :version, :labelVersion, :captured, :received,
                    'RECEIVED')
            """)
        .param("id", id)
        .param("user", user)
        .param("clientId", metadata.clientId().toString())
        .param("code", code)
        .param("confidence", metadata.confidence())
        .param("location", SignSql.point(metadata.location()))
        .param("heading", metadata.headingDeg())
        .param("source", metadata.source().name())
        .param("flags", SignSql.flags(json, metadata.flags()))
        .param("crop", cropKey)
        .param("name", metadata.modelName())
        .param("version", metadata.modelVersion())
        .param("labelVersion", metadata.labelMapVersion())
        .param("captured", SignSql.utc(metadata.capturedAt().truncatedTo(ChronoUnit.MILLIS)))
        .param("received", SignSql.utc(receivedAt))
        .update();
  }

  List<Item> items(String user, int page, int size) {
    return jdbc.sql(
            """
            SELECT id, sign_type_code, status, reject_reason, sign_id, received_at, captured_at,
                   ST_Latitude(location) AS lat, ST_Longitude(location) AS lng
            FROM submissions WHERE user_id = :user
            ORDER BY received_at DESC, id DESC LIMIT :size OFFSET :offset
            """)
        .param("user", user)
        .param("size", size)
        .param("offset", (long) page * size)
        .query((rs, row) ->
            new Item(
                rs.getString("id"),
                rs.getString("sign_type_code"),
                SubmissionStatus.valueOf(rs.getString("status")),
                rs.getString("reject_reason"),
                rs.getString("sign_id"),
                SignSql.location(rs),
                SignSql.instant(rs, "received_at"),
                SignSql.instant(rs, "captured_at")))
        .list();
  }

  long count(String user) {
    return jdbc.sql("SELECT COUNT(*) FROM submissions WHERE user_id = :user")
        .param("user", user)
        .query(Long.class)
        .single();
  }

  Optional<WorkItem> lock(String id) {
    return jdbc.sql(
            """
            SELECT id, user_id, sign_type_code, confidence, heading_deg, flags, received_at, crop_key,
                   status, ST_Latitude(location) AS lat, ST_Longitude(location) AS lng
            FROM submissions WHERE id = :id FOR UPDATE
            """)
        .param("id", id)
        .query((rs, row) ->
            new WorkItem(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("sign_type_code"),
                rs.getBigDecimal("confidence"),
                SignSql.location(rs),
                rs.getObject("heading_deg", Integer.class),
                SignSql.flags(json, rs.getString("flags"), SubmissionFlag.class),
                SignSql.instant(rs, "received_at"),
                rs.getString("crop_key"),
                SubmissionStatus.valueOf(rs.getString("status"))))
        .optional();
  }

  Map<String, List<MemberSubmission>> forSigns(List<String> ids) {
    Map<String, List<MemberSubmission>> members = new HashMap<>();
    jdbc.sql(
            """
            SELECT id, sign_id, user_id, sign_type_code, weight, received_at, status,
                   ST_Latitude(location) AS lat, ST_Longitude(location) AS lng
            FROM submissions WHERE sign_id IN (:ids) AND weight IS NOT NULL
            """)
        .param("ids", ids)
        .query(rs -> {
          while (rs.next()) {
            members
                .computeIfAbsent(rs.getString("sign_id"), id -> new ArrayList<>())
                .add(
                    new MemberSubmission(
                        rs.getString("id"),
                        rs.getString("user_id"),
                        rs.getString("sign_type_code"),
                        rs.getBigDecimal("weight"),
                        SignSql.location(rs),
                        SignSql.instant(rs, "received_at"),
                        SubmissionStatus.valueOf(rs.getString("status"))));
          }
          return members;
        });
    return members;
  }

  /**
   * {@code W_c} sau quyết định gần nhất: tổng {@code weight} theo mã của đề xuất có {@code
   * received_at > reviewed_at} (docs/04 §6). Biển chưa duyệt không có dòng nào.
   */
  Map<String, Map<String, BigDecimal>> weightsSinceReview(List<String> ids) {
    Map<String, Map<String, BigDecimal>> weights = new HashMap<>();
    jdbc.sql(
            """
            SELECT s.sign_id, s.sign_type_code, SUM(s.weight) AS weight
            FROM submissions s JOIN signs g ON g.id = s.sign_id
            WHERE s.sign_id IN (:ids) AND s.weight IS NOT NULL
              AND g.reviewed_at IS NOT NULL AND s.received_at > g.reviewed_at
            GROUP BY s.sign_id, s.sign_type_code
            """)
        .param("ids", ids)
        .query(rs -> {
          weights
              .computeIfAbsent(rs.getString("sign_id"), id -> new HashMap<>())
              .put(rs.getString("sign_type_code"), rs.getBigDecimal("weight"));
        });
    return weights;
  }

  public String bestCrop(String signId, String submissionId, String code) {
    return jdbc.sql(
            """
            SELECT crop_key FROM submissions
            WHERE sign_type_code = :code AND (sign_id = :sign OR id = :submission)
            ORDER BY confidence DESC, received_at, id LIMIT 1
            """)
        .param("code", code)
        .param("sign", signId)
        .param("submission", submissionId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  void finish(String submissionId, ClusteringOutcome result) {
    jdbc.sql(
            """
            UPDATE submissions SET status = :status, weight = :weight, sign_id = :sign,
              reject_reason = :reason WHERE id = :id
            """)
        .param("status", result.submissionStatus().name())
        .param("weight", result.submissionWeight())
        .param("sign", result.sign().id())
        .param("reason", result.rejectReason())
        .param("id", submissionId)
        .update();
  }

  void updateMember(MemberUpdate member) {
    jdbc.sql("UPDATE submissions SET status = :status, reject_reason = :reason WHERE id = :id")
        .param("status", member.status().name())
        .param("reason", member.rejectReason())
        .param("id", member.submissionId())
        .update();
  }

  List<Received> receivedBefore(Instant cutoff, Received cursor, int batch) {
    return jdbc.sql(
            """
            SELECT id, received_at FROM submissions
            WHERE status = 'RECEIVED' AND received_at < :cutoff
              AND (received_at > :from OR (received_at = :from AND id > :id))
            ORDER BY received_at, id LIMIT :batch
            """)
        .param("cutoff", SignSql.utc(cutoff))
        .param("from", SignSql.utc(cursor.receivedAt()))
        .param("id", cursor.id())
        .param("batch", batch)
        .query((rs, row) -> new Received(rs.getString("id"), SignSql.instant(rs, "received_at")))
        .list();
  }

  long countReceived() {
    return jdbc.sql("SELECT COUNT(*) FROM submissions WHERE status = 'RECEIVED'")
        .query(Long.class)
        .single();
  }

  Optional<Instant> oldestReceived() {
    return jdbc.sql(
            """
            SELECT received_at FROM submissions
            WHERE status = 'RECEIVED' ORDER BY received_at LIMIT 1
            """)
        .query((rs, row) -> SignSql.instant(rs, "received_at"))
        .optional();
  }

  Map<String, Boolean> cropReferences(List<String> keys, Instant retiredBefore) {
    return jdbc.sql(
            """
            SELECT sub.crop_key,
                   MIN(CASE WHEN s.status = 'REJECTED' AND s.reviewed_at < :retiredBefore
                       THEN 1 ELSE 0 END) AS expired
            FROM submissions sub LEFT JOIN signs s ON s.id = sub.sign_id
            WHERE sub.crop_key IN (:keys)
            GROUP BY sub.crop_key
            """)
        .param("keys", keys)
        .param("retiredBefore", SignSql.utc(retiredBefore))
        .query(rs -> {
          Map<String, Boolean> references = new HashMap<>();
          while (rs.next()) references.put(rs.getString("crop_key"), rs.getBoolean("expired"));
          return references;
        });
  }

  record Received(String id, Instant receivedAt) {}

  record WorkItem(
      String id,
      String userId,
      String signTypeCode,
      BigDecimal confidence,
      GeoPoint location,
      Integer heading,
      Set<SubmissionFlag> flags,
      Instant receivedAt,
      String cropKey,
      SubmissionStatus status) {
    SubmissionInput input(String category) {
      return new SubmissionInput(
          id, userId, signTypeCode, category, confidence, location, heading, flags, receivedAt);
    }
  }
}
