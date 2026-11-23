import type { Location, NearbySign, ReviewStatus } from './reviewTypes.ts';

export const reportReasonLabels = {
  NOT_EXIST: 'Biển không còn tồn tại',
  WRONG_TYPE: 'Sai loại biển',
  WRONG_LOCATION: 'Sai vị trí',
  OTHER: 'Lý do khác',
};

export type ReportReason = keyof typeof reportReasonLabels;
export type ReportStatus = 'PENDING' | 'ACCEPTED' | 'DISMISSED';

export interface SignReport {
  id: string;
  reason: ReportReason;
  description?: string | null;
  photoUrl?: string | null;
  reporterName?: string | null;
  createdAt: string;
  status: ReportStatus;
  resolution?: 'EDITED' | 'REMOVED' | null;
  resolutionNote?: string | null;
}

export interface ReportGroup {
  sign: NearbySign & {
    status: ReviewStatus;
    version: number;
    signName?: string;
  };
  reports: SignReport[];
}

export interface ReportFilters {
  page: number;
  size: number;
  status: ReportStatus;
  reason?: ReportReason;
}

export interface ReportChanges {
  signTypeCode?: string;
  location?: Location;
}

export type ReportDecision =
  | { action: 'EDIT'; version: number; changes: ReportChanges }
  | { action: 'REMOVE'; version: number }
  | { action: 'DISMISS'; note: string };

export function normalizeReportDecision(
  decision: ReportDecision,
): ReportDecision {
  if (decision.action === 'DISMISS') {
    const note = decision.note.trim();
    if (!note || note.length > 1000)
      throw new Error('Nhập lý do từ chối dài từ 1 đến 1.000 ký tự.');
    return { action: 'DISMISS', note };
  }
  if (!Number.isInteger(decision.version) || decision.version < 0)
    throw new Error('Thiếu phiên bản của biển. Vui lòng tải lại báo cáo.');
  if (decision.action === 'REMOVE') return decision;

  const { location } = decision.changes;
  const signTypeCode = decision.changes.signTypeCode?.trim();
  if (!signTypeCode && !location)
    throw new Error('Vui lòng thay đổi mã biển hoặc tọa độ.');
  if (signTypeCode && signTypeCode.length > 30)
    throw new Error('Mã biển không được dài quá 30 ký tự.');
  if (
    location &&
    (!Number.isFinite(location.lat) ||
      !Number.isFinite(location.lng) ||
      Math.abs(location.lat) > 90 ||
      Math.abs(location.lng) > 180)
  )
    throw new Error('Tọa độ không hợp lệ.');
  return { ...decision, changes: { signTypeCode, location } };
}
