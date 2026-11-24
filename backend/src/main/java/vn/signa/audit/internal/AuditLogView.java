package vn.signa.audit.internal;

import java.time.Instant;
import tools.jackson.databind.JsonNode;
import vn.signa.audit.api.ActorType;
import vn.signa.audit.api.AuditEntityType;

/**
 * Một dòng lịch sử trả cho web quản trị (docs/07 §8). {@code beforeValue}, {@code afterValue} là
 * JSON object, không phải chuỗi; {@code actorId}, {@code actorName} là {@code null} khi {@code
 * SYSTEM}.
 */
public record AuditLogView(
    long id,
    AuditEntityType entityType,
    String entityId,
    String action,
    ActorType actorType,
    String actorId,
    String actorName,
    JsonNode beforeValue,
    JsonNode afterValue,
    String reason,
    String requestId,
    Instant createdAt) {}
