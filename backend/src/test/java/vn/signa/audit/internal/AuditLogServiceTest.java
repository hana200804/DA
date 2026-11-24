package vn.signa.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.audit.api.ActorNames;
import vn.signa.audit.api.ActorType;
import vn.signa.audit.internal.AuditLogRepository.Row;

class AuditLogServiceTest {

  private static final Instant AT = Instant.parse("2026-11-24T08:00:00Z");

  private final AuditLogRepository repository = mock(AuditLogRepository.class);
  private final ActorNames names = mock(ActorNames.class);
  private final AuditLogService service =
      new AuditLogService(repository, names, JsonMapper.builder().build());

  private static Row row(long id, String actorType, String actorId) {
    return new Row(
        id,
        "USER",
        "u-target",
        "ROLE_CHANGED",
        actorType,
        actorId,
        "{\"role\":\"USER\"}",
        "{\"role\":\"MODERATOR\"}",
        null,
        "req-1",
        AT);
  }

  private static AuditLogQuery query() {
    return new AuditLogQuery(null, null, null, null, null, null, 0, 20);
  }

  @Test
  void namesAreLookedUpOnceForThePageAndValuesAreJsonObjects() {
    when(repository.count(any())).thenReturn(3L);
    when(repository.page(any()))
        .thenReturn(List.of(row(3, "USER", "u-a"), row(2, "USER", "u-a"), row(1, "SYSTEM", null)));
    when(names.displayNames(anyCollection())).thenReturn(Map.of("u-a", "Nguyễn Văn A"));

    AuditPage<AuditLogView> page = service.search(query());

    verify(names, times(1)).displayNames(Set.of("u-a"));
    assertThat(page.total()).isEqualTo(3);
    assertThat(page.items()).extracting(AuditLogView::id).containsExactly(3L, 2L, 1L);
    AuditLogView first = page.items().getFirst();
    assertThat(first.actorName()).isEqualTo("Nguyễn Văn A");
    assertThat(first.beforeValue().isObject()).isTrue();
    assertThat(first.beforeValue().get("role").asString()).isEqualTo("USER");
    assertThat(first.afterValue().get("role").asString()).isEqualTo("MODERATOR");
    AuditLogView system = page.items().get(2);
    assertThat(system.actorType()).isEqualTo(ActorType.SYSTEM);
    assertThat(system.actorId()).isNull();
    assertThat(system.actorName()).isNull();
  }

  @Test
  void nullJsonStaysNull() {
    when(repository.count(any())).thenReturn(1L);
    Row created = new Row(1, "SIGN", "s-1", "CREATED", "SYSTEM", null, null, "{}", null, null, AT);
    when(repository.page(any())).thenReturn(List.of(created));

    AuditLogView view = service.search(query()).items().getFirst();

    assertThat(view.beforeValue()).isNull();
    assertThat(view.afterValue().isObject()).isTrue();
    verify(names, never()).displayNames(anyCollection());
  }

  @Test
  void pageBeyondTotalSkipsTheQuery() {
    when(repository.count(any())).thenReturn(5L);

    var page = service.search(new AuditLogQuery(null, null, null, null, null, null, 1, 5));

    assertThat(page.items()).isEmpty();
    assertThat(page.total()).isEqualTo(5);
    verify(repository, never()).page(any());
  }
}
