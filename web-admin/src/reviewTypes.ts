export type ReviewStatus =
  | 'PENDING'
  | 'UNDER_REVIEW'
  | 'APPROVED'
  | 'REJECTED'
  | 'REMOVED';
export type ReviewSuggestion = 'APPROVE' | 'INSPECT' | 'WAIT';

export interface Location {
  lat: number;
  lng: number;
}
export interface NearbySign {
  id: string;
  signTypeCode: string;
  location: Location;
  status?: ReviewStatus;
}
export interface ReviewSign extends NearbySign {
  status: ReviewStatus;
  headingDeg?: number | null;
  confidence: number;
  suggestion: ReviewSuggestion | null;
  distinctSubmitters?: number;
  flags?: string[];
  createdAt?: string;
  signName?: string;
}
export interface ReviewSubmission {
  id: string;
  signTypeCode: string;
  confidence: number;
  cropUrl?: string | null;
}
export interface ReviewDetail extends ReviewSign {
  version: number;
  relatedSignId?: string | null;
  coverCropUrl?: string | null;
  submissions: ReviewSubmission[];
  codeWeights: Record<string, number>;
  nearbySigns: NearbySign[];
}
export interface ReviewFilters {
  page: number;
  size: number;
  suggestion?: ReviewSuggestion | '';
  status?: 'PENDING' | 'UNDER_REVIEW' | '';
}
export type ReviewDecision =
  | { action: 'approve' | 'edit'; signTypeCode?: string; location?: Location }
  | { action: 'reject'; reason: string }
  | { action: 'remove'; reason: string }
  | { action: 'keep' };

export function reviewReasonProblem(reason: string) {
  const length = reason.trim().length;
  return length < 1 || length > 1_000
    ? 'Vui lòng nhập lý do dài từ 1 đến 1.000 ký tự.'
    : null;
}

export function normalizeReviewReason(reason: string) {
  const problem = reviewReasonProblem(reason);
  if (problem) throw new Error(problem);
  return reason.trim();
}

export const suggestionLabels: Record<ReviewSuggestion, string> = {
  INSPECT: 'Cần xem kỹ',
  APPROVE: 'Nên duyệt',
  WAIT: 'Chờ thêm',
};

export function reviewFlagLabel(flag: string) {
  return flag === 'DIFFERS_FROM_APPROVED' ? 'Khác mã với biển đã duyệt' : flag;
}

export function reviewInspectionReasons(sign: ReviewDetail) {
  if (sign.suggestion !== 'INSPECT') return [];

  const reasons: string[] = [];
  if (sign.status === 'UNDER_REVIEW')
    reasons.push('Biển đang được rà soát lại.');
  if (sign.flags?.includes('DIFFERS_FROM_APPROVED'))
    reasons.push('Mã biển khác với biển đã duyệt ở gần.');

  let total = 0;
  let competitor = { code: '', weight: 0 };
  for (const [code, weight] of Object.entries(sign.codeWeights)) {
    total += weight;
    if (code !== sign.signTypeCode && weight > competitor.weight)
      competitor = { code, weight };
  }
  if (competitor.code && (!reasons.length || competitor.weight >= 0.3 * total))
    reasons.push(`Có mã cạnh tranh ${competitor.code} từ các đề xuất.`);

  return reasons.length
    ? reasons
    : ['Cần đối chiếu ảnh và vị trí trước khi quyết định.'];
}

export function isReviewable(status: ReviewStatus) {
  return status === 'PENDING' || status === 'UNDER_REVIEW';
}
