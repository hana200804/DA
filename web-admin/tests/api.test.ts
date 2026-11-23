import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

// Load the actual API adapter in API mode without requiring a browser or a live server.
let source = await readFile(new URL('../src/api.ts', import.meta.url), 'utf8');
source = source
  .replaceAll(
    'import.meta.env',
    '({ VITE_DATA_MODE: "api", VITE_API_CONTRACT: "legacy" })',
  )
  .replace(
    "'crypto-js/md5'",
    JSON.stringify(
      new URL('../node_modules/crypto-js/md5.js', import.meta.url).href,
    ),
  )
  .replace(
    "'./domain'",
    JSON.stringify(new URL('../src/domain.ts', import.meta.url).href),
  )
  .replace(
    "'./reviewApi.ts'",
    JSON.stringify(new URL('../src/reviewApi.ts', import.meta.url).href),
  )
  .replace(
    "'./accountTypes.ts'",
    JSON.stringify(new URL('../src/accountTypes.ts', import.meta.url).href),
  )
  .replace(
    "'./compareDemo.ts'",
    JSON.stringify(new URL('../src/compareDemo.ts', import.meta.url).href),
  )
  .replace(
    "'./accountsDemo.ts'",
    JSON.stringify(new URL('../src/accountsDemo.ts', import.meta.url).href),
  )
  .replace(
    "'./catalogDemo.ts'",
    JSON.stringify(new URL('../src/catalogDemo.ts', import.meta.url).href),
  )
  .replace(
    "'./reviewDemo.ts'",
    JSON.stringify(new URL('../src/reviewDemo.ts', import.meta.url).href),
  )
  .replace(
    "'./demo'",
    JSON.stringify(new URL('../src/demo.ts', import.meta.url).href),
  );
const compiled = ts.transpileModule(source, {
  fileName: 'api.ts',
  compilerOptions: {
    target: ts.ScriptTarget.ES2022,
    module: ts.ModuleKind.ESNext,
  },
}).outputText;
const { api, request } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled).toString('base64')}`
);
const filters = {
  query: 'Hà Nội',
  typeId: '',
  page: 2,
  size: 7,
  sort: 'oldest',
};
async function mockFetch(
  run: (
    calls: { url: string; body: Record<string, unknown> }[],
  ) => Promise<void>,
  response = {
    result: { ok: true, message: 'OK', responseCode: '00' },
    data: { data: [], totalRecords: 12, totalPages: 2 },
  },
  status = 200,
) {
  const previous = globalThis.fetch;
  const calls: { url: string; body: Record<string, unknown> }[] = [];
  globalThis.fetch = (async (url: string, options: RequestInit) => {
    calls.push({ url: String(url), body: JSON.parse(String(options.body)) });
    return new Response(JSON.stringify(response), {
      status,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;
  try {
    await run(calls);
  } finally {
    globalThis.fetch = previous;
  }
}
test('API chờ duyệt gửi asc/desc viết thường đúng hợp đồng Java', async () => {
  await mockFetch(async (calls) => {
    const page = await api.pending('session', filters);
    assert.equal(calls[0].url, '/api/rtts/rtts/v1.0/get-list-rtts-not-verify');
    assert.equal(calls[0].body.sortDir, 'asc');
    assert.equal(calls[0].body.sortBy, 'created_at');
    assert.equal(calls[0].body.pageNumber, 2);
    assert.equal(page.total, 12);
  });
});
test('API thống kê gửi PENDING/APPROVAL và DESC viết hoa', async () => {
  await mockFetch(async (calls) => {
    await api.users('session', { ...filters, sort: 'pending' });
    assert.equal(calls[0].body.sortBy, 'PENDING');
    assert.equal(calls[0].body.sortDir, 'DESC');
    await api.users('session', { ...filters, sort: 'total' });
    assert.equal(calls[1].body.sortBy, 'APPROVAL');
  });
});
test('xử lý báo cáo dùng ID báo cáo trong trường rttsId', async () => {
  await mockFetch(async (calls) => {
    await api.decide('session', 'reports', 'report-001', 'APPROVE');
    assert.equal(calls[0].url, '/api/rtts/rtts/v1.0/approve-report-rtts');
    assert.deepEqual(calls[0].body, {
      sessionId: 'session',
      rttsId: 'report-001',
      decision: 'APPROVE',
    });
  });
});
test('đăng nhập băm MD5 theo backend; không gửi mật khẩu thô', async () => {
  await mockFetch(async (calls) => {
    await api.login('admin', 'abc');
    assert.equal(calls[0].body.password, '900150983cd24fb0d6963f7d28e17f72');
    assert.equal(calls[0].body.username, 'admin');
  });
});
test('HTTP 401/403 được nhận biết là phiên hết hạn', async () => {
  for (const code of [401, 403])
    await mockFetch(
      async () => {
        await assert.rejects(() => request('auth', '/test', {}), {
          message: 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.',
        });
      },
      undefined,
      code,
    );
});
test('lỗi nghiệp vụ không bị coi là thành công', async () => {
  await mockFetch(
    async () => {
      await assert.rejects(
        () => api.editSign('session', 'id', 'Tên mới'),
        /Update fail/,
      );
    },
    {
      result: { ok: false, message: 'Update fail', responseCode: '99' },
      data: { data: [], totalRecords: 0, totalPages: 0 },
    },
  );
});
test('lịch sử truyền đúng username và trang; sửa tên gửi đúng trường', async () => {
  await mockFetch(async (calls) => {
    await api.history('session', 'minhanh', 2);
    assert.equal(calls[0].body.username, 'minhanh');
    assert.equal(calls[0].body.pageNumber, 2);
    await api.editSign('session', 'sign-01', ' Tên mới ');
    assert.deepEqual(calls[1].body, {
      sessionId: 'session',
      id: 'sign-01',
      name: 'Tên mới',
    });
  });
});
