import type { AuditLog } from './auditTypes.ts';
import type { DemoDB } from './types';

export function getAuditLogs(db: DemoDB): AuditLog[] {
  return (
    db.auditLogs ??
    (db.mapAudit ?? []).map((log, index) => ({
      id: 'map-' + index,
      entityType: 'SIGN',
      entityId: log.signId,
      action: log.action,
      actorType: 'USER',
      actorId: log.actorId,
      createdAt: log.createdAt,
      beforeValue: log.before,
      afterValue: log.after,
      reason: log.reason,
    }))
  );
}

export function appendAudit(
  db: DemoDB,
  event: Omit<AuditLog, 'id' | 'actorId' | 'actorType' | 'createdAt'>,
) {
  db.auditLogs = getAuditLogs(db);
  db.auditLogs.push({
    ...event,
    id: crypto.randomUUID(),
    actorType: 'USER',
    actorId: 'admin.demo',
    createdAt: new Date().toISOString(),
  });
  delete db.mapAudit;
}
