import type { Location, NearbySign, ReviewStatus } from './reviewTypes.ts';

export type MapBounds = [
  west: number,
  south: number,
  east: number,
  north: number,
];
export interface MapSign extends NearbySign {
  status: ReviewStatus;
  headingDeg?: number | null;
  signName?: string;
}
export interface MapCluster {
  location: Location;
  count: number;
}
export type MapResult =
  | { mode: 'SIGNS'; items: MapSign[] }
  | { mode: 'CLUSTERS'; items: MapCluster[] };
export interface MapSignType {
  code: string;
  name: string;
}
export interface MapSignInput {
  signTypeCode: string;
  location: Location;
  headingDeg: number | null;
}
export type MapSignPatch = Partial<MapSignInput>;

export function isPublished(status: ReviewStatus) {
  return status === 'APPROVED' || status === 'UNDER_REVIEW';
}

export function validateSignChanges(input: MapSignPatch): MapSignPatch {
  const changes = { ...input };
  if (changes.signTypeCode !== undefined) {
    changes.signTypeCode = changes.signTypeCode.trim();
    if (!changes.signTypeCode || changes.signTypeCode.length > 30)
      throw new Error('Nhập mã biển dài từ 1 đến 30 ký tự.');
  }
  const { location, headingDeg } = changes;
  if (
    location &&
    (!Number.isFinite(location.lat) ||
      !Number.isFinite(location.lng) ||
      Math.abs(location.lat) > 90 ||
      Math.abs(location.lng) > 180)
  )
    throw new Error('Tọa độ không hợp lệ.');
  if (
    headingDeg != null &&
    (!Number.isInteger(headingDeg) || headingDeg < 0 || headingDeg > 359)
  )
    throw new Error('Hướng phải là số nguyên từ 0 đến 359 độ.');
  if (
    changes.signTypeCode === undefined &&
    location === undefined &&
    headingDeg === undefined
  )
    throw new Error('Vui lòng thay đổi mã, vị trí hoặc hướng của biển.');
  return changes;
}

export function validateSignVersion(version: number) {
  if (!Number.isInteger(version) || version < 0)
    throw new Error('Thiếu phiên bản của biển. Vui lòng tải lại.');
}

export function normalizeRemovalReason(reason: string) {
  const value = reason.trim();
  if (!value || value.length > 1000)
    throw new Error('Nhập lý do gỡ biển dài từ 1 đến 1.000 ký tự.');
  return value;
}
