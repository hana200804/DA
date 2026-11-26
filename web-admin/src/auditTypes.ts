export const auditEntityLabels = {
  SIGN: 'Biển báo',
  REPORT: 'Báo cáo',
  SIGN_TYPE: 'Loại biển',
  SIGN_CATEGORY: 'Nhóm biển',
  USER: 'Tài khoản',
};

export const auditActionLabels: Record<string, string> = {
  CREATED: 'Tạo mới',
  LEADING_CODE_CHANGED: 'Đổi mã dẫn đầu',
  APPROVED: 'Phê duyệt',
  AUTO_APPROVED: 'Tự động phê duyệt',
  REJECTED: 'Từ chối biển',
  EDITED: 'Chỉnh sửa',
  MARKED_UNDER_REVIEW: 'Chuyển sang rà soát',
  KEPT: 'Giữ biển',
  REMOVED: 'Gỡ biển',
  ACCEPTED: 'Chấp nhận báo cáo',
  DISMISSED: 'Từ chối báo cáo',
  UPDATED: 'Cập nhật',
  DEACTIVATED: 'Ngừng sử dụng',
  ROLE_CHANGED: 'Đổi vai trò',
  LOCKED: 'Khóa tài khoản',
  UNLOCKED: 'Mở khóa tài khoản',
};

export interface AuditLog {
  id: number | string;
  entityType: keyof typeof auditEntityLabels;
  entityId: string;
  action: string;
  actorType: 'USER' | 'SYSTEM';
  actorId: string | null;
  actorName?: string | null;
  createdAt: string;
  beforeValue?: unknown;
  afterValue?: unknown;
  reason?: string | null;
  requestId?: string | null;
}

export interface AuditFilters {
  page: number;
  size: number;
  signId?: string;
  actorId?: string;
  action?: string;
  fromDate?: string;
  toDate?: string;
}

export function auditQuery(filters: AuditFilters) {
  const signId = filters.signId?.trim();
  const actorId = filters.actorId?.trim();
  return {
    entityType: signId ? 'SIGN' : undefined,
    entityId: signId || undefined,
    actorId: actorId || undefined,
    action: filters.action || undefined,
    ...dateRangeUtc(filters),
  };
}

export function dateRangeUtc(
  filters: Pick<AuditFilters, 'fromDate' | 'toDate'>,
  offset = '+07:00',
) {
  if (filters.fromDate && filters.toDate && filters.fromDate > filters.toDate)
    throw new Error('Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.');
  return {
    from: filters.fromDate
      ? new Date(filters.fromDate + 'T00:00:00.000' + offset).toISOString()
      : undefined,
    to: filters.toDate
      ? new Date(filters.toDate + 'T23:59:59.999' + offset).toISOString()
      : undefined,
  };
}

export function formatAuditTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? '—'
    : new Intl.DateTimeFormat('vi-VN', {
        timeZone: 'Asia/Bangkok',
        dateStyle: 'short',
        timeStyle: 'medium',
      }).format(date);
}
