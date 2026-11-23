import type { Page, Status } from './types.ts';

export const statusLabels: Record<Status, string> = {
  PENDING: 'Chờ duyệt',
  APPROVAL: 'Đã duyệt',
  REJECTED: 'Từ chối',
  DELETED: 'Đã xóa',
};
export function searchText(value: string) {
  return value
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/đ/g, 'd')
    .replace(/Đ/g, 'D')
    .toLowerCase();
}
export function matches(query: string, ...values: (string | undefined)[]) {
  return searchText(values.filter(Boolean).join(' ')).includes(
    searchText(query.trim()),
  );
}
export function paginate<T>(rows: T[], page: number, size: number): Page<T> {
  const pages = Math.max(1, Math.ceil(rows.length / size));
  const safePage = Math.max(1, Math.min(page, pages));
  return {
    rows: rows.slice((safePage - 1) * size, safePage * size),
    total: rows.length,
    pages,
  };
}
export function normalizedImage(value?: string) {
  if (!value) return '';
  const text = value.trim();
  if (/^data:image\/(png|jpe?g|webp|gif);base64,/i.test(text)) return text;
  if (/^\/\//.test(text)) return `https:${text}`;
  if (/^https?:\/\//i.test(text) || /^\/(?!\/)/.test(text)) return text;
  if (/^[A-Za-z0-9+/\r\n]+={0,2}$/.test(text) && text.length > 80)
    return `data:image/jpeg;base64,${text}`;
  return '';
}
export function boundingBox(value?: string | null) {
  try {
    const v: unknown = JSON.parse(value || 'null');
    if (
      !Array.isArray(v) ||
      v.length !== 4 ||
      !v.every(
        (n) => typeof n === 'number' && Number.isFinite(n) && n >= 0 && n <= 1,
      )
    )
      return null;
    const [x, y, w, h] = v as number[];
    if (!w || !h) return null;
    const left = Math.max(0, x - w / 2),
      top = Math.max(0, y - h / 2);
    return {
      left: left * 100,
      top: top * 100,
      width: (Math.min(1, x + w / 2) - left) * 100,
      height: (Math.min(1, y + h / 2) - top) * 100,
    };
  } catch {
    return null;
  }
}
export function percentage(n: number) {
  return Math.max(0, Math.min(100, n <= 1 ? n * 100 : n));
}
export function dateLabel(value: string, time = false) {
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return 'Chưa có thông tin';
  return new Intl.DateTimeFormat('vi-VN', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    ...(time ? { hour: '2-digit', minute: '2-digit' } : {}),
  }).format(d);
}
export function csvCell(value: unknown) {
  let s = String(value ?? '');
  if (/^[\s]*[=+@-]/.test(s)) s = `'${s}`;
  return `"${s.replace(/"/g, '""')}"`;
}
export function errorMessage(error: unknown) {
  return error instanceof Error
    ? error.message
    : 'Có lỗi xảy ra. Vui lòng thử lại.';
}
export function distanceMeters(
  a: { latitude: number; longitude: number },
  b: { latitude: number; longitude: number },
) {
  const rad = Math.PI / 180;
  const dy = (b.latitude - a.latitude) * rad;
  const dx = (b.longitude - a.longitude) * rad;
  const h =
    Math.sin(dy / 2) ** 2 +
    Math.cos(a.latitude * rad) *
      Math.cos(b.latitude * rad) *
      Math.sin(dx / 2) ** 2;
  return 6371000 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1 - h)));
}
export class SessionExpiredError extends Error {
  constructor() {
    super('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');
  }
}
