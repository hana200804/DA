import md5 from 'crypto-js/md5';
import type {
  Admin,
  Contributor,
  Filters,
  Page,
  Report,
  Sign,
  SignType,
  Upload,
  UserDetail,
} from './types';
import { SessionExpiredError } from './domain';
import {
  decideReport,
  decideUpload,
  matches,
  nearbyApproved,
  paginate,
  readDemo,
  signTypes,
  stats,
  userInfo,
  writeDemo,
} from './demo';

import { demoAdminEmail, demoAdminId } from './accountTypes.ts';
import { accountsDemo } from './accountsDemo.ts';
import { catalogDemo } from './catalogDemo.ts';
import { compareDemo } from './compareDemo.ts';
import { createReviewApi } from './reviewApi.ts';
import { reviewDemo } from './reviewDemo.ts';

export const isDemo = import.meta.env.VITE_DATA_MODE !== 'api';
export const isSigna =
  !isDemo && import.meta.env.VITE_API_CONTRACT !== 'legacy';
export const isLegacy = !isDemo && !isSigna;
const signa = createReviewApi();
export const signaSessionApi = signa;
export const comparisonApi = isDemo ? compareDemo : signa;
export const accountsApi = isDemo ? accountsDemo : signa;
export const catalogApi = isDemo ? catalogDemo : signa;
export const reviewApi = isDemo ? reviewDemo : signa;
const bases = {
  auth: import.meta.env.VITE_AUTH_BASE || '/api/auth',
  signs: import.meta.env.VITE_SIGN_BASE || '/api/signs',
  rtts: import.meta.env.VITE_RTTS_BASE || '/api/rtts',
};
type Service = keyof typeof bases;
interface Envelope<T> {
  result: { ok: boolean; message: string; responseCode: string };
  data: T;
}
export async function request<T>(
  service: Service,
  path: string,
  body: object,
): Promise<T> {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 20000);
  try {
    const response = await fetch(`${bases[service]}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    if (response.status === 401 || response.status === 403)
      throw new SessionExpiredError();
    if (!response.ok)
      throw new Error(
        `Máy chủ trả về lỗi ${response.status}. Vui lòng thử lại.`,
      );
    let payload: Envelope<T>;
    try {
      payload = await response.json();
    } catch {
      throw new Error(
        'Máy chủ trả về dữ liệu không hợp lệ. Kiểm tra cấu hình API.',
      );
    }
    if (payload.result?.ok !== true) {
      const message = payload.result?.message || '';
      if (
        /session.*(invalid|expir|not found)|invalid.*session|phiên.*hết hạn/i.test(
          message,
        )
      )
        throw new SessionExpiredError();
      throw new Error(message || 'Yêu cầu chưa được xử lý.');
    }
    return payload.data;
  } catch (error) {
    if (error instanceof TypeError)
      throw new Error(
        'Không thể kết nối backend. Kiểm tra máy chủ và cấu hình API.',
      );
    if (error instanceof DOMException && error.name === 'AbortError')
      throw new Error('Máy chủ phản hồi quá lâu. Vui lòng thử lại.');
    throw error;
  } finally {
    clearTimeout(timeout);
  }
}
type RawPage<T> = { data: T[]; totalRecords: number; totalPages: number };
function pageResult<T>(rows: T[], total: number, pages: number): Page<T> {
  return {
    rows: rows || [],
    total: total || 0,
    pages: Math.max(1, pages || 1),
  };
}
async function fetchRttsPage<T>(
  path: string,
  sessionId: string,
  page: number,
  size: number,
  filters?: object,
): Promise<Page<T>> {
  const { data, totalRecords, totalPages } = await request<RawPage<T>>(
    'rtts',
    path,
    { sessionId, pageNumber: page, pageSize: size, ...filters },
  );
  return pageResult(data, totalRecords, totalPages);
}
function sorted<T extends { createdAt: string }>(rows: T[], sort: string) {
  return [...rows].sort((a, b) =>
    sort === 'oldest'
      ? a.createdAt.localeCompare(b.createdAt)
      : b.createdAt.localeCompare(a.createdAt),
  );
}

export const api = {
  async login(username: string, password: string): Promise<Admin> {
    if (isDemo)
      return {
        id: demoAdminId,
        username: demoAdminId,
        fullName: 'Quản trị viên',
        email: demoAdminEmail,
        role: 'ADMIN',
        sessionId: 'demo-session',
      };
    if (isSigna) return signa.login(username, password);
    return request('auth', '/authen/v1.0/do-login-admin', {
      username,
      password: md5(password).toString(),
    });
  },
  async logout(sessionId: string) {
    if (isSigna) return signa.logout(sessionId);
    if (!isDemo) await request('auth', '/authen/v1.0/do-logout', { sessionId });
  },
  async types(sessionId: string): Promise<SignType[]> {
    if (isDemo) return signTypes;
    const { data } = await request<{ data: SignType[] }>(
      'signs',
      '/sign-type/v1.0/get-sign-types-list',
      { sessionId },
    );
    return data || [];
  },
  async signs(sessionId: string, f: Filters): Promise<Page<Sign>> {
    if (isDemo)
      return paginate(
        sorted(
          readDemo().signs.filter(
            (s) =>
              (!f.typeId || s.typeId === f.typeId) &&
              matches(f.query, s.name, s.code),
          ),
          f.sort,
        ),
        f.page,
        f.size,
      );
    const r = await request<{
      productList: Sign[];
      totalProducts: number;
      totalPages: number;
    }>('signs', '/traffic-sign/v1.0/get-traffic-sign-by-page', {
      sessionId,
      pageNumber: f.page,
      pageSize: f.size,
      type: f.typeId ? 'BY_TYPE' : 'ALL',
      signTypeId: f.typeId || undefined,
      name: f.query || undefined,
    });
    return pageResult(r.productList, r.totalProducts, r.totalPages);
  },
  async editSign(sessionId: string, id: string, name: string) {
    if (isDemo) {
      const db = readDemo();
      const sign = db.signs.find((s) => s.id === id);
      if (!sign) throw new Error('Không tìm thấy biển báo.');
      sign.name = name.trim();
      sign.updatedAt = new Date().toISOString();
      db.uploads
        .filter((u) => u.trafficSignId === sign.code)
        .forEach((u) => (u.trafficSignName = sign.name));
      writeDemo(db);
      return;
    }
    await request('signs', '/traffic-sign/v1.0/update-traffic-sign', {
      sessionId,
      id,
      name: name.trim(),
    });
  },
  async pending(sessionId: string, f: Filters): Promise<Page<Upload>> {
    if (isDemo) {
      const db = readDemo();
      return paginate(
        sorted(
          db.uploads
            .filter(
              (u) => u.status === 'PENDING' && matches(f.query, u.fullAddress),
            )
            .map((u) => ({
              ...u,
              numberDuplicate: nearbyApproved(db.uploads, u).length,
            })),
          f.sort,
        ),
        f.page,
        f.size,
      );
    }
    return fetchRttsPage<Upload>(
      '/rtts/v1.0/get-list-rtts-not-verify',
      sessionId,
      f.page,
      f.size,
      {
        searchText: f.query || undefined,
        sortBy: 'created_at',
        sortDir: f.sort === 'oldest' ? 'asc' : 'desc',
      },
    );
  },
  async duplicates(sessionId: string, row: Upload): Promise<Upload[]> {
    if (isDemo) return nearbyApproved(readDemo().uploads, row);
    const { data } = await request<{ data: Upload[] }>(
      'rtts',
      '/rtts/v1.0/get-list-rtts-verified',
      { sessionId, rttsId: row.id },
    );
    return data || [];
  },
  async decide(
    sessionId: string,
    kind: 'pending' | 'reports',
    id: string,
    decision: 'APPROVE' | 'REJECT',
  ) {
    if (isDemo) {
      const db = readDemo();
      (kind === 'reports' ? decideReport : decideUpload)(db, id, decision);
      writeDemo(db);
      return;
    }
    await request(
      'rtts',
      `/rtts/v1.0/${kind === 'reports' ? 'approve-report-rtts' : 'approve-rtts'}`,
      { sessionId, rttsId: id, decision },
    );
  },
  async reports(sessionId: string, f: Filters): Promise<Page<Report>> {
    if (isDemo)
      return paginate(
        sorted(
          readDemo().reports.filter((r) => r.status === 'PENDING'),
          'newest',
        ),
        f.page,
        f.size,
      );
    return fetchRttsPage<Report>(
      '/rtts/v1.0/get-list-report-rtts',
      sessionId,
      f.page,
      f.size,
    );
  },
  async users(sessionId: string, f: Filters): Promise<Page<Contributor>> {
    if (isDemo) {
      const rows = stats(readDemo()).filter((u) => matches(f.query, u.custId));
      rows.sort((a, b) =>
        f.sort === 'pending' ? b.pending - a.pending : b.approval - a.approval,
      );
      return paginate(rows, f.page, f.size);
    }
    return fetchRttsPage<Contributor>(
      '/rtts/v1.0/get-list-user-upload-rtts',
      sessionId,
      f.page,
      f.size,
      {
        username: f.query || undefined,
        sortBy: f.sort === 'pending' ? 'PENDING' : 'APPROVAL',
        sortDir: 'DESC',
      },
    );
  },
  async user(sessionId: string, username: string): Promise<UserDetail> {
    if (isDemo) return userInfo(readDemo(), username);
    return request('auth', '/user/v1.0/get-cust-info', { sessionId, username });
  },
  async history(
    sessionId: string,
    username: string,
    page: number,
  ): Promise<Page<Upload>> {
    if (isDemo)
      return paginate(
        sorted(
          readDemo().uploads.filter((u) => u.createdBy === username),
          'newest',
        ),
        page,
        6,
      );
    return fetchRttsPage<Upload>(
      '/rtts/v1.0/get-cust-upload-his',
      sessionId,
      page,
      6,
      { username },
    );
  },
  async remove(sessionId: string, id: string) {
    if (isDemo) {
      const db = readDemo();
      const row = db.uploads.find((u) => u.id === id);
      if (!row || row.status === 'DELETED')
        throw new Error('Biển báo đã bị xóa hoặc không tồn tại.');
      row.status = 'DELETED';
      writeDemo(db);
      return;
    }
    await request('rtts', '/rtts/v1.0/delete-rtts', { sessionId, rttsId: id });
  },
};
