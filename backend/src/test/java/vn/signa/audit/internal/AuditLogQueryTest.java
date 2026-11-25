package vn.signa.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import vn.signa.audit.api.AuditEntityType;
import vn.signa.shared.error.ApiException;
import vn.signa.shared.error.ErrorCode;

class AuditLogQueryTest {

  private static final Instant T1 = Instant.parse("2026-11-01T00:00:00Z");
  private static final Instant T2 = Instant.parse("2026-11-02T00:00:00Z");

  @Test
  void fromAfterToIsValidationError() {
    assertThatThrownBy(() -> new AuditLogQuery(null, null, null, null, T2, T1, 0, 20))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
              assertThat(e.details())
                  .singleElement()
                  .satisfies(d -> assertThat(d.field()).isEqualTo("from"));
            });
  }

  @Test
  void equalBoundsAreAllowed() {
    assertThat(new AuditLogQuery(null, null, null, null, T1, T1, 0, 20).from()).isEqualTo(T1);
  }

  @Test
  void blankFiltersMeanNoFilter() {
    var q = new AuditLogQuery(null, "  ", "", " APPROVED ", null, null, 0, 20);
    assertThat(q.entityId()).isNull();
    assertThat(q.actorId()).isNull();
    assertThat(q.action()).isEqualTo("APPROVED");
    assertThat(AuditLogRepository.Where.of(q).sql()).isEqualTo(" WHERE action = :action");
  }

  @Test
  void offsetDoesNotOverflow() {
    var q = new AuditLogQuery(null, null, null, null, null, null, Integer.MAX_VALUE, 100);
    assertThat(q.offset()).isEqualTo(Integer.MAX_VALUE * 100L);
  }

  @Test
  void whereCombinesEveryFilterWithInclusiveBounds() {
    var q = new AuditLogQuery(AuditEntityType.SIGN, "s-1", "u-1", "APPROVED", T1, T2, 0, 20);
    var where = AuditLogRepository.Where.of(q);
    assertThat(where.sql())
        .isEqualTo(
            " WHERE entity_type = :entityType AND entity_id = :entityId AND actor_id = :actorId"
                + " AND action = :action AND created_at >= :from AND created_at <= :to");
    assertThat(where.params())
        .containsEntry("entityType", "SIGN")
        .containsEntry("from", Timestamp.from(T1))
        .containsEntry("to", Timestamp.from(T2));
  }

  @Test
  void noFilterMeansNoWhere() {
    var q = new AuditLogQuery(null, null, null, null, null, null, 0, 20);
    assertThat(AuditLogRepository.Where.of(q).sql()).isEmpty();
    assertThat(AuditLogRepository.Where.of(q).params()).isEmpty();
  }
}
