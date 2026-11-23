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
}
export interface ReviewSign extends NearbySign {
  status: ReviewStatus;
  headingDeg?: number | null;
  confidence: number;
  suggestion: ReviewSuggestion | null;
  createdAt?: string;
  signName?: string;
}
export interface ReviewSubmission {
  id: string;
  signTypeCode: string;
  confidence: number;
  cropUrl?: string;
}
export interface ReviewDetail extends ReviewSign {
  version: number;
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

export function isReviewable(status: ReviewStatus) {
  return status === 'PENDING' || status === 'UNDER_REVIEW';
}
