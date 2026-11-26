import { demoStatistics } from './statisticsDemo.ts';
import type { StatisticsFilters } from './statisticsTypes.ts';
import { readCatalogTypes } from './catalogDemo.ts';
import { appendAudit, getAuditLogs } from './demoAudit.ts';
import { auditQuery, type AuditFilters } from './auditTypes.ts';
import { readDemo, writeDemo } from './demo.ts';
import { distanceMeters } from './domain.ts';
import {
  normalizeReportDecision,
  type ReportDecision,
  type ReportFilters,
  type ReportGroup,
  type SignReport,
} from './reportTypes.ts';
import {
  isPublished,
  normalizeRemovalReason,
  validateSignChanges,
  validateSignVersion,
  type MapBounds,
  type MapResult,
  type MapSign,
  type MapSignInput,
  type MapSignPatch,
} from './mapTypes.ts';
import type { DemoDB, Report, Status } from './types';
import { ReviewApiError } from './reviewApi.ts';
import {
  isReviewable,
  normalizeReviewReason,
  type ReviewDecision,
  type ReviewDetail,
  type ReviewFilters,
} from './reviewTypes.ts';

function seedReviews({ uploads }: DemoDB): ReviewDetail[] {
  return uploads
    .filter((row) => row.status === 'PENDING' || row.status === 'APPROVAL')
    .map((row, index) => {
      const {
        id,
        trafficSignId: signTypeCode,
        latitude: lat,
        longitude: lng,
      } = row;
      const competingCode =
        signTypeCode === 'P.127_60' ? 'P.127_50' : 'P.127_60';
      const hasConflict = index % 3 === 0;
      const status =
        row.status === 'PENDING'
          ? 'PENDING'
          : index % 7 === 0
            ? 'UNDER_REVIEW'
            : 'APPROVED';
      return {
        id,
        signTypeCode,
        location: { lat, lng },
        status,
        version: 1,
        signName: row.trafficSignName,
        createdAt: row.createdAt,
        confidence: hasConflict ? 0.65 : index % 3 === 1 ? 0.84 : 0.72,
        distinctSubmitters: 1,
        coverCropUrl: row.photoBase64 || null,
        suggestion:
          status === 'APPROVED'
            ? null
            : status === 'UNDER_REVIEW' || hasConflict
              ? 'INSPECT'
              : index % 3 === 1
                ? 'APPROVE'
                : 'WAIT',
        submissions: [
          {
            id: id + '-1',
            signTypeCode,
            confidence: row.accuracyAI,
            cropUrl: row.photoBase64,
          },
          ...(hasConflict
            ? [{ id: id + '-2', signTypeCode: competingCode, confidence: 0.67 }]
            : []),
        ],
        codeWeights: hasConflict
          ? { [signTypeCode]: 0.94, [competingCode]: 0.67 }
          : { [signTypeCode]: 1.8 },
        nearbySigns: [],
      };
    });
}

const uploadStatuses: Record<ReviewDetail['status'], Status> = {
  PENDING: 'PENDING',
  UNDER_REVIEW: 'APPROVAL',
  APPROVED: 'APPROVAL',
  REJECTED: 'REJECTED',
  REMOVED: 'DELETED',
};

function readReviews(db = readDemo()): ReviewDetail[] {
  return (db.reviewSigns ?? seedReviews(db)).map((sign) => {
    const upload = db.uploads.find((row) => row.id === sign.id);
    if (!upload || upload.status === uploadStatuses[sign.status]) return sign;
    const status =
      upload.status === 'DELETED'
        ? 'REMOVED'
        : upload.status === 'APPROVAL'
          ? 'APPROVED'
          : upload.status;
    return { ...sign, status, version: sign.version + 1, suggestion: null };
  });
}

function findSign(rows: ReviewDetail[], id: string) {
  const row = rows.find((sign) => sign.id === id);
  if (!row)
    throw new ReviewApiError(404, 'NOT_FOUND', 'Không tìm thấy biển báo.');
  return row;
}

const reportStatuses: Record<Status, SignReport['status']> = {
  PENDING: 'PENDING',
  APPROVAL: 'ACCEPTED',
  REJECTED: 'DISMISSED',
  DELETED: 'DISMISSED',
};

function demoReport(row: Report): SignReport {
  return {
    id: row.id,
    reason:
      row.reasonCode ??
      (row.id === 'BC-100'
        ? 'NOT_EXIST'
        : row.id === 'BC-101'
          ? 'WRONG_LOCATION'
          : 'OTHER'),
    description: row.reason,
    photoUrl: row.photoBase64,
    reporterName: row.custId,
    createdAt: row.createdAt,
    status: reportStatuses[row.status],
    resolution: row.resolution,
    resolutionNote: row.resolutionNote,
  };
}

function mapSnapshot({
  id,
  location,
  signTypeCode,
  status,
  headingDeg,
  signName,
}: ReviewDetail): MapSign {
  return { id, location, signTypeCode, status, headingDeg, signName };
}

function recordSignChange(
  db: DemoDB,
  rows: ReviewDetail[],
  sign: ReviewDetail,
  action: string,
  before: MapSign | null,
  reason?: string,
) {
  const upload = db.uploads.find((row) => row.id === sign.id);
  if (upload) {
    upload.status = uploadStatuses[sign.status];
    upload.trafficSignId = sign.signTypeCode;
    upload.trafficSignName = sign.signName ?? sign.signTypeCode;
    upload.latitude = sign.location.lat;
    upload.longitude = sign.location.lng;
  }
  db.reviewSigns = rows;
  appendAudit(db, {
    entityType: 'SIGN',
    entityId: sign.id,
    action,
    beforeValue: before,
    afterValue: mapSnapshot(sign),
    reason,
  });
}

function loadMapChange(id: string, version: number) {
  validateSignVersion(version);
  const db = readDemo();
  const rows = readReviews(db);
  const sign = findSign(rows, id);
  if (!isPublished(sign.status) || version !== sign.version)
    throw new ReviewApiError(
      409,
      'SIGN_ALREADY_PROCESSED',
      'Biển đã thay đổi hoặc đã được gỡ.',
    );
  return { db, rows, sign, before: mapSnapshot(sign) };
}

export const reviewDemo = {
  async statistics(_token: string, filters: StatisticsFilters) {
    const db = readDemo();
    return demoStatistics(db, readReviews(db), filters);
  },
  async mapSigns(
    _token: string,
    [west, south, east, north]: MapBounds,
    _zoom: number,
  ): Promise<MapResult> {
    return {
      mode: 'SIGNS',
      items: readReviews()
        .filter(
          ({ status, location: { lat, lng } }) =>
            isPublished(status) &&
            lng >= west &&
            lng <= east &&
            lat >= south &&
            lat <= north,
        )
        .map(mapSnapshot),
    };
  },
  async mapSignTypes() {
    return readCatalogTypes()
      .filter((row) => row.active)
      .map(({ code, name }) => ({ code, name }));
  },
  async createMapSign(_token: string, input: MapSignInput) {
    const { signTypeCode, location, headingDeg } = validateSignChanges(
      input,
    ) as MapSignInput;
    const db = readDemo();
    const rows = readReviews(db);
    const type = readCatalogTypes(db).find(
      (row) => row.code === signTypeCode && row.active,
    );
    if (!type) throw new Error('Loại biển không còn được sử dụng.');
    const sign: ReviewDetail = {
      id: crypto.randomUUID(),
      version: 0,
      status: 'APPROVED',
      signTypeCode,
      location,
      headingDeg,
      signName: type.name,
      createdAt: new Date().toISOString(),
      confidence: 2.5 / 3,
      distinctSubmitters: 0,
      suggestion: null,
      submissions: [],
      codeWeights: {},
      nearbySigns: [],
    };
    rows.push(sign);
    recordSignChange(db, rows, sign, 'CREATED', null);
    writeDemo(db);
  },
  async patchMapSign(
    _token: string,
    id: string,
    version: number,
    input: MapSignPatch,
  ) {
    const changes = validateSignChanges(input);
    const { db, rows, sign, before } = loadMapChange(id, version);
    if (changes.signTypeCode) {
      const type = readCatalogTypes(db).find(
        (row) => row.code === changes.signTypeCode && row.active,
      );
      if (!type) throw new Error('Loại biển không còn được sử dụng.');
      sign.signName = type.name;
    }
    Object.assign(sign, changes);
    sign.version++;
    recordSignChange(db, rows, sign, 'EDITED', before);
    writeDemo(db);
  },
  async removeMapSign(
    _token: string,
    id: string,
    version: number,
    reason: string,
  ) {
    const note = normalizeRemovalReason(reason);
    const { db, rows, sign, before } = loadMapChange(id, version);
    sign.status = 'REMOVED';
    sign.suggestion = null;
    sign.version++;
    recordSignChange(db, rows, sign, 'REMOVED', before, note);
    writeDemo(db);
  },
  async auditLogs(_token: string, filters: AuditFilters) {
    const { entityId, entityType, actorId, action, from, to } =
      auditQuery(filters);
    const rows = getAuditLogs(readDemo())
      .filter(
        (row) =>
          (!entityType || row.entityType === entityType) &&
          (!entityId || row.entityId === entityId) &&
          (!actorId || row.actorId === actorId) &&
          (!action || row.action === action) &&
          (!from || Date.parse(row.createdAt) >= Date.parse(from)) &&
          (!to || Date.parse(row.createdAt) <= Date.parse(to)),
      )
      .reverse()
      .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt));
    return {
      rows: rows.slice(
        (filters.page - 1) * filters.size,
        filters.page * filters.size,
      ),
      total: rows.length,
      pages: Math.max(1, Math.ceil(rows.length / filters.size)),
    };
  },
  async reportQueue(
    _token: string,
    { page, size, status, reason }: ReportFilters,
  ) {
    const db = readDemo();
    const signs = new Map(readReviews(db).map((sign) => [sign.id, sign]));
    const groups = new Map<string, ReportGroup>();
    for (const report of db.reports) {
      if (reportStatuses[report.status] !== status) continue;
      const group = groups.get(report.rttsId);
      if (group) {
        group.reports.push(demoReport(report));
        continue;
      }
      const sign = signs.get(report.rttsId);
      const upload = db.uploads.find((row) => row.id === report.rttsId);
      if (!upload) continue;
      groups.set(report.rttsId, {
        sign: sign ?? {
          id: upload.id,
          version: 1,
          signTypeCode: upload.trafficSignId,
          signName: upload.trafficSignName,
          location: { lat: upload.latitude, lng: upload.longitude },
          status:
            upload.status === 'DELETED'
              ? 'REMOVED'
              : upload.status === 'APPROVAL'
                ? 'APPROVED'
                : upload.status,
        },
        reports: [demoReport(report)],
      });
    }
    const rows = [...groups.values()].filter(
      (group) =>
        !reason || group.reports.some((report) => report.reason === reason),
    );
    for (const group of rows)
      group.reports.sort((a, b) => b.createdAt.localeCompare(a.createdAt));
    rows.sort((a, b) =>
      b.reports[0].createdAt.localeCompare(a.reports[0].createdAt),
    );
    return {
      rows: rows.slice((page - 1) * size, page * size),
      total: rows.length,
      pages: Math.max(1, Math.ceil(rows.length / size)),
    };
  },
  async resolveReport(_token: string, id: string, input: ReportDecision) {
    const decision = normalizeReportDecision(input);
    const db = readDemo();
    const report = db.reports.find((row) => row.id === id);
    if (report?.status !== 'PENDING')
      throw new ReviewApiError(409, 'CONFLICT', 'Báo cáo đã được xử lý.');
    const beforeValue = {
      signId: report.rttsId,
      status: reportStatuses[report.status],
    };
    if (decision.action === 'DISMISS') {
      report.status = 'REJECTED';
      report.resolutionNote = decision.note;
    } else {
      const rows = readReviews(db);
      const sign = rows.find((row) => row.id === report.rttsId);
      const upload = db.uploads.find((row) => row.id === report.rttsId);
      if (
        !sign ||
        !upload ||
        sign.version !== decision.version ||
        sign.status === 'REMOVED'
      )
        throw new ReviewApiError(
          409,
          'SIGN_ALREADY_PROCESSED',
          'Biển đã thay đổi hoặc đã được gỡ.',
        );
      const beforeSign = mapSnapshot(sign);
      if (decision.action === 'REMOVE') {
        sign.status = 'REMOVED';
        sign.suggestion = null;
        report.resolution = 'REMOVED';
      } else {
        const { signTypeCode, location } = decision.changes;
        if (signTypeCode && signTypeCode !== sign.signTypeCode) {
          sign.signTypeCode = signTypeCode;
          sign.signName = db.signs.find(
            (row) => row.code === signTypeCode,
          )?.name;
        }
        if (location) sign.location = location;
        report.resolution = 'EDITED';
      }
      sign.version++;
      recordSignChange(
        db,
        rows,
        sign,
        decision.action === 'REMOVE' ? 'REMOVED' : 'EDITED',
        beforeSign,
        report.reason,
      );
      report.status = 'APPROVAL';
    }
    appendAudit(db, {
      entityType: 'REPORT',
      entityId: id,
      action: decision.action === 'DISMISS' ? 'DISMISSED' : 'ACCEPTED',
      beforeValue,
      afterValue: {
        signId: report.rttsId,
        status: reportStatuses[report.status],
        resolution: report.resolution,
      },
      reason: report.resolutionNote,
    });
    writeDemo(db);
  },
  async queue(
    _token: string,
    { page, size, status, suggestion }: ReviewFilters,
  ) {
    const priority = { INSPECT: 0, APPROVE: 1, WAIT: 2 };
    const rows = readReviews()
      .filter(
        (sign) =>
          isReviewable(sign.status) &&
          (!status || sign.status === status) &&
          (!suggestion || sign.suggestion === suggestion),
      )
      .sort(
        (a, b) =>
          (a.suggestion ? priority[a.suggestion] : 3) -
            (b.suggestion ? priority[b.suggestion] : 3) ||
          b.confidence - a.confidence,
      );
    return {
      rows: rows.slice((page - 1) * size, page * size),
      total: rows.length,
      pages: Math.max(1, Math.ceil(rows.length / size)),
    };
  },
  async detail(_token: string, id: string) {
    const rows = readReviews();
    const sign = findSign(rows, id);
    const origin = {
      latitude: sign.location.lat,
      longitude: sign.location.lng,
    };
    const nearbySigns = rows
      .filter((row) => row.id !== id && isPublished(row.status))
      .map(({ id, signTypeCode, status, location }) => ({
        sign: { id, signTypeCode, status, location },
        distance: distanceMeters(origin, {
          latitude: location.lat,
          longitude: location.lng,
        }),
      }))
      .filter(({ distance }) => distance <= 50)
      .sort((a, b) => a.distance - b.distance)
      .slice(0, 50)
      .map(({ sign }) => sign);
    return { ...sign, nearbySigns };
  },
  async decide(
    _token: string,
    snapshot: ReviewDetail,
    decision: ReviewDecision,
  ) {
    const db = readDemo();
    const rows = readReviews(db);
    const sign = findSign(rows, snapshot.id);
    if (sign.version !== snapshot.version || !isReviewable(sign.status))
      throw new ReviewApiError(
        409,
        'SIGN_ALREADY_PROCESSED',
        'Biển đã được người khác xử lý.',
      );

    const before = mapSnapshot(sign);
    const reason =
      'reason' in decision ? normalizeReviewReason(decision.reason) : undefined;
    if (decision.action === 'reject' || decision.action === 'remove') {
      sign.status = decision.action === 'reject' ? 'REJECTED' : 'REMOVED';
    } else {
      if (decision.action !== 'keep') {
        if (
          decision.signTypeCode &&
          decision.signTypeCode !== sign.signTypeCode
        ) {
          sign.signTypeCode = decision.signTypeCode;
          sign.signName = undefined;
        }
        if (decision.location) sign.location = decision.location;
      }
      sign.status = 'APPROVED';
    }
    sign.version++;
    sign.suggestion = null;
    const action = {
      approve: 'APPROVED',
      reject: 'REJECTED',
      edit: 'EDITED',
      keep: 'KEPT',
      remove: 'REMOVED',
    }[decision.action];
    recordSignChange(db, rows, sign, action, before, reason);
    writeDemo(db);
  },
};
