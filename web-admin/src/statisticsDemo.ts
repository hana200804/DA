import { readCatalogCategories, readCatalogTypes } from './catalogDemo.ts';
import { seedDemo } from './demo.ts';
import { getAuditLogs } from './demoAudit.ts';
import {
  statisticsRange,
  type Statistics,
  type StatisticsFilters,
} from './statisticsTypes.ts';
import type { ReviewDetail } from './reviewTypes.ts';
import type { DemoDB, Upload } from './types';

const gridDegrees = 0.005;
const seededUploads = new Map(seedDemo().uploads.map((row) => [row.id, row]));

export function demoStatistics(
  db: DemoDB,
  signs: ReviewDetail[],
  filters: StatisticsFilters,
): Statistics {
  const { from, to } = statisticsRange(filters);
  const start = from ? Date.parse(from) : -Infinity;
  const end = to ? Date.parse(to) : Infinity;
  const included = (value?: string) => {
    const timestamp = Date.parse(value ?? '');
    return timestamp >= start && timestamp <= end;
  };
  const reviewTimes = new Map<string, string>();
  const events = [...getAuditLogs(db)].sort(
    (a, b) => Date.parse(a.createdAt) - Date.parse(b.createdAt),
  );
  for (const event of events) {
    if (event.entityType !== 'SIGN') continue;
    const before = event.beforeValue as { status?: string } | null | undefined;
    const after = event.afterValue as { status?: string } | null | undefined;
    if (
      event.action === 'CREATED' ||
      event.action === 'APPROVED' ||
      event.action === 'AUTO_APPROVED' ||
      event.action === 'REJECTED' ||
      event.action === 'KEPT' ||
      (event.action === 'EDITED' &&
        before?.status === 'UNDER_REVIEW' &&
        after?.status === 'APPROVED')
    )
      reviewTimes.set(event.entityId, event.createdAt);
  }
  const byDay = new Map<string, number>();
  const decisions = { approved: 0, rejected: 0 };
  let totalMs = 0;
  let samples = 0;
  for (const upload of db.uploads) {
    if (!included(upload.createdAt)) continue;
    const day = new Date(upload.createdAt).toISOString().slice(0, 10);
    byDay.set(day, (byDay.get(day) ?? 0) + 1);
  }

  const uploadsById = new Map(db.uploads.map((row) => [row.id, row]));
  const statuses = new Map<string, ReviewDetail['status']>(
    db.uploads.map((row) => [
      row.id,
      row.status === 'APPROVAL'
        ? 'APPROVED'
        : row.status === 'DELETED'
          ? 'REMOVED'
          : row.status,
    ]),
  );
  for (const sign of signs) {
    statuses.set(sign.id, sign.status);
  }

  for (const [id, status] of statuses) {
    if (status !== 'APPROVED' && status !== 'REJECTED') continue;
    const upload = uploadsById.get(id);
    const seed = seededUploads.get(id);
    const fromSeed =
      upload &&
      seed &&
      seed.createdAt === upload.createdAt &&
      seed.status === upload.status
        ? seed.decision
        : undefined;
    const decision = upload?.decision ?? fromSeed;
    const reviewedAt =
      reviewTimes.get(id) ??
      (decision?.status === status ? decision.at : undefined);
    if (!included(reviewedAt)) continue;
    if (status === 'APPROVED') decisions.approved++;
    else decisions.rejected++;
    const elapsed =
      Date.parse(reviewedAt ?? '') - Date.parse(upload?.createdAt ?? '');
    if (Number.isFinite(elapsed) && elapsed >= 0) {
      totalMs += elapsed;
      samples++;
    }
  }

  const categories = readCatalogCategories(db);
  const names = new Map(categories.map((row) => [row.code, row.name]));
  const typeCategory = new Map(
    readCatalogTypes(db).map((row) => [row.code, row.categoryCode]),
  );
  const categoryCounts = new Map<string, number>();
  const grid = new Map<string, Statistics['densityCells'][number]>();
  for (const sign of signs) {
    if (sign.status !== 'APPROVED') continue;
    const category = typeCategory.get(sign.signTypeCode) ?? 'OTHER';
    categoryCounts.set(category, (categoryCounts.get(category) ?? 0) + 1);
    const { lat, lng } = sign.location;
    if (!Number.isFinite(lat) || !Number.isFinite(lng)) continue;
    const y = Math.floor(lat / gridDegrees);
    const x = Math.floor(lng / gridDegrees);
    const key = y + ':' + x;
    const cell = grid.get(key);
    if (cell) cell.count++;
    else
      grid.set(key, {
        location: {
          lat: (y + 0.5) * gridDegrees,
          lng: (x + 0.5) * gridDegrees,
        },
        count: 1,
      });
  }
  return {
    submissionsByDay: [...byDay]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([date, count]) => ({ date, count })),
    decisions,
    processingTime: { averageMs: samples ? totalMs / samples : null, samples },
    signsByCategory: [...categoryCounts]
      .map(([categoryCode, count]) => ({
        categoryCode,
        name: names.get(categoryCode) ?? categoryCode,
        count,
      }))
      .sort(
        (a, b) =>
          b.count - a.count || a.categoryCode.localeCompare(b.categoryCode),
      ),
    densityCells: [...grid.values()],
  };
}
