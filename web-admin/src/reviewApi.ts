import {
  validateAccountPatch,
  type AccountFilters,
  type AccountPatch,
  type ManagedUser,
} from './accountTypes.ts';
import { auditQuery, type AuditFilters, type AuditLog } from './auditTypes.ts';
import {
  normalizeCategory,
  normalizeCategoryCode,
  normalizeSignType,
  normalizeSignTypeCode,
  validateCatalogIcon,
  type CategoryInput,
  type CatalogCategory,
  type CatalogSignType,
  type SignTypeInput,
} from './catalogTypes.ts';
import {
  modelNames,
  validateComparisonImage,
  type Comparison,
  type Evaluation,
  type ModelName,
} from './compareTypes.ts';
import { SessionExpiredError } from './domain.ts';
import {
  normalizeRemovalReason,
  validateSignChanges,
  validateSignVersion,
  type MapBounds,
  type MapResult,
  type MapSignInput,
  type MapSignPatch,
  type MapSignType,
} from './mapTypes.ts';
import {
  statisticsRange,
  type Statistics,
  type StatisticsFilters,
} from './statisticsTypes.ts';
import {
  canEnterSigna,
  isCurrentSignaSession,
  type SignaIdentity,
  type SignaSession,
} from './signaAccess.ts';
import type { Admin, Page } from './types';
import {
  normalizeReportDecision,
  type ReportDecision,
  type ReportFilters,
  type ReportGroup,
} from './reportTypes.ts';
import {
  isReviewable,
  normalizeReviewReason,
  type ReviewDecision,
  type ReviewDetail,
  type ReviewFilters,
  type ReviewSign,
} from './reviewTypes.ts';

export class ReviewApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

interface ModelCardMetrics {
  map_50?: number | null;
  map_50_95?: number | null;
  operating_point?: {
    precision?: number | null;
    recall?: number | null;
  } | null;
}

interface LoginResponse {
  token: string;
  expiresAt: string;
  user: SignaIdentity;
}

function signaAdmin(session: SignaSession, user: SignaIdentity): Admin {
  if (!isCurrentSignaSession(session)) throw new SessionExpiredError();
  if (!canEnterSigna(user))
    throw new ReviewApiError(
      403,
      'FORBIDDEN',
      'Chỉ tài khoản kiểm duyệt viên hoặc quản trị viên có quyền kiểm duyệt mới được vào VisionMap.',
    );
  if (
    typeof user.id !== 'string' ||
    !user.id.trim() ||
    typeof user.displayName !== 'string'
  )
    throw new Error('Backend Signa chưa trả về thông tin tài khoản hợp lệ.');
  return {
    ...session,
    id: user.id,
    username: session.email,
    fullName: user.displayName,
    role: user.role,
    permissions: [...user.permissions],
  };
}

export function createReviewApi(base = '/api/v1') {
  async function request<T>(
    path: string,
    token?: string,
    method = 'GET',
    body?: object,
    readJson = method === 'GET' || path === '/auth/login',
  ): Promise<T> {
    let response: Response;
    try {
      response = await fetch(base + path, {
        method,
        headers: {
          ...(token ? { Authorization: 'Bearer ' + token } : {}),
          ...(body && !(body instanceof FormData)
            ? { 'Content-Type': 'application/json' }
            : {}),
        },
        body:
          body instanceof FormData
            ? body
            : body
              ? JSON.stringify(body)
              : undefined,
        signal: AbortSignal.timeout(20_000),
      });
    } catch (error) {
      if (error instanceof DOMException && error.name === 'TimeoutError')
        throw new Error('Máy chủ phản hồi quá lâu. Vui lòng thử lại.');
      throw new Error('Không thể kết nối backend Signa.');
    }
    if (response.status === 401 && token) throw new SessionExpiredError();
    if (response.ok && !readJson) return undefined as T;
    const payload = await response.json().catch(() => null);
    if (!response.ok) {
      throw new ReviewApiError(
        response.status,
        payload?.code ?? '',
        payload?.message ?? 'Máy chủ trả về lỗi ' + response.status + '.',
      );
    }
    if (payload === null)
      throw new Error('Backend Signa trả về dữ liệu không hợp lệ.');
    return payload as T;
  }

  function detail(token: string, id: string) {
    return request<ReviewDetail>(
      '/admin/signs/' + encodeURIComponent(id),
      token,
    );
  }

  return {
    async login(email: string, password: string): Promise<Admin> {
      email = email.trim().toLowerCase();
      const { token, expiresAt, user } = await request<LoginResponse>(
        '/auth/login',
        undefined,
        'POST',
        { email, password, client: 'WEB' },
      );
      return signaAdmin({ sessionId: token, expiresAt, email }, user);
    },
    async restoreSession(session: SignaSession) {
      if (!isCurrentSignaSession(session)) throw new SessionExpiredError();
      const response = await request<SignaIdentity | { user: SignaIdentity }>(
        '/me',
        session.sessionId,
      );
      const user =
        typeof response === 'object' && 'user' in response
          ? response.user
          : response;
      return signaAdmin(session, user);
    },
    logout(token: string) {
      return request<void>('/auth/logout', token, 'POST');
    },
    async queue(
      token: string,
      filters: ReviewFilters,
    ): Promise<Page<ReviewSign>> {
      const params = new URLSearchParams({
        page: String(filters.page - 1),
        size: String(filters.size),
      });
      if (filters.suggestion) params.set('suggestion', filters.suggestion);
      if (filters.status) params.set('status', filters.status);
      const result = await request<{ items: ReviewSign[]; total: number }>(
        '/admin/review-queue?' + params,
        token,
      );
      return {
        rows: result.items,
        total: result.total,
        pages: Math.max(1, Math.ceil(result.total / filters.size)),
      };
    },
    detail,
    mapSigns(_token: string, bounds: MapBounds, zoom: number) {
      const query = new URLSearchParams({
        bbox: bounds.join(','),
        zoom: String(zoom),
      });
      return request<MapResult>('/signs?' + query);
    },
    async mapSignTypes() {
      const result = await request<{ items: MapSignType[] }>('/sign-types');
      return result.items;
    },
    async catalogCategories() {
      const result = await request<
        CatalogCategory[] | { items: CatalogCategory[] }
      >('/sign-categories');
      return (Array.isArray(result) ? result : result.items)
        .slice()
        .sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));
    },
    async catalogTypes(adminToken?: string) {
      const path = adminToken ? '/admin/sign-types' : '/sign-types';
      const result = await request<{ items: CatalogSignType[] }>(
        path,
        adminToken,
      );
      return result.items.map((row) => ({
        ...row,
        active: row.active ?? true,
      }));
    },
    async saveCategory(
      token: string,
      code: string,
      input: CategoryInput,
      create: boolean,
    ) {
      const key = normalizeCategoryCode(code);
      const values = normalizeCategory(input);
      const path = '/admin/sign-categories';
      await request<void>(
        create ? path : path + '/' + encodeURIComponent(key),
        token,
        create ? 'POST' : 'PATCH',
        create ? { code: key, ...values } : values,
      );
    },
    async saveSignType(
      token: string,
      code: string,
      input: SignTypeInput,
      create: boolean,
    ) {
      const key = normalizeSignTypeCode(code);
      const values = normalizeSignType(input);
      const path = '/admin/sign-types';
      await request<void>(
        create ? path : path + '/' + encodeURIComponent(key),
        token,
        create ? 'POST' : 'PATCH',
        create ? { code: key, recognizable: false, ...values } : values,
      );
    },
    async uploadCatalogIcon(token: string, code: string, file: File) {
      validateCatalogIcon(file);
      const data = new FormData();
      data.append('icon', file);
      await request<void>(
        '/admin/sign-types/' +
          encodeURIComponent(normalizeSignTypeCode(code)) +
          '/icon',
        token,
        'PUT',
        data,
      );
    },
    async createMapSign(token: string, input: MapSignInput) {
      await request<void>(
        '/admin/signs',
        token,
        'POST',
        validateSignChanges(input),
      );
    },
    async patchMapSign(
      token: string,
      id: string,
      version: number,
      input: MapSignPatch,
    ) {
      validateSignVersion(version);
      const changes = validateSignChanges(input);
      await request<void>(
        '/admin/signs/' + encodeURIComponent(id),
        token,
        'PATCH',
        { version, ...changes },
      );
    },
    async removeMapSign(
      token: string,
      id: string,
      version: number,
      reason: string,
    ) {
      validateSignVersion(version);
      await request<void>(
        '/admin/signs/' + encodeURIComponent(id) + '/remove',
        token,
        'POST',
        {
          version,
          reason: normalizeRemovalReason(reason),
        },
      );
    },
    async compareImage(token: string, file: File): Promise<Comparison> {
      validateComparisonImage(file);
      const body = new FormData();
      body.append('image', file);
      const data = await request<Comparison>(
        '/admin/recognition/compare',
        token,
        'POST',
        body,
        true,
      );
      if (!Array.isArray(data.results))
        throw new Error('Backend Signa chưa trả về danh sách kết quả so sánh.');
      return data;
    },
    async statistics(
      token: string,
      filters: StatisticsFilters,
    ): Promise<Statistics> {
      const { from, to } = statisticsRange(filters);
      const params = new URLSearchParams();
      if (from) params.set('from', from);
      if (to) params.set('to', to);
      const path = '/admin/stats' + (params.size ? '?' + params : '');
      const data = await request<Statistics>(path, token);
      if (
        !Array.isArray(data.submissionsByDay) ||
        !data.decisions ||
        !data.processingTime ||
        !Array.isArray(data.signsByCategory) ||
        !Array.isArray(data.densityCells)
      )
        throw new Error(
          'Backend Signa chưa trả về dữ liệu thống kê theo định dạng dự kiến.',
        );
      return data;
    },
    async modelEvaluations(): Promise<Evaluation[]> {
      const data = await request<{
        models: {
          name?: ModelName;
          modelName?: ModelName;
          version?: string;
          modelVersion?: string;
          modelCard?: {
            datasetVersion?: string;
            evaluations?: Omit<Evaluation, 'model' | 'version'>[];
            val?: ModelCardMetrics | null;
            test?: ModelCardMetrics | null;
          };
        }[];
      }>('/recognition/models');
      if (!Array.isArray(data.models))
        throw new Error('Backend Signa chưa trả về danh sách mô hình.');
      return data.models.flatMap(
        ({ name, modelName, version, modelVersion, modelCard }) => {
          const model = modelName ?? name;
          const release = modelVersion ?? version;
          if (!model || !modelNames.includes(model) || !release || !modelCard)
            return [];

          const rows: Evaluation[] = Array.isArray(modelCard.evaluations)
            ? modelCard.evaluations.map((row) => ({
                ...row,
                model,
                version: release,
                datasetVersion: row.datasetVersion ?? modelCard.datasetVersion,
              }))
            : [];

          for (const split of ['val', 'test'] as const) {
            const metrics = modelCard[split];
            if (!metrics || rows.some((row) => row.split === split)) continue;

            const {
              map_50: map50,
              map_50_95: map50To95,
              operating_point,
            } = metrics;
            const { precision, recall } = operating_point ?? {};
            if (![map50, map50To95, precision, recall].some(Number.isFinite))
              continue;

            rows.push({
              model,
              version: release,
              datasetVersion: modelCard.datasetVersion,
              split,
              map50,
              map50To95,
              precision,
              recall,
            });
          }

          return rows;
        },
      );
    },
    async managedUsers(
      token: string,
      filters: AccountFilters,
    ): Promise<Page<ManagedUser>> {
      const params = new URLSearchParams({
        page: String(filters.page - 1),
        size: String(filters.size),
      });
      const search = filters.query?.trim().toLowerCase();
      if (search) params.set('q', search);
      if (filters.role) params.set('role', filters.role);
      if (filters.status) params.set('status', filters.status);
      const result = await request<{ items: ManagedUser[]; total: number }>(
        '/admin/users?' + params,
        token,
      );
      return {
        rows: result.items,
        total: result.total,
        pages: Math.max(1, Math.ceil(result.total / filters.size)),
      };
    },
    async updateManagedUser(token: string, id: string, patch: AccountPatch) {
      await request<void>(
        '/admin/users/' + encodeURIComponent(id),
        token,
        'PATCH',
        validateAccountPatch(patch),
      );
    },
    async auditLogs(
      token: string,
      filters: AuditFilters,
    ): Promise<Page<AuditLog>> {
      const params = new URLSearchParams({
        page: String(filters.page - 1),
        size: String(filters.size),
      });
      for (const [name, value] of Object.entries(auditQuery(filters))) {
        if (value) params.set(name, value);
      }
      const result = await request<{ items: AuditLog[]; total: number }>(
        '/admin/audit-logs?' + params,
        token,
      );
      return {
        rows: result.items,
        total: result.total,
        pages: Math.max(1, Math.ceil(result.total / filters.size)),
      };
    },
    async reportQueue(
      token: string,
      filters: ReportFilters,
    ): Promise<Page<ReportGroup>> {
      const params = new URLSearchParams({
        page: String(filters.page - 1),
        size: String(filters.size),
        status: filters.status,
      });
      if (filters.reason) params.set('reason', filters.reason);
      const result = await request<{ items: ReportGroup[]; total: number }>(
        '/admin/reports?' + params,
        token,
      );
      return {
        rows: result.items,
        total: result.total,
        pages: Math.max(1, Math.ceil(result.total / filters.size)),
      };
    },
    async resolveReport(token: string, id: string, decision: ReportDecision) {
      const { action, ...payload } = normalizeReportDecision(decision);
      const path = '/admin/reports/' + encodeURIComponent(id);
      if (action === 'DISMISS') {
        await request<void>(path + '/dismiss', token, 'POST', payload);
      } else {
        await request<void>(path + '/accept', token, 'POST', {
          action,
          ...payload,
        });
      }
    },
    async decide(token: string, sign: ReviewDetail, decision: ReviewDecision) {
      if (!isReviewable(sign.status))
        throw new ReviewApiError(
          409,
          'SIGN_ALREADY_PROCESSED',
          'Biển đã được xử lý.',
        );
      const pendingAction =
        decision.action === 'approve' || decision.action === 'reject';
      if (pendingAction !== (sign.status === 'PENDING'))
        throw new Error('Quyết định không phù hợp với trạng thái của biển.');
      const path = '/admin/signs/' + encodeURIComponent(sign.id);
      const { version } = sign;
      switch (decision.action) {
        case 'approve':
          await request(path + '/approve', token, 'POST', {
            version,
            signTypeCode: decision.signTypeCode,
            location: decision.location,
          });
          break;
        case 'edit':
          await request(path + '/keep', token, 'POST', {
            version,
            signTypeCode: decision.signTypeCode,
            location: decision.location,
          });
          break;
        case 'keep':
          await request(path + '/keep', token, 'POST', { version });
          break;
        case 'reject':
        case 'remove': {
          const reason = normalizeReviewReason(decision.reason);
          await request(path + '/' + decision.action, token, 'POST', {
            version,
            reason,
          });
          break;
        }
      }
    },
  };
}
