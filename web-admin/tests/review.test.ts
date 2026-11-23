import assert from 'node:assert/strict';
import test from 'node:test';
import { createReviewApi, ReviewApiError } from '../src/reviewApi.ts';
import {
  canEnterSigna,
  canOpenSignaPage,
  hasSignaPermission,
  isCurrentSignaSession,
} from '../src/signaAccess.ts';
import { accountsDemo } from '../src/accountsDemo.ts';
import { demoAdminId, isOwnAccount } from '../src/accountTypes.ts';
import { boxPercent } from '../src/compareTypes.ts';
import { demoStatistics } from '../src/statisticsDemo.ts';
import { dailyChartRows, decisionRates } from '../src/statisticsTypes.ts';
import { compareDemo } from '../src/compareDemo.ts';
import { catalogDemo } from '../src/catalogDemo.ts';
import { reviewDemo } from '../src/reviewDemo.ts';
import { SessionExpiredError } from '../src/domain.ts';
import { auditQuery, formatAuditTime } from '../src/auditTypes.ts';
import type { MapSign } from '../src/mapTypes.ts';
import { readDemo, writeDemo } from '../src/demo.ts';
import { mapView } from '../src/mapGeometry.ts';
import type { ReviewDetail } from '../src/reviewTypes.ts';

const api = createReviewApi();
const pending: ReviewDetail = {
  id: 'sign-1',
  status: 'PENDING',
  signTypeCode: 'P.127_60',
  location: { lat: 21.03, lng: 105.8 },
  confidence: 0.82,
  suggestion: 'APPROVE',
  version: 7,
  submissions: [
    {
      id: 'obs-1',
      signTypeCode: 'P.127_60',
      confidence: 0.9,
      cropUrl: 'https://example.com/one.jpg',
    },
    {
      id: 'obs-2',
      signTypeCode: 'P.127_50',
      confidence: 0.6,
      cropUrl: 'https://example.com/two.jpg',
    },
  ],
  codeWeights: { 'P.127_60': 0.9, 'P.127_50': 0.6 },
  nearbySigns: [],
};
const underReview: ReviewDetail = {
  ...pending,
  status: 'UNDER_REVIEW',
  suggestion: 'INSPECT',
};
type Call = {
  path: string;
  method: string;
  auth: string | null;
  body: unknown;
};

async function mockFetch(
  responses: Response[],
  run: (calls: Call[]) => Promise<void>,
) {
  const oldFetch = globalThis.fetch;
  const calls: Call[] = [];
  globalThis.fetch = (async (path, request) => {
    calls.push({
      path: String(path),
      method: request?.method ?? 'GET',
      auth: new Headers(request?.headers).get('Authorization'),
      body:
        request?.body instanceof FormData
          ? request.body
          : request?.body
            ? JSON.parse(String(request.body))
            : undefined,
    });
    const response = responses.shift();
    assert.ok(response, 'Unexpected request to ' + path);
    return response;
  }) as typeof fetch;
  try {
    await run(calls);
    assert.equal(responses.length, 0);
  } finally {
    globalThis.fetch = oldFetch;
  }
}

test('đăng nhập web lấy Bearer token và kiểm tra quyền duyệt', async () => {
  const user = {
    id: 'moderator-1',
    role: 'MODERATOR',
    displayName: 'Kiểm duyệt viên',
    permissions: ['review:decide'],
  };
  const login = { token: 'token', expiresAt: '2099-11-21T00:00:00Z', user };
  await mockFetch(
    [
      Response.json(login),
      Response.json({ ...login, user: { ...user, permissions: [] } }),
    ],
    async (calls) => {
      const admin = await api.login('reviewer@example.com', 'password');
      assert.equal(admin.sessionId, 'token');
      assert.equal(admin.id, 'moderator-1');
      assert.deepEqual(calls[0], {
        path: '/api/v1/auth/login',
        method: 'POST',
        auth: null,
        body: {
          email: 'reviewer@example.com',
          password: 'password',
          client: 'WEB',
        },
      });
      await assert.rejects(api.login('reviewer@example.com', 'password'), {
        code: 'FORBIDDEN',
      });
    },
  );
});

test('hàng đợi gửi trang từ 0 và chi tiết giữ ảnh của từng đề xuất', async () => {
  await mockFetch(
    [Response.json({ items: [pending], total: 13 }), Response.json(pending)],
    async (calls) => {
      const queue = await api.queue('token', {
        page: 2,
        size: 7,
        status: 'PENDING',
        suggestion: 'APPROVE',
      });
      assert.equal(queue.pages, 2);
      assert.equal(queue.rows[0].id, 'sign-1');
      assert.deepEqual(calls[0], {
        path: '/api/v1/admin/review-queue?page=1&size=7&suggestion=APPROVE&status=PENDING',
        auth: 'Bearer token',
        method: 'GET',
        body: undefined,
      });
      const detail = await api.detail('token', pending.id);
      assert.equal(calls[1].path, '/api/v1/admin/signs/sign-1');
      assert.equal(detail.version, 7);
      assert.deepEqual(detail.submissions, pending.submissions);
    },
  );
});

test('duyệt và sửa rồi duyệt gửi đúng phiên bản và phần chỉnh sửa', async () => {
  const location = { lat: 21.04, lng: 105.81 };
  await mockFetch(
    [new Response(null, { status: 200 }), new Response(null, { status: 204 })],
    async (calls) => {
      await api.decide('token', pending, { action: 'approve' });
      await api.decide('token', pending, {
        action: 'approve',
        signTypeCode: 'P.127_50',
        location,
      });
      assert.deepEqual(calls[0], {
        path: '/api/v1/admin/signs/sign-1/approve',
        method: 'POST',
        auth: 'Bearer token',
        body: { version: 7 },
      });
      assert.deepEqual(calls[1].body, {
        version: 7,
        signTypeCode: 'P.127_50',
        location,
      });
    },
  );
});

test('FR-31 lý do từ chối và gỡ biển phải có từ 1 đến 1.000 ký tự', async () => {
  const cases = [
    { action: 'reject', sign: pending },
    { action: 'remove', sign: underReview },
  ] as const;
  const validReasons = ['x', 'x'.repeat(1_000)];
  await mockFetch(
    Array.from({ length: 4 }, () => new Response(null, { status: 204 })),
    async (calls) => {
      for (const { action, sign } of cases) {
        for (const reason of ['', '   ', 'x'.repeat(1_001)]) {
          await assert.rejects(
            api.decide('token', sign, { action, reason }),
            /1 đến 1\.000 ký tự/,
          );
        }
      }
      assert.equal(calls.length, 0);

      for (const { action, sign } of cases) {
        for (const reason of validReasons)
          await api.decide('token', sign, {
            action,
            reason: ' ' + reason + ' ',
          });
      }
      assert.deepEqual(
        calls,
        cases.flatMap(({ action }) =>
          validReasons.map((reason) => ({
            path: '/api/v1/admin/signs/sign-1/' + action,
            method: 'POST',
            auth: 'Bearer token',
            body: { version: 7, reason },
          })),
        ),
      );
    },
  );
});

test('FR-31 sửa và giữ biển đang rà soát bằng một POST /keep', async () => {
  const location = { lat: 21.03, lng: 105.81 };
  await mockFetch(
    [new Response(null, { status: 204 }), new Response(null, { status: 204 })],
    async (calls) => {
      await api.decide('token', underReview, {
        action: 'edit',
        signTypeCode: 'P.127_50',
        location,
      });
      await api.decide('token', underReview, { action: 'keep' });
      assert.deepEqual(calls, [
        {
          path: '/api/v1/admin/signs/sign-1/keep',
          method: 'POST',
          auth: 'Bearer token',
          body: { version: 7, signTypeCode: 'P.127_50', location },
        },
        {
          path: '/api/v1/admin/signs/sign-1/keep',
          method: 'POST',
          auth: 'Bearer token',
          body: { version: 7 },
        },
      ]);
    },
  );
});

test('FR-31 trả lỗi xung đột từ /keep và không gửi thêm yêu cầu ghi', async () => {
  await mockFetch(
    [Response.json({ code: 'SIGN_ALREADY_PROCESSED' }, { status: 409 })],
    async (calls) => {
      await assert.rejects(
        api.decide('token', underReview, {
          action: 'edit',
          signTypeCode: 'P.127_50',
        }),
        { status: 409, code: 'SIGN_ALREADY_PROCESSED' },
      );
      assert.equal(calls.length, 1);
      assert.equal(calls[0].path, '/api/v1/admin/signs/sign-1/keep');
    },
  );
});

test('phân biệt xung đột phiên bản, hết phiên và thiếu quyền', async () => {
  await mockFetch(
    [
      Response.json({ code: 'SIGN_ALREADY_PROCESSED' }, { status: 409 }),
      Response.json({ code: 'UNAUTHENTICATED' }, { status: 401 }),
      Response.json({ code: 'FORBIDDEN' }, { status: 403 }),
    ],
    async () => {
      await assert.rejects(
        api.decide('token', pending, { action: 'approve' }),
        { code: 'SIGN_ALREADY_PROCESSED' },
      );
      await assert.rejects(
        api.detail('token', pending.id),
        SessionExpiredError,
      );
      await assert.rejects(
        api.detail('token', pending.id),
        (error) => error instanceof ReviewApiError && error.status === 403,
      );
    },
  );
});

async function withDemoStorage(run: () => Promise<void>) {
  const original = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
  const store = new Map<string, string>();
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      getItem: (key: string) => store.get(key) ?? null,
      setItem: (key: string, value: string) => {
        store.set(key, value);
      },
    },
  });
  try {
    await run();
  } finally {
    if (original) Object.defineProperty(globalThis, 'localStorage', original);
    else Reflect.deleteProperty(globalThis, 'localStorage');
  }
}

test('demo gom nhiều đề xuất, sắp theo gợi ý và từ chối phiên bản cũ', () =>
  withDemoStorage(async () => {
    const queue = await reviewDemo.queue('demo', { page: 1, size: 30 });
    const pendingOnly = await reviewDemo.queue('demo', {
      page: 1,
      size: 30,
      status: 'PENDING',
    });
    const sample = await reviewDemo.detail('demo', pendingOnly.rows[0].id);
    assert.equal(sample.suggestion, 'INSPECT');
    assert.equal(sample.submissions.length, 2);
    assert.ok(queue.rows.some((row) => row.status === 'UNDER_REVIEW'));
    await reviewDemo.decide('demo', sample, { action: 'approve' });
    assert.equal(
      (await reviewDemo.detail('demo', sample.id)).status,
      'APPROVED',
    );
    assert.equal(
      (await reviewDemo.queue('demo', { page: 1, size: 30 })).total,
      queue.total - 1,
    );
    await assert.rejects(
      reviewDemo.decide('demo', sample, { action: 'approve' }),
      { code: 'SIGN_ALREADY_PROCESSED' },
    );
  }));

test('demo FR-31 giữ nguyên biển và lịch sử khi lý do quá dài', () =>
  withDemoStorage(async () => {
    const queue = await reviewDemo.queue('demo', { page: 1, size: 30 });
    const row = queue.rows.find(({ status }) => status === 'UNDER_REVIEW')!;
    const sign = await reviewDemo.detail('demo', row.id);
    await assert.rejects(
      reviewDemo.decide('demo', sign, {
        action: 'remove',
        reason: 'x'.repeat(1_001),
      }),
      /1 đến 1\.000 ký tự/,
    );
    assert.deepEqual(await reviewDemo.detail('demo', sign.id), sign);
    assert.equal(
      (
        await reviewDemo.auditLogs('demo', {
          signId: sign.id,
          page: 1,
          size: 8,
        })
      ).total,
      0,
    );
  }));

test('FR-32 đọc báo cáo theo nhóm biển và gửi Bearer token', async () => {
  const sign = {
    id: 's1',
    signTypeCode: 'P.127_60',
    location: { lat: 21, lng: 105 },
    status: 'APPROVED',
    version: 4,
  };
  const reports = [
    {
      id: 'r1',
      reason: 'WRONG_TYPE',
      status: 'PENDING',
      createdAt: '2026-11-20T00:00:00Z',
    },
    {
      id: 'r2',
      reason: 'WRONG_LOCATION',
      status: 'PENDING',
      createdAt: '2026-11-19T00:00:00Z',
    },
  ];
  await mockFetch(
    [Response.json({ items: [{ sign, reports }], total: 8 })],
    async (calls) => {
      const result = await api.reportQueue('token', {
        page: 2,
        size: 7,
        status: 'PENDING',
        reason: 'WRONG_TYPE',
      });
      assert.deepEqual(result, {
        rows: [{ sign, reports }],
        pages: 2,
        total: 8,
      });
      assert.deepEqual(calls[0], {
        path: '/api/v1/admin/reports?page=1&size=7&status=PENDING&reason=WRONG_TYPE',
        method: 'GET',
        auth: 'Bearer token',
        body: undefined,
      });
    },
  );
});

test('FR-32 chấp nhận và đổi biển bằng một yêu cầu theo ID báo cáo', async () => {
  const changes = {
    signTypeCode: 'P.127_50',
    location: { lat: 21.1, lng: 105.2 },
  };
  await mockFetch(
    [new Response(null, { status: 204 }), new Response(null, { status: 200 })],
    async (calls) => {
      await api.resolveReport('token', 'report/1', {
        action: 'EDIT',
        version: 4,
        changes,
      });
      await api.resolveReport('token', 'report2', {
        action: 'REMOVE',
        version: 8,
      });
      assert.deepEqual(calls, [
        {
          path: '/api/v1/admin/reports/report%2F1/accept',
          method: 'POST',
          auth: 'Bearer token',
          body: { action: 'EDIT', version: 4, changes },
        },
        {
          path: '/api/v1/admin/reports/report2/accept',
          method: 'POST',
          auth: 'Bearer token',
          body: { action: 'REMOVE', version: 8 },
        },
      ]);
    },
  );
});

test('FR-32 từ chối phải có lý do và giữ nguyên lỗi xung đột', async () => {
  await mockFetch(
    [
      new Response(null, { status: 204 }),
      Response.json(
        { code: 'SIGN_ALREADY_PROCESSED', message: 'Biển đã thay đổi' },
        { status: 409 },
      ),
    ],
    async (calls) => {
      await assert.rejects(
        api.resolveReport('token', 'r1', { action: 'DISMISS', note: '   ' }),
        /lý do/,
      );
      await assert.rejects(
        api.resolveReport('token', 'r1', {
          action: 'EDIT',
          version: 4,
          changes: {},
        }),
        /thay đổi/,
      );
      await assert.rejects(
        api.resolveReport('token', 'r1', {
          action: 'EDIT',
          version: 4,
          changes: { location: { lat: 91, lng: 0 } },
        }),
        /Tọa độ/,
      );
      await api.resolveReport('token', 'r1', {
        action: 'DISMISS',
        note: '  Biển vẫn còn  ',
      });
      assert.deepEqual(calls[0], {
        path: '/api/v1/admin/reports/r1/dismiss',
        method: 'POST',
        auth: 'Bearer token',
        body: { note: 'Biển vẫn còn' },
      });
      await assert.rejects(
        api.resolveReport('token', 'r2', { action: 'REMOVE', version: 3 }),
        { status: 409, code: 'SIGN_ALREADY_PROCESSED' },
      );
    },
  );
});

test('demo FR-32 giữ các báo cáo cùng biển và lưu kết quả sửa, từ chối, gỡ', () =>
  withDemoStorage(async () => {
    const filters = { page: 1, size: 7, status: 'PENDING' } as const;
    const queue = () => reviewDemo.reportQueue('demo', filters);
    const initial = await queue();
    const group = initial.rows.find((item) => item.reports.length === 2)!;
    assert.ok(group);
    const [edit, dismiss] = group.reports;
    const { id, version } = group.sign;
    const newLocation = { lat: 21.009, lng: 105.812 };
    await reviewDemo.resolveReport('demo', edit.id, {
      action: 'EDIT',
      version,
      changes: { location: newLocation },
    });
    const stillPending = (await queue()).rows.find(
      ({ sign }) => sign.id === id,
    )!;
    assert.deepEqual(
      stillPending.reports.map((report) => report.id),
      [dismiss.id],
    );
    assert.deepEqual(stillPending.sign.location, newLocation);
    assert.equal(stillPending.sign.version, version + 1);
    await assert.rejects(
      reviewDemo.resolveReport('demo', dismiss.id, {
        action: 'REMOVE',
        version,
      }),
      { code: 'SIGN_ALREADY_PROCESSED' },
    );
    await reviewDemo.resolveReport('demo', dismiss.id, {
      action: 'DISMISS',
      note: 'Không đủ bằng chứng',
    });
    assert.equal((await reviewDemo.detail('demo', id)).version, version + 1);
    const dismissed = await reviewDemo.reportQueue('demo', {
      ...filters,
      status: 'DISMISSED',
    });
    assert.equal(
      dismissed.rows[0].reports[0].resolutionNote,
      'Không đủ bằng chứng',
    );

    const another = (await queue()).rows[0];
    await reviewDemo.resolveReport('demo', another.reports[0].id, {
      action: 'REMOVE',
      version: another.sign.version,
    });
    assert.equal(
      (await reviewDemo.detail('demo', another.sign.id)).status,
      'REMOVED',
    );
    const accepted = await reviewDemo.reportQueue('demo', {
      ...filters,
      status: 'ACCEPTED',
    });
    assert.ok(
      accepted.rows.some((entry) =>
        entry.reports.some((report) => report.resolution === 'REMOVED'),
      ),
    );
    assert.ok(
      accepted.rows.some((entry) =>
        entry.reports.some((report) => report.resolution === 'EDITED'),
      ),
    );
  }));

test('FR-33 viewport tải đúng bbox và nhận cụm biển', async () => {
  const bounds = [105.8, 21, 105.9, 21.1] as const;
  const items = [{ location: { lat: 21.05, lng: 105.85 }, count: 14 }];
  await mockFetch(
    [Response.json({ mode: 'CLUSTERS', items })],
    async (calls) => {
      const result = await api.mapSigns('token', [...bounds], 15);
      const url = new URL(calls[0].path, 'http://localhost');
      assert.equal(url.pathname, '/api/v1/signs');
      assert.equal(url.searchParams.get('bbox'), bounds.join(','));
      assert.equal(url.searchParams.get('zoom'), '15');
      assert.equal(calls[0].auth, null);
      assert.deepEqual(result, { mode: 'CLUSTERS', items });
    },
  );
});

test('FR-33 tạo, sửa và gỡ dùng đúng endpoint, Bearer và version', async () => {
  const input = {
    signTypeCode: 'P.127_60',
    location: { lat: 21, lng: 105.8 },
    headingDeg: 90,
  };
  await mockFetch(
    [
      new Response(null, { status: 201 }),
      new Response(null, { status: 204 }),
      new Response(null, { status: 204 }),
    ],
    async (calls) => {
      await api.createMapSign('token', input);
      await api.patchMapSign('token', 'sign/1', 4, { headingDeg: null });
      await api.removeMapSign('token', 'sign/1', 5, '  Biển đã tháo  ');
      assert.deepEqual(calls, [
        {
          path: '/api/v1/admin/signs',
          method: 'POST',
          auth: 'Bearer token',
          body: input,
        },
        {
          path: '/api/v1/admin/signs/sign%2F1',
          method: 'PATCH',
          auth: 'Bearer token',
          body: { version: 4, headingDeg: null },
        },
        {
          path: '/api/v1/admin/signs/sign%2F1/remove',
          method: 'POST',
          auth: 'Bearer token',
          body: { version: 5, reason: 'Biển đã tháo' },
        },
      ]);
    },
  );
});

test('FR-33 kiểm tra hướng, tọa độ và lý do trước khi gửi', async () => {
  await mockFetch([], async () => {
    await assert.rejects(
      api.patchMapSign('token', 's1', 0, { headingDeg: 360 }),
      /0 đến 359/,
    );
    await assert.rejects(
      api.patchMapSign('token', 's1', 0, { location: { lat: 21, lng: 181 } }),
      /Tọa độ/,
    );
    await assert.rejects(api.patchMapSign('token', 's1', 0, {}), /thay đổi/);
    await assert.rejects(api.removeMapSign('token', 's1', 0, '   '), /lý do/);
  });
});

test('tọa độ ghim đổi qua pixel và trở về đúng vị trí trên bản đồ', () => {
  const center = { lat: 21, lng: 105.8 };
  const view = mapView(center, 14, 720, 360);
  const position = { lat: 21.003, lng: 105.803 };
  const point = view.toScreen(position);
  assert.deepEqual(view.fromScreen(point.x, point.y), position);
  assert.deepEqual(view.fromScreen(360, 180), center);
  const [west, south, east, north] = view.bounds;
  assert.ok(west < center.lng && center.lng < east);
  assert.ok(south < center.lat && center.lat < north);
});

test('demo FR-33 hiển thị biển mới, cập nhật đúng phiên bản và ghi đủ lịch sử', () =>
  withDemoStorage(async () => {
    const location = { lat: 21.038, lng: 105.823 };
    await reviewDemo.createMapSign('demo', {
      signTypeCode: 'P.130',
      location,
      headingDeg: 45,
    });
    const bounds = [105.822, 21.037, 105.824, 21.039] as const;
    const created = await reviewDemo.mapSigns('demo', [...bounds], 18);
    assert.equal(created.mode, 'SIGNS');
    assert.equal(created.items.length, 1);
    const item = created.items[0];
    assert.equal(item.status, 'APPROVED');

    const moved = { lat: 21.0392, lng: 105.8245 };
    await reviewDemo.patchMapSign('demo', item.id, 0, {
      signTypeCode: 'P.124a',
      headingDeg: 180,
      location: moved,
    });
    assert.equal(
      (await reviewDemo.mapSigns('demo', [...bounds], 18)).items.length,
      0,
    );
    await assert.rejects(
      reviewDemo.removeMapSign('demo', item.id, 0, 'Biển đã tháo'),
      { status: 409 },
    );
    await reviewDemo.removeMapSign('demo', item.id, 1, 'Biển đã tháo');
    assert.equal((await reviewDemo.detail('demo', item.id)).status, 'REMOVED');
    assert.equal(
      (
        await reviewDemo.mapSigns('demo', [105.8, 21, 105.9, 21.1], 15)
      ).items.some((row) => row.id === item.id),
      false,
    );

    const audit = readDemo().auditLogs!;
    assert.deepEqual(
      audit.map((entry) => entry.action),
      ['CREATED', 'EDITED', 'REMOVED'],
    );
    assert.equal(audit[0].beforeValue, null);
    assert.equal((audit[1].beforeValue as MapSign).headingDeg, 45);
    assert.equal((audit[1].afterValue as MapSign).headingDeg, 180);
    assert.deepEqual((audit[1].afterValue as MapSign).location, moved);
    assert.equal(audit[2].reason, 'Biển đã tháo');
  }));

test('FR-34 gửi bộ lọc biển, người xử lý, hành động và ngày UTC', async () => {
  const log = {
    id: 42,
    entityType: 'SIGN',
    entityId: 's1',
    action: 'EDITED',
    actorType: 'USER',
    actorId: 'u1',
    createdAt: '2026-11-21T08:01:00Z',
    beforeValue: { headingDeg: 10 },
    afterValue: { headingDeg: 20 },
  };
  await mockFetch(
    [Response.json({ items: [log], total: 21 })],
    async (calls) => {
      const result = await api.auditLogs('token', {
        page: 2,
        size: 10,
        signId: ' s1 ',
        actorId: 'u1',
        action: 'EDITED',
        fromDate: '2026-11-21',
        toDate: '2026-11-22',
      });
      assert.deepEqual(result, { rows: [log], total: 21, pages: 3 });
      const url = new URL(calls[0].path, 'http://localhost');
      assert.equal(url.pathname, '/api/v1/admin/audit-logs');
      assert.equal(calls[0].method, 'GET');
      assert.equal(calls[0].auth, 'Bearer token');
      assert.deepEqual(Object.fromEntries(url.searchParams), {
        page: '1',
        size: '10',
        entityType: 'SIGN',
        entityId: 's1',
        actorId: 'u1',
        action: 'EDITED',
        from: '2026-11-20T17:00:00.000Z',
        to: '2026-11-22T16:59:59.999Z',
      });
    },
  );
});

test('FR-34 thiếu quyền giữ lỗi 403 và khoảng ngày ngược bị chặn', async () => {
  await mockFetch(
    [Response.json({ code: 'FORBIDDEN' }, { status: 403 })],
    async (calls) => {
      await assert.rejects(
        api.auditLogs('token', {
          page: 1,
          size: 10,
          fromDate: '2026-11-22',
          toDate: '2026-11-21',
        }),
        /Ngày bắt đầu/,
      );
      await assert.rejects(api.auditLogs('token', { page: 1, size: 10 }), {
        status: 403,
        code: 'FORBIDDEN',
      });
      assert.equal(calls.length, 1);
    },
  );
  assert.equal(auditQuery({ page: 1, size: 10 }).from, undefined);
  assert.match(formatAuditTime('2026-11-20T17:30:00.000Z'), /21\/11\/26/);
});

test('demo FR-34 tra cứu lịch sử FR-33 cũ và ghi quyết định duyệt, báo cáo', () =>
  withDemoStorage(async () => {
    const db = readDemo();
    const signId = db.uploads.find((sign) => sign.status === 'PENDING')!.id;
    const beforeDate = '2026-11-20T17:00:00.000Z';
    const snapshot = {
      id: signId,
      signTypeCode: 'P.130',
      location: { lat: 21, lng: 105.8 },
      status: 'APPROVED',
    } as const;
    db.mapAudit = [
      {
        signId,
        action: 'EDITED',
        actorId: 'moderator.demo',
        createdAt: beforeDate,
        before: null,
        after: snapshot,
      },
      {
        signId,
        action: 'EDITED',
        actorId: 'moderator.demo',
        createdAt: '2026-11-20T16:59:59.999Z',
        before: null,
        after: snapshot,
      },
    ];
    writeDemo(db);
    const filtered = await reviewDemo.auditLogs('demo', {
      page: 1,
      size: 10,
      signId,
      actorId: 'moderator.demo',
      action: 'EDITED',
      fromDate: '2026-11-21',
      toDate: '2026-11-21',
    });
    assert.equal(filtered.total, 1);
    assert.equal(filtered.rows[0].createdAt, beforeDate);

    const sign = await reviewDemo.detail('demo', signId);
    await reviewDemo.decide('demo', sign, { action: 'approve' });
    const accepted = await reviewDemo.reportQueue('demo', {
      page: 1,
      size: 7,
      status: 'PENDING',
    });
    const group = accepted.rows[0];
    await reviewDemo.resolveReport('demo', group.reports[0].id, {
      action: 'REMOVE',
      version: group.sign.version,
    });
    const history = await reviewDemo.auditLogs('demo', {
      page: 1,
      size: 1,
      actorId: 'admin.demo',
    });
    assert.equal(history.total, 3);
    assert.equal(history.pages, 3);
    const reportHistory = await reviewDemo.auditLogs('demo', {
      page: 1,
      size: 10,
      action: 'ACCEPTED',
    });
    assert.equal(reportHistory.rows[0].entityType, 'REPORT');
    assert.equal(reportHistory.rows[0].entityId, group.reports[0].id);
    const signHistory = await reviewDemo.auditLogs('demo', {
      page: 1,
      size: 10,
      signId: group.sign.id,
    });
    assert.equal(signHistory.rows[0].action, 'REMOVED');
    assert.equal(readDemo().auditLogs?.length, 5);
    assert.equal(readDemo().mapAudit, undefined);
  }));

const categoryInput = {
  name: 'Nhóm kiểm thử',
  description: 'Mô tả',
  sortOrder: 10,
};
const typeInput = {
  categoryCode: 'TEST_GROUP',
  name: 'Biển kiểm thử',
  description: 'Ý nghĩa',
  speedKmh: 60,
  active: true,
};

test('FR-35 danh mục quản trị trả cả loại đã ngừng dùng, tra cứu vẫn công khai', async () => {
  const active = { code: 'P.127_60', recognizable: true, ...typeInput };
  const inactive = { ...active, code: 'P.127_50', active: false };
  await mockFetch(
    [
      Response.json({ items: [active, inactive] }),
      Response.json({ items: [active] }),
    ],
    async (calls) => {
      assert.deepEqual(await api.catalogTypes('admin-token'), [
        active,
        inactive,
      ]);
      assert.deepEqual(await api.catalogTypes(), [active]);
      assert.deepEqual(calls, [
        {
          path: '/api/v1/admin/sign-types',
          method: 'GET',
          auth: 'Bearer admin-token',
          body: undefined,
        },
        {
          path: '/api/v1/sign-types',
          method: 'GET',
          auth: null,
          body: undefined,
        },
      ]);
    },
  );
});

test('FR-35 dùng POST và PATCH cho nhóm biển, chuẩn hóa mã nhóm', async () => {
  await mockFetch(
    [new Response(null, { status: 201 }), new Response(null, { status: 204 })],
    async (calls) => {
      await api.saveCategory('token', ' test_group ', categoryInput, true);
      await api.saveCategory(
        'token',
        'TEST_GROUP',
        { ...categoryInput, sortOrder: 11 },
        false,
      );
      assert.deepEqual(calls, [
        {
          path: '/api/v1/admin/sign-categories',
          method: 'POST',
          auth: 'Bearer token',
          body: { code: 'TEST_GROUP', ...categoryInput },
        },
        {
          path: '/api/v1/admin/sign-categories/TEST_GROUP',
          method: 'PATCH',
          auth: 'Bearer token',
          body: { ...categoryInput, sortOrder: 11 },
        },
      ]);
    },
  );
});

test('FR-35 thêm loại chỉ nhập tay, ngừng sử dụng bằng PATCH và tải ảnh multipart', async () => {
  const picture = new File([new Uint8Array([137, 80, 78, 71])], 'icon.png', {
    type: 'image/png',
  });
  await mockFetch(
    [201, 204, 204].map((status) => new Response(null, { status })),
    async (calls) => {
      await api.saveSignType('token', 'P.127_60', typeInput, true);
      await api.saveSignType(
        'token',
        'P.127_60',
        { ...typeInput, active: false },
        false,
      );
      await api.uploadCatalogIcon('token', 'P.127_60', picture);
      assert.deepEqual(calls[0], {
        path: '/api/v1/admin/sign-types',
        method: 'POST',
        auth: 'Bearer token',
        body: { code: 'P.127_60', recognizable: false, ...typeInput },
      });
      assert.deepEqual(calls[1], {
        path: '/api/v1/admin/sign-types/P.127_60',
        method: 'PATCH',
        auth: 'Bearer token',
        body: { ...typeInput, active: false },
      });
      assert.equal(calls[2].method, 'PUT');
      assert.equal(calls[2].path, '/api/v1/admin/sign-types/P.127_60/icon');
      assert.equal(calls[2].auth, 'Bearer token');
      assert.equal(
        ((calls[2].body as FormData).get('icon') as File).name,
        'icon.png',
      );
    },
  );
});

test('FR-35 chặn tốc độ sai và định dạng ảnh khác', async () => {
  await mockFetch([], async () => {
    await assert.rejects(
      api.saveSignType(
        'token',
        'P.127_60',
        { ...typeInput, speedKmh: -1 },
        true,
      ),
      /số nguyên dương/,
    );
    await assert.rejects(
      api.uploadCatalogIcon(
        'token',
        'P.127_60',
        new File(['x'], 'icon.svg', { type: 'image/svg+xml' }),
      ),
      /PNG/,
    );
  });
});

test('FR-35 chỉ gửi ảnh minh họa không vượt quá 1.000.000 byte', async () => {
  const atLimit = new File([new Uint8Array(1_000_000)], 'icon.png', {
    type: 'image/png',
  });
  const overLimit = new File([new Uint8Array(1_000_001)], 'large.png', {
    type: 'image/png',
  });
  await mockFetch([new Response(null, { status: 204 })], async (calls) => {
    await assert.rejects(
      api.uploadCatalogIcon('token', 'P.102', overLimit),
      /1 MB/,
    );
    await assert.rejects(
      catalogDemo.uploadCatalogIcon('demo', 'P.102', overLimit),
      /1 MB/,
    );
    assert.equal(calls.length, 0);

    await api.uploadCatalogIcon('token', 'P.102', atLimit);
    assert.equal(calls.length, 1);
    const icon = (calls[0].body as FormData).get('icon');
    assert.ok(icon instanceof File);
    assert.equal(icon.size, 1_000_000);
  });
});

test('demo FR-35 sửa danh mục, tải ảnh và giữ lịch sử loại đã ngừng dùng', () =>
  withDemoStorage(async () => {
    const code = 'P.999';
    await catalogDemo.saveCategory('demo', 'TEST_GROUP', categoryInput, true);
    await catalogDemo.saveCategory(
      'demo',
      'TEST_GROUP',
      { ...categoryInput, name: 'Nhóm đã sửa' },
      false,
    );
    await catalogDemo.saveSignType('demo', code, typeInput, true);
    await assert.rejects(
      catalogDemo.saveSignType('demo', code, typeInput, true),
      { status: 409 },
    );
    await catalogDemo.uploadCatalogIcon(
      'demo',
      code,
      new File(['x'], 'image.png', { type: 'image/png' }),
    );
    const manual = (await catalogDemo.catalogTypes()).find(
      (row) => row.code === code,
    )!;
    assert.equal(manual.recognizable, false);
    assert.match(manual.iconUrl!, /^data:image\/png;base64,/);
    assert.equal(
      (await catalogDemo.catalogCategories()).find(
        (row) => row.code === 'TEST_GROUP',
      )?.name,
      'Nhóm đã sửa',
    );
    assert.ok(
      (await reviewDemo.mapSignTypes()).some((row) => row.code === code),
    );
    await catalogDemo.saveSignType(
      'demo',
      code,
      { ...typeInput, active: false },
      false,
    );
    assert.equal(
      (await catalogDemo.catalogTypes()).find((row) => row.code === code)
        ?.active,
      false,
    );
    assert.ok(
      !(await reviewDemo.mapSignTypes()).some((row) => row.code === code),
    );
    assert.equal(
      (
        await reviewDemo.auditLogs('demo', {
          page: 1,
          size: 10,
          action: 'DEACTIVATED',
        })
      ).rows[0].entityId,
      code,
    );
  }));

test('FR-36 gửi email, vai trò, trạng thái và phân trang đến API quản trị', async () => {
  const account = {
    id: 'u1',
    email: 'member@example.com',
    displayName: 'Thành viên',
    role: 'USER',
    status: 'ACTIVE',
  };
  await mockFetch(
    [Response.json({ items: [account], total: 17 })],
    async (calls) => {
      const result = await api.managedUsers('token', {
        page: 2,
        size: 8,
        query: ' MEMBER@EXAMPLE.COM ',
        role: 'USER',
        status: 'ACTIVE',
      });
      const url = new URL(calls[0].path, 'http://localhost');
      assert.equal(url.pathname, '/api/v1/admin/users');
      assert.equal(calls[0].auth, 'Bearer token');
      assert.deepEqual(Object.fromEntries(url.searchParams), {
        page: '1',
        size: '8',
        q: 'member@example.com',
        role: 'USER',
        status: 'ACTIVE',
      });
      assert.deepEqual(result, { rows: [account], total: 17, pages: 3 });
    },
  );
});

test('FR-36 đổi vai trò, khóa và mở khóa chỉ PATCH trường cần cập nhật', async () => {
  await mockFetch(
    [204, 204, 204].map((status) => new Response(null, { status })),
    async (calls) => {
      await assert.rejects(
        api.updateManagedUser('token', 'u/1', {}),
        /Chưa có thay đổi/,
      );
      await api.updateManagedUser('token', 'u/1', { role: 'MODERATOR' });
      await api.updateManagedUser('token', 'u/1', { status: 'LOCKED' });
      await api.updateManagedUser('token', 'u/1', { status: 'ACTIVE' });
      assert.deepEqual(
        calls,
        [{ role: 'MODERATOR' }, { status: 'LOCKED' }, { status: 'ACTIVE' }].map(
          (body) => ({
            path: '/api/v1/admin/users/u%2F1',
            method: 'PATCH',
            auth: 'Bearer token',
            body,
          }),
        ),
      );
    },
  );
});

test('demo FR-36 tìm theo email, bảo vệ tài khoản hiện tại và ghi lịch sử quản lý', () =>
  withDemoStorage(async () => {
    const query = { page: 1, size: 8 };
    const me = (
      await accountsDemo.managedUsers('demo', {
        ...query,
        query: 'ADMIN@Example.com',
      })
    ).rows[0];
    assert.equal(me.id, demoAdminId);
    assert.ok(isOwnAccount(me, { id: demoAdminId, email: 'khac@example.com' }));
    assert.ok(isOwnAccount(me, { email: 'ADMIN@EXAMPLE.COM' }));
    assert.ok(!isOwnAccount(me, { id: 'another-account', email: me.email }));
    await assert.rejects(
      accountsDemo.updateManagedUser('demo', me.id, { role: 'USER' }),
      { status: 403 },
    );
    await assert.rejects(
      accountsDemo.updateManagedUser('demo', me.id, { status: 'LOCKED' }),
      { status: 403 },
    );
    const user = (
      await accountsDemo.managedUsers('demo', { ...query, query: 'QUANGHUY@' })
    ).rows[0];
    assert.equal(user.role, 'USER');
    await accountsDemo.updateManagedUser('demo', user.id, {
      role: 'MODERATOR',
    });
    await accountsDemo.updateManagedUser('demo', user.id, { status: 'LOCKED' });
    const locked = await accountsDemo.managedUsers('demo', {
      ...query,
      role: 'MODERATOR',
      status: 'LOCKED',
    });
    assert.deepEqual(
      locked.rows.map(({ id, status }) => ({ id, status })),
      [{ id: user.id, status: 'LOCKED' }],
    );
    await accountsDemo.updateManagedUser('demo', user.id, { status: 'ACTIVE' });
    assert.equal(
      (await accountsDemo.managedUsers('demo', { ...query, query: user.email }))
        .rows[0].status,
      'ACTIVE',
    );
    const log = readDemo().auditLogs!;
    assert.deepEqual(
      log.map((row) => row.action),
      ['ROLE_CHANGED', 'LOCKED', 'UNLOCKED'],
    );
    assert.ok(
      log.every(
        (row) =>
          row.entityType === 'USER' &&
          row.entityId === user.id &&
          row.actorId === demoAdminId,
      ),
    );
  }));

test('FR-37 gửi đúng một ảnh multipart và đọc đủ kết quả POST', async () => {
  const file = new File(['image'], 'sign.webp', { type: 'image/webp' });
  const result = {
    image: { width: 640, height: 480 },
    results: [
      { model: { name: 'yolov5' }, inferenceMs: 82, detections: [] },
      { model: { name: 'yolov8' }, inferenceMs: 61, detections: [] },
      { model: { name: 'detr' }, inferenceMs: 120, detections: [] },
    ],
  };
  await mockFetch([Response.json(result)], async (calls) => {
    assert.deepEqual(await api.compareImage('token', file), result);
    assert.equal(calls[0].path, '/api/v1/admin/recognition/compare');
    assert.equal(calls[0].method, 'POST');
    assert.equal(calls[0].auth, 'Bearer token');
    const form = calls[0].body as FormData;
    assert.deepEqual([...form.keys()], ['image']);
    assert.equal((form.get('image') as File).name, 'sign.webp');
  });
});

test('FR-37 chặn ảnh sai định dạng, quá 10 MB và xử lý quyền', async () => {
  await mockFetch(
    [Response.json({ code: 'FORBIDDEN' }, { status: 403 })],
    async (calls) => {
      await assert.rejects(
        api.compareImage(
          'token',
          new File(['x'], 'notes.txt', { type: 'text/plain' }),
        ),
        /JPEG/,
      );
      await assert.rejects(
        api.compareImage(
          'token',
          new File([new Uint8Array(10_000_001)], 'large.png', {
            type: 'image/png',
          }),
        ),
        /10 MB/,
      );
      await assert.rejects(
        api.compareImage(
          'token',
          new File([new Uint8Array(10_000_000)], 'one.png', {
            type: 'image/png',
          }),
        ),
        { status: 403 },
      );
      assert.equal(calls.length, 1);
    },
  );
});

test('FR-37 quy đổi khung theo pixel gốc, cắt phần ngoài ảnh', () => {
  assert.deepEqual(
    boxPercent(
      { x: -10, y: 20, width: 30, height: 100 },
      { width: 100, height: 100 },
    ),
    { left: 0, top: 20, width: 20, height: 80 },
  );
  assert.equal(
    boxPercent(
      { x: 100, y: 20, width: 30, height: 10 },
      { width: 100, height: 100 },
    ),
    null,
  );
  assert.equal(
    boxPercent(
      { x: 20, y: 20, width: 20, height: 20 },
      { width: 0, height: 100 },
    ),
    null,
  );
});

test('FR-37 lấy chỉ số có trong thẻ mô hình và demo chỉ dùng số tham khảo', async () => {
  await mockFetch(
    [
      Response.json({
        models: [
          {
            modelName: 'detr',
            modelVersion: 'detr-r50-v1',
            modelCard: {
              datasetVersion: 'v1.1',
              evaluations: [{ split: 'test', map50: 0.88 }],
            },
          },
          { name: 'yolov5', version: 'v1', modelCard: {} },
        ],
      }),
    ],
    async (calls) => {
      assert.deepEqual(await api.modelEvaluations(), [
        {
          model: 'detr',
          version: 'detr-r50-v1',
          datasetVersion: 'v1.1',
          split: 'test',
          map50: 0.88,
        },
      ]);
      assert.equal(calls[0].path, '/api/v1/recognition/models');
    },
  );
  const evaluation = await compareDemo.modelEvaluations();
  assert.equal(evaluation[0].model, 'yolov8');
  assert.equal(evaluation[0].referenceOnly, true);
  const output = await compareDemo.compareImage(
    'demo',
    new File(['x'], 'one.png', { type: 'image/png' }),
  );
  assert.deepEqual(
    output.results.map((row) => row.model.name),
    ['yolov5', 'yolov8', 'detr'],
  );
});

test('FR-37 đọc thẻ DA và giữ kết quả đã chuẩn hóa theo từng tập', async () => {
  await mockFetch(
    [
      Response.json({
        models: [
          {
            modelName: 'yolov8',
            modelVersion: 'yolov8s-v1.0',
            modelCard: {
              datasetVersion: 'v1.1',
              val: {
                map_50: 0.84034,
                map_50_95: 0.69563,
                prohibitory_recall: 0.79749,
              },
              test: {
                map_50: 0.81113,
                map_50_95: 0.66496,
                operating_point: { precision: 0.89992, recall: 0.81006 },
              },
              field: null,
            },
          },
          {
            name: 'yolov5',
            version: 'yolov5s-v1.0',
            modelCard: {
              datasetVersion: 'v1.1',
              evaluations: [
                { split: 'val', map50: 0.8, datasetVersion: 'v1.0' },
              ],
              val: { map_50: 0.81 },
              test: { map_50: 0, operating_point: null },
            },
          },
          {
            modelName: 'detr',
            modelVersion: 'detr-r50-v1.0',
            modelCard: { val: { map_50: null }, test: null },
          },
        ],
      }),
    ],
    async () => {
      const rows = await api.modelEvaluations();
      assert.equal(rows.length, 4);
      assert.deepEqual(
        rows.map(({ model, version, datasetVersion, split, map50 }) => [
          model,
          version,
          datasetVersion,
          split,
          map50,
        ]),
        [
          ['yolov8', 'yolov8s-v1.0', 'v1.1', 'val', 0.84034],
          ['yolov8', 'yolov8s-v1.0', 'v1.1', 'test', 0.81113],
          ['yolov5', 'yolov5s-v1.0', 'v1.0', 'val', 0.8],
          ['yolov5', 'yolov5s-v1.0', 'v1.1', 'test', 0],
        ],
      );
      assert.equal(rows[0].map50To95, 0.69563);
      assert.equal(rows[0].precision, undefined);
      assert.equal(rows[0].recall, undefined);
      assert.equal(rows[1].map50To95, 0.66496);
      assert.equal(rows[1].precision, 0.89992);
      assert.equal(rows[1].recall, 0.81006);
    },
  );
});

test('FR-38 gọi thống kê với quyền Bearer và ngày UTC, giữ dữ liệu trả về', async () => {
  const statistics = {
    submissionsByDay: [{ date: '2026-11-21', count: 8 }],
    decisions: { approved: 4, rejected: 2 },
    processingTime: { averageMs: 21000, samples: 6 },
    signsByCategory: [
      { categoryCode: 'PROHIBITORY', name: 'Biển cấm', count: 2 },
    ],
    densityCells: [{ location: { lat: 21.03, lng: 105.8 }, count: 2 }],
  };
  await mockFetch([Response.json(statistics)], async (calls) => {
    assert.deepEqual(
      await api.statistics('token', {
        fromDate: '2026-11-21',
        toDate: '2026-11-21',
      }),
      statistics,
    );
    const url = new URL(calls[0].path, 'http://localhost');
    assert.equal(url.pathname, '/api/v1/admin/stats');
    assert.equal(calls[0].auth, 'Bearer token');
    assert.deepEqual(Object.fromEntries(url.searchParams), {
      from: '2026-11-20T17:00:00.000Z',
      to: '2026-11-21T16:59:59.999Z',
    });
  });
});

test('FR-38 chặn ngày ngược, giữ lỗi thiếu quyền và báo DTO thiếu', async () => {
  await mockFetch(
    [Response.json({}, { status: 403 }), Response.json({ total: 5 })],
    async (calls) => {
      await assert.rejects(
        api.statistics('token', {
          fromDate: '2026-11-22',
          toDate: '2026-11-21',
        }),
        /Ngày bắt đầu/,
      );
      await assert.rejects(api.statistics('token', {}), { status: 403 });
      await assert.rejects(api.statistics('token', {}), /định dạng dự kiến/);
      assert.deepEqual(
        calls.map((call) => call.path),
        ['/api/v1/admin/stats', '/api/v1/admin/stats'],
      );
    },
  );
});

test('FR-38 biểu đồ điền ngày trống và tỉ lệ chỉ tính trên số đã quyết định', () => {
  assert.deepEqual(
    dailyChartRows([{ date: '2026-11-21', count: 5 }], {
      fromDate: '2026-11-20',
      toDate: '2026-11-22',
    }),
    [
      { date: '2026-11-20', count: 0 },
      { date: '2026-11-21', count: 5 },
      { date: '2026-11-22', count: 0 },
    ],
  );
  assert.deepEqual(decisionRates({ approved: 3, rejected: 1 }), {
    approved: 0.75,
    rejected: 0.25,
  });
  assert.equal(decisionRates({ approved: 0, rejected: 0 }), null);
});

test('demo FR-38 lọc theo ngày Việt Nam, tính thời gian hợp lệ và gom mật độ', () =>
  withDemoStorage(async () => {
    const db = readDemo();
    const baseUpload = db.uploads[0];
    db.uploads = [
      {
        ...baseUpload,
        id: 'a',
        status: 'APPROVAL',
        createdAt: '2026-11-20T17:00:00Z',
        decision: { status: 'APPROVED', at: '2026-11-20T18:00:00Z' },
      },
      {
        ...baseUpload,
        id: 'b',
        status: 'REJECTED',
        createdAt: '2026-11-20T18:00:00Z',
        decision: { status: 'REJECTED', at: '2026-11-20T20:00:00Z' },
      },
      {
        ...baseUpload,
        id: 'c',
        status: 'PENDING',
        createdAt: '2026-11-21T00:00:00Z',
        decision: undefined,
      },
      {
        ...baseUpload,
        id: 'd',
        status: 'DELETED',
        createdAt: '2026-11-21T00:45:00Z',
        decision: undefined,
      },
      {
        ...baseUpload,
        id: 'e',
        status: 'PENDING',
        createdAt: '2026-11-20T16:59:59.999Z',
        decision: undefined,
      },
      {
        ...baseUpload,
        id: 'f',
        status: 'APPROVAL',
        createdAt: '2026-11-21T01:00:00Z',
        decision: undefined,
      },
    ];
    db.auditLogs = [
      {
        id: 'event',
        entityType: 'SIGN',
        entityId: 'd',
        action: 'APPROVED',
        actorType: 'USER',
        actorId: 'admin.demo',
        createdAt: '2026-11-21T03:45:00Z',
      },
    ];
    const signs: ReviewDetail[] = [
      {
        ...pending,
        id: 'a',
        status: 'APPROVED',
        signTypeCode: 'P.130',
        createdAt: '2026-11-20T17:00:00Z',
        location: { lat: 21.001, lng: 105.801 },
      },
      {
        ...pending,
        id: 'b',
        status: 'UNDER_REVIEW',
        signTypeCode: 'P.102',
        createdAt: '2026-11-20T18:00:00Z',
        location: { lat: 21.002, lng: 105.802 },
      },
      {
        ...pending,
        id: 'c',
        status: 'PENDING',
        createdAt: '2026-11-21T00:00:00Z',
      },
      {
        ...pending,
        id: 'd',
        status: 'REMOVED',
        createdAt: '2026-11-21T00:45:00Z',
      },
      {
        ...pending,
        id: 'e',
        status: 'APPROVED',
        createdAt: '2026-11-20T16:59:59.999Z',
      },
    ];
    const stats = demoStatistics(db, signs, {
      fromDate: '2026-11-21',
      toDate: '2026-11-21',
    });
    assert.deepEqual(stats.submissionsByDay, [
      { date: '2026-11-21', count: 5 },
    ]);
    assert.deepEqual(stats.decisions, { approved: 3, rejected: 1 });
    assert.deepEqual(stats.processingTime, {
      averageMs: 7_200_000,
      samples: 3,
    });
    assert.deepEqual(stats.signsByCategory, [
      { categoryCode: 'PROHIBITORY', name: 'Biển cấm', count: 2 },
    ]);
    assert.equal(stats.densityCells.length, 1);
    assert.equal(stats.densityCells[0].count, 2);
  }));

test('demo FR-38 giữ thời gian xử lý cho dữ liệu mẫu từ bản cũ', () =>
  withDemoStorage(async () => {
    const db = readDemo();
    db.uploads = db.uploads.map(({ decision: _decision, ...row }) => row);
    writeDemo(db);
    const stats = await reviewDemo.statistics('demo', {});
    assert.equal(stats.decisions.approved + stats.decisions.rejected, 23);
    assert.equal(stats.processingTime.samples, 23);
    assert.ok(stats.processingTime.averageMs! > 0);
  }));

const moderatorPermissions = [
  'review:decide',
  'report:resolve',
  'sign:edit',
  'audit:read',
  'stats:read',
  'recognition:compare',
];
const administratorPermissions = [
  ...moderatorPermissions,
  'catalog:manage',
  'user:manage',
];

test('FR-30 ma trận quyền kiểm tra menu, đường dẫn trực tiếp và quyền chỉnh sửa', () => {
  const moderator = { role: 'MODERATOR', permissions: moderatorPermissions };
  const admin = { role: 'ADMIN', permissions: administratorPermissions };
  const user = { role: 'USER', permissions: administratorPermissions };
  for (const route of [
    'overview',
    'pending',
    'reports',
    'map',
    'audit',
    'stats',
    'compare',
    'signs',
    'types',
  ] as const) {
    assert.ok(canOpenSignaPage(moderator, route));
    assert.ok(canOpenSignaPage(admin, route));
    assert.ok(!canOpenSignaPage(user, route));
  }
  assert.ok(canOpenSignaPage(admin, 'accounts'));
  assert.ok(!canOpenSignaPage(moderator, 'accounts'));
  assert.ok(!canOpenSignaPage(user, 'accounts'));
  assert.ok(hasSignaPermission(admin, 'catalog:manage'));
  assert.ok(
    !hasSignaPermission(
      { ...moderator, permissions: administratorPermissions },
      'catalog:manage',
    ),
  );
  assert.ok(
    !hasSignaPermission(
      { ...moderator, permissions: administratorPermissions },
      'user:manage',
    ),
  );
  assert.ok(!canEnterSigna({ role: 'ADMIN', permissions: [] }));
  assert.ok(
    !canOpenSignaPage(
      { role: 'MODERATOR', permissions: ['review:decide'] },
      'reports',
    ),
  );
  assert.ok(
    !hasSignaPermission(
      { role: 'MODERATOR', permissions: ['review:decide'] },
      'sign:edit',
    ),
  );
});

test('FR-30 chỉ nhận đăng nhập ADMIN/MODERATOR, kể cả khi USER được trả token', async () => {
  const response = (role: string) =>
    Response.json({
      token: 'test-token',
      expiresAt: '2099-01-01T00:00:00Z',
      user: {
        id: role + '-test',
        displayName: role,
        role,
        permissions: administratorPermissions,
      },
    });
  await mockFetch(
    [
      response('USER'),
      response('MODERATOR'),
      response('ADMIN'),
      Response.json(
        { code: 'FORBIDDEN', message: 'Tài khoản đã bị khóa' },
        { status: 403 },
      ),
    ],
    async (calls) => {
      await assert.rejects(api.login(' USER@Example.com ', 'pw'), {
        status: 403,
      });
      assert.equal(
        (await api.login('moderator@example.com', 'pw')).role,
        'MODERATOR',
      );
      assert.equal((await api.login('admin@example.com', 'pw')).role, 'ADMIN');
      await assert.rejects(api.login('locked@example.com', 'pw'), /bị khóa/);
      assert.deepEqual(calls[0].body, {
        email: 'user@example.com',
        password: 'pw',
        client: 'WEB',
      });
    },
  );
});

test('FR-30 kiểm tra /me khi khôi phục phiên, bỏ quyền cũ và phân biệt 401 với 403', async () => {
  const session = {
    sessionId: 'saved-token',
    email: 'test@example.com',
    expiresAt: '2099-01-01T00:00:00Z',
  };
  const profile = {
    id: 'profile-id',
    displayName: 'Tài khoản thử',
    role: 'MODERATOR',
    permissions: moderatorPermissions,
  };
  await mockFetch(
    [
      Response.json(profile),
      Response.json({ user: { ...profile, role: 'USER' } }),
      Response.json({ code: 'UNAUTHENTICATED' }, { status: 401 }),
      Response.json({ code: 'FORBIDDEN' }, { status: 403 }),
      new Response(null, { status: 204 }),
    ],
    async (calls) => {
      const restored = await api.restoreSession(session);
      assert.equal(restored.id, 'profile-id');
      assert.ok(!canOpenSignaPage(restored, 'accounts'));
      assert.deepEqual(calls[0], {
        path: '/api/v1/me',
        method: 'GET',
        auth: 'Bearer saved-token',
        body: undefined,
      });
      await assert.rejects(api.restoreSession(session), { status: 403 });
      await assert.rejects(api.restoreSession(session), SessionExpiredError);
      await assert.rejects(
        api.managedUsers(session.sessionId, { page: 1, size: 8 }),
        { status: 403 },
      );
      await api.logout(session.sessionId);
      assert.deepEqual(calls.at(-1), {
        path: '/api/v1/auth/logout',
        method: 'POST',
        auth: 'Bearer saved-token',
        body: undefined,
      });
    },
  );
  assert.equal(
    isCurrentSignaSession({ ...session, expiresAt: '2020-01-01' }),
    false,
  );
  await mockFetch([], async () => {
    await assert.rejects(
      api.restoreSession({ ...session, expiresAt: '2020-01-01' }),
      SessionExpiredError,
    );
  });
});

test('FR-30 giữ quyền tra cứu công khai khi API quản lý trả 403', async () => {
  const denied = () => Response.json({ code: 'FORBIDDEN' }, { status: 403 });
  await mockFetch(
    [
      denied(),
      denied(),
      Response.json({ items: [] }),
      Response.json({ items: [], total: 0 }),
    ],
    async (calls) => {
      await assert.rejects(
        api.saveCategory('moderator-token', 'TEST', categoryInput, true),
        { status: 403 },
      );
      await assert.rejects(
        api.managedUsers('moderator-token', { page: 1, size: 8 }),
        { status: 403 },
      );
      assert.deepEqual(await api.catalogTypes(), []);
      assert.equal(
        (await api.queue('moderator-token', { page: 1, size: 8 })).total,
        0,
      );
      assert.equal(calls[2].auth, null);
      assert.equal(calls[3].auth, 'Bearer moderator-token');
    },
  );
});
