package vn.signa.sign.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.sign.clustering.GeoPoint;
import vn.signa.sign.clustering.SignFlag;
import vn.signa.sign.clustering.SignState;
import vn.signa.sign.clustering.SignStatus;

class SignRepositoryTest {

  @Test
  void updatesEvidenceAndIncrementsExactlyThePreviousVersion() {
    JdbcClient jdbc = mock(JdbcClient.class);
    JdbcClient.StatementSpec sql = mock(JdbcClient.StatementSpec.class);
    when(jdbc.sql(anyString())).thenReturn(sql);
    when(sql.params(anyMap())).thenReturn(sql);
    when(sql.param("expectedVersion", 5)).thenReturn(sql);
    when(sql.update()).thenReturn(1, 0);

    var repository = new SignRepository(jdbc, JsonMapper.builder().build());
    var sign =
        SignState.builder()
            .id("sign")
            .signTypeCode("P.102")
            .categoryCode("PROHIBITORY")
            .status(SignStatus.APPROVED)
            .location(new GeoPoint(21.3, 105.4))
            .headingDeg(92)
            .locationLocked(true)
            .distinctSubmitters(4)
            .confirmCount(2)
            .denyCount(1)
            .denySinceReview(1)
            .moderatorBoost(new BigDecimal("2.00"))
            .confidence(new BigDecimal("0.8010"))
            .flags(Set.of(SignFlag.DIFFERS_FROM_APPROVED))
            .relatedSignId("other")
            .reviewedAt(Instant.parse("2026-11-20T08:15:00Z"))
            .version(6)
            .build();
    var now = Instant.parse("2026-11-24T09:15:00Z");

    repository.update(sign, "cover.jpg", now);

    verify(jdbc).sql(contains("version = version + 1"));
    verify(jdbc).sql(contains("WHERE id = :id AND version = :expectedVersion"));
    verify(sql).param("expectedVersion", 5);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> changes = ArgumentCaptor.forClass(Map.class);
    verify(sql).params(changes.capture());
    assertThat(changes.getValue())
        .containsEntry("id", "sign")
        .containsEntry("status", "APPROVED")
        .containsEntry("code", "P.102")
        .containsEntry("category", "PROHIBITORY")
        .containsEntry("location", "POINT(105.4 21.3)")
        .containsEntry("heading", 92)
        .containsEntry("locked", true)
        .containsEntry("submitters", 4)
        .containsEntry("confirms", 2)
        .containsEntry("denies", 1)
        .containsEntry("deniesSinceReview", 1)
        .containsEntry("boost", new BigDecimal("2.00"))
        .containsEntry("confidence", new BigDecimal("0.8010"))
        .containsEntry("suggestion", null)
        .containsEntry("flags", "[\"DIFFERS_FROM_APPROVED\"]")
        .containsEntry("related", "other")
        .containsEntry("cover", "cover.jpg")
        .containsEntry("reviewed", LocalDateTime.parse("2026-11-20T08:15:00"))
        .containsEntry("now", LocalDateTime.parse("2026-11-24T09:15:00"));

    assertThatThrownBy(() -> repository.update(sign, "cover.jpg", now))
        .isInstanceOf(IllegalStateException.class);
  }
}
