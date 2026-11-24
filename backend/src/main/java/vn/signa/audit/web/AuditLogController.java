package vn.signa.audit.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.signa.audit.api.AuditEntityType;
import vn.signa.audit.internal.AuditLogQuery;
import vn.signa.audit.internal.AuditLogService;
import vn.signa.audit.internal.AuditLogView;
import vn.signa.audit.internal.AuditPage;

/**
 * Lịch sử xử lý cho web quản trị (docs/07 §8, FR-34). Chỉ có {@code GET}: lịch sử không sửa, không
 * xoá được qua API (FR-23).
 */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@PreAuthorize("hasAuthority('audit:read')")
class AuditLogController {

  private final AuditLogService service;

  AuditLogController(AuditLogService service) {
    this.service = service;
  }

  @GetMapping
  AuditPage<AuditLogView> list(
      @RequestParam(required = false) AuditEntityType entityType,
      @RequestParam(required = false) @Size(max = 36) String entityId,
      @RequestParam(required = false) @Size(max = 36) String actorId,
      @RequestParam(required = false) @Size(max = 40) String action,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return service.search(
        new AuditLogQuery(entityType, entityId, actorId, action, from, to, page, size));
  }
}
