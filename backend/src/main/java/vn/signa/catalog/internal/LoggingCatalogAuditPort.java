package vn.signa.catalog.internal;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Cài đặt tạm của {@link CatalogAuditPort}: chỉ ghi log, chưa ghi {@code audit_logs}. Thay bằng
 * adapter {@code AuditRecorder} ở phần 2 của lát 2, sau khi merge lát 1.
 */
@Component
class LoggingCatalogAuditPort implements CatalogAuditPort {

  private static final Logger log = LoggerFactory.getLogger(LoggingCatalogAuditPort.class);

  @Override
  public void recordSystem(
      Entity entity, String code, String action, Map<String, ?> before, Map<String, ?> after) {
    log.info(
        "Lịch sử catalog (tạm): {} {} {} trước={} sau={}", action, entity, code, before, after);
  }
}
