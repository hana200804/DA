package vn.signa.sign.clustering;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Một dòng lịch sử cần ghi vào {@code audit_logs}. Mọi sự kiện của lõi gom cụm do hệ thống thực
 * hiện ({@code actor_type = SYSTEM}).
 *
 * @param before các trường thay đổi, giá trị trước; {@code null} khi tạo mới
 * @param after giá trị sau
 */
public record SignEvent(
    SignEventType type, Map<String, Object> before, Map<String, Object> after, String reason) {

  public static final String ACTOR_SYSTEM = "SYSTEM";

  public SignEvent {
    Objects.requireNonNull(type, "type");
    before = before == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(before));
    after = after == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(after));
  }

  /** Giá trị {@code audit_logs.actor_type}. */
  public String actorType() {
    return ACTOR_SYSTEM;
  }
}
