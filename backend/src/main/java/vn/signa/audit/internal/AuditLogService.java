package vn.signa.audit.internal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import vn.signa.audit.api.ActorNames;
import vn.signa.audit.api.ActorType;
import vn.signa.audit.api.AuditEntityType;

/** Xem lịch sử xử lý có lọc và phân trang (FR-34). Chỉ đọc. */
@Service
public class AuditLogService {

  private final AuditLogRepository logs;
  private final ActorNames actorNames;
  private final JsonMapper json;

  AuditLogService(AuditLogRepository logs, ActorNames actorNames, JsonMapper json) {
    this.logs = logs;
    this.actorNames = actorNames;
    this.json = json;
  }

  /** Đếm và đọc trang trong cùng một snapshot, rồi tra tên người thực hiện một lần cho cả trang. */
  @Transactional(readOnly = true)
  public AuditPage<AuditLogView> search(AuditLogQuery query) {
    long total = logs.count(query);
    List<AuditLogRepository.Row> rows =
        total > query.offset() ? logs.page(query) : List.<AuditLogRepository.Row>of();
    Set<String> actorIds =
        rows.stream()
            .map(AuditLogRepository.Row::actorId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<String, String> names = actorIds.isEmpty() ? Map.of() : actorNames.displayNames(actorIds);
    List<AuditLogView> items =
        rows.stream()
            .map(r ->
                new AuditLogView(
                    r.id(),
                    AuditEntityType.valueOf(r.entityType()),
                    r.entityId(),
                    r.action(),
                    ActorType.valueOf(r.actorType()),
                    r.actorId(),
                    r.actorId() == null ? null : names.get(r.actorId()),
                    parse(r.before()),
                    parse(r.after()),
                    r.reason(),
                    r.requestId(),
                    r.createdAt()))
            .toList();
    return new AuditPage<>(items, query.page(), query.size(), total);
  }

  private JsonNode parse(String value) {
    return value == null ? null : json.readTree(value);
  }
}
