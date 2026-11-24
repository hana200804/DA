package vn.signa.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.catalog.internal.CatalogRepository.TypeRow;
import vn.signa.catalog.internal.CatalogResponses.SignTypesSnapshot;

class CatalogQueryServiceTest {

  private final LabelImporterTest.FakeRepository repository =
      new LabelImporterTest.FakeRepository();
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);

  @SuppressWarnings("unchecked")
  private final ValueOperations<String, String> values = mock(ValueOperations.class);

  private CatalogQueryService service;

  @BeforeEach
  void setUp() {
    when(redis.opsForValue()).thenReturn(values);
    service =
        new CatalogQueryService(
            repository,
            new SignTypesCache(redis, new CatalogProperties(false, Duration.ofHours(24))),
            JsonMapper.builder().build());
    repository.types.put(
        "P.127_50",
        new TypeRow(
            "P.127_50", "PROHIBITORY", "Tốc độ 50", "Mô tả", 50, "P.127_50.png", 13, 1, "x", true));
    repository.types.put(
        "M.1", new TypeRow("M.1", "OTHER", "Nhập tay", null, null, null, null, null, null, true));
    repository.types.put(
        "OLD", new TypeRow("OLD", "OTHER", "Ngừng", null, null, null, 3, 1, "Ngừng", false));
  }

  @Test
  void buildsOnlyActiveTypesAndCachesBody() {
    SignTypesSnapshot snapshot = service.signTypes();

    assertThat(snapshot.json())
        .isEqualTo(
            "{\"labelMapVersion\":1,\"items\":["
                + "{\"code\":\"M.1\",\"categoryCode\":\"OTHER\",\"name\":\"Nhập tay\","
                + "\"description\":null,\"speedKmh\":null,\"iconUrl\":null,\"recognizable\":false},"
                + "{\"code\":\"P.127_50\",\"categoryCode\":\"PROHIBITORY\",\"name\":\"Tốc độ 50\","
                + "\"description\":\"Mô tả\",\"speedKmh\":50,\"iconUrl\":\"/icons/P.127_50.png\","
                + "\"recognizable\":true}]}");
    assertThat(snapshot.etag()).isEqualTo(ETags.of(snapshot.json()));
    verify(values).set(SignTypesCache.key(1), snapshot.json(), Duration.ofHours(24));
  }

  @Test
  void servesCachedBodyWithoutReadingTypes() {
    when(values.get(SignTypesCache.key(1))).thenReturn("{\"cached\":true}");
    repository.types.clear();

    SignTypesSnapshot snapshot = service.signTypes();

    assertThat(snapshot.json()).isEqualTo("{\"cached\":true}");
    assertThat(snapshot.etag()).isEqualTo(ETags.of("{\"cached\":true}"));
    verify(values, never()).set(any(), any(), any(Duration.class));
  }

  @Test
  void fallsBackToDatabaseWhenRedisIsDown() {
    when(values.get(SignTypesCache.key(1))).thenThrow(new RedisConnectionFailureException("down"));
    org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down"))
        .when(values)
        .set(eq(SignTypesCache.key(1)), any(), any(Duration.class));

    assertThat(service.signTypes().json()).contains("P.127_50");
  }

  @Test
  void aLateWriteToThePreviousGenerationCannotRepublishTheOldCatalog() {
    doAnswer(
            call -> {
              // Admin commit sau khi GET lấy danh mục từ MySQL, trước khi nội dung đi vào Redis.
              repository.types.remove("P.127_50");
              repository.advanceGeneration();
              when(values.get(SignTypesCache.key(1))).thenReturn(call.getArgument(1));
              return null;
            })
        .when(values)
        .set(eq(SignTypesCache.key(1)), any(), any(Duration.class));

    assertThat(service.signTypes().json()).contains("P.127_50");
    assertThat(service.signTypes().json()).doesNotContain("P.127_50");
    verify(values).get(SignTypesCache.key(2));
  }

  @Test
  void labelMapVersionIsNullBeforeImport() {
    repository.types.clear();

    assertThat(service.signTypes().json()).isEqualTo("{\"labelMapVersion\":null,\"items\":[]}");
  }

  @Test
  void detailHidesInactiveTypes() {
    assertThat(service.activeType("P.127_50")).isPresent();
    assertThat(service.activeType("OLD")).isEmpty();
    assertThat(service.activeType("NOPE")).isEmpty();
  }
}
