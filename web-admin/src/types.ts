import type { ReportReason, SignReport } from './reportTypes';
import type { CatalogCategory, CatalogSignType } from './catalogTypes';
import type { ManagedUser } from './accountTypes';
import type { AuditLog } from './auditTypes';
import type { MapSign } from './mapTypes';
import type { ReviewDetail } from './reviewTypes';

export type PageKey =
  | 'overview'
  | 'signs'
  | 'types'
  | 'map'
  | 'pending'
  | 'reports'
  | 'audit'
  | 'users'
  | 'accounts'
  | 'compare'
  | 'stats';
export type Status = 'PENDING' | 'APPROVAL' | 'REJECTED' | 'DELETED';
export interface Admin {
  id?: string;
  username: string;
  fullName: string;
  email: string;
  role: string;
  sessionId: string;
  expiresAt?: string;
  permissions?: string[];
}
export interface SignType {
  id: string;
  name: string;
  description: string;
}
export interface Sign {
  id: string;
  code: string;
  name: string;
  typeId: string;
  description: string;
  photoUrl: string;
  createdAt: string;
  updatedAt?: string | null;
}
export interface Upload {
  id: string;
  trafficSignId: string;
  trafficSignName: string;
  fullAddress: string;
  latitude: number;
  longitude: number;
  photoBase64: string;
  bbox: string | null;
  accuracyAI: number;
  createdAt: string;
  createdBy: string;
  status: Status;
  numberDuplicate: number;
  decision?: { status: 'APPROVED' | 'REJECTED'; at: string };
}
export interface Report extends Upload {
  rttsId: string;
  custId: string;
  reason: string;
  reasonCode?: ReportReason;
  resolution?: SignReport['resolution'];
  resolutionNote?: string;
}
export interface Contributor {
  custId: string;
  pending: number;
  approval: number;
  deleted: number;
  rejected: number;
}
export interface UserDetail {
  id: string;
  username: string;
  fullName: string;
  email?: string;
  createdAt: string;
  pending: number;
  approval: number;
  deleted: number;
  rejected: number;
}
export interface Page<T> {
  rows: T[];
  total: number;
  pages: number;
}
export interface Filters {
  query: string;
  typeId: string;
  page: number;
  size: number;
  sort: string;
}
export interface DemoDB {
  version: 1;
  signs: Sign[];
  uploads: Upload[];
  reports: Report[];
  reviewSigns?: ReviewDetail[];
  auditLogs?: AuditLog[];
  managedUsers?: ManagedUser[];
  catalogCategories?: CatalogCategory[];
  catalogTypes?: CatalogSignType[];
  mapAudit?: {
    signId: string;
    action: 'CREATED' | 'EDITED' | 'REMOVED';
    actorId: string;
    createdAt: string;
    before: MapSign | null;
    after: MapSign;
    reason?: string;
  }[];
}
