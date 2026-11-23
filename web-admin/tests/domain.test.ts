import test from 'node:test';
import assert from 'node:assert/strict';
import {
  boundingBox,
  csvCell,
  matches,
  normalizedImage,
  paginate,
  percentage,
} from '../src/domain.ts';
import {
  decideReport,
  decideUpload,
  DEMO_KEY,
  nearbyApproved,
  readDemo,
  seedDemo,
  stats,
  writeDemo,
} from '../src/demo.ts';

const storage = new Map<string, string>();
Object.defineProperty(globalThis, 'localStorage', {
  configurable: true,
  value: {
    getItem: (k: string) => storage.get(k) ?? null,
    setItem: (k: string, v: string) => storage.set(k, v),
    removeItem: (k: string) => storage.delete(k),
  },
});
test('tìm kiếm tiếng Việt không dấu, không phân biệt đ/Đ', () => {
  assert.equal(matches('duong', 'Đường người đi bộ'), true);
  assert.equal(matches('nguoc chieu', 'Cấm đi ngược chiều'), true);
  assert.equal(matches('P.102', 'P.102'), true);
  assert.equal(matches('P.127', 'P.102'), false);
});
test('phân trang xử lý trang trống và trang vượt giới hạn', () => {
  assert.deepEqual(paginate([], 1, 8), { rows: [], total: 0, pages: 1 });
  assert.deepEqual(paginate([1, 2, 3, 4, 5], 9, 2), {
    rows: [5],
    total: 5,
    pages: 3,
  });
});
test('bbox hỏng không gây lỗi và bbox hợp lệ được đổi sang phần trăm', () => {
  assert.equal(boundingBox('{broken'), null);
  assert.equal(boundingBox('[1,2,3,4]'), null);
  assert.equal(boundingBox('[0.5,0.5,0,0.2]'), null);
  assert.deepEqual(boundingBox('[0.5,0.5,0.2,0.4]'), {
    left: 40,
    top: 30,
    width: 19.999999999999996,
    height: 40,
  });
  const clipped = boundingBox('[0.1,0.5,0.4,0.4]')!;
  assert.equal(clipped.left, 0);
  assert.ok(clipped.width <= 100);
});
test('ảnh nhận URL, base64 và loại giao thức không hợp lệ', () => {
  assert.equal(
    normalizedImage('//cdn.example.com/sign.png'),
    'https://cdn.example.com/sign.png',
  );
  assert.equal(normalizedImage('javascript:alert(1)'), '');
  assert.equal(normalizedImage('object-id-not-base64'), '');
  assert.ok(
    normalizedImage('A'.repeat(100)).startsWith('data:image/jpeg;base64,'),
  );
});
test('độ tin cậy chấp nhận hệ 0–1 và 0–100', () => {
  assert.equal(percentage(0.95), 95);
  assert.equal(percentage(95), 95);
  assert.equal(percentage(120), 100);
  assert.equal(percentage(-5), 0);
});
test('CSV giữ dấu ngoặc kép và vô hiệu chuỗi công thức', () => {
  assert.equal(csvCell('Biển "cấm"'), '"Biển ""cấm"""');
  assert.equal(csvCell('=HYPERLINK("x")'), '"\'=HYPERLINK(""x"")"');
});
test('phê duyệt cập nhật trạng thái và thống kê người dùng', () => {
  const db = seedDemo();
  const row = db.uploads.find((u) => u.status === 'PENDING')!;
  const before = stats(db).find((u) => u.custId === row.createdBy)!;
  decideUpload(db, row.id, 'APPROVE');
  const after = stats(db).find((u) => u.custId === row.createdBy)!;
  assert.equal(row.status, 'APPROVAL');
  assert.equal(after.pending, before.pending - 1);
  assert.equal(after.approval, before.approval + 1);
  assert.throws(() => decideUpload(db, row.id, 'REJECT'));
});
test('từ chối đóng góp không làm tăng số đã duyệt', () => {
  const db = seedDemo();
  const row = db.uploads[0];
  const before = stats(db).find((u) => u.custId === row.createdBy)!;
  decideUpload(db, row.id, 'REJECT');
  const after = stats(db).find((u) => u.custId === row.createdBy)!;
  assert.equal(row.status, 'REJECTED');
  assert.equal(after.approval, before.approval);
  assert.equal(after.rejected, before.rejected + 1);
});
test('chấp nhận báo cáo loại biển liên quan khỏi dữ liệu hiển thị', () => {
  const db = seedDemo();
  const report = db.reports[0];
  decideReport(db, report.id, 'APPROVE');
  assert.equal(report.status, 'APPROVAL');
  assert.equal(
    db.uploads.find((u) => u.id === report.rttsId)?.status,
    'DELETED',
  );
  assert.throws(() => decideReport(db, report.id, 'APPROVE'));
});
test('từ chối báo cáo giữ nguyên biển báo đã duyệt', () => {
  const db = seedDemo();
  const report = db.reports[0];
  decideReport(db, report.id, 'REJECT');
  assert.equal(report.status, 'REJECTED');
  assert.equal(
    db.uploads.find((u) => u.id === report.rttsId)?.status,
    'APPROVAL',
  );
});
test('kiểm tra trùng chỉ lấy cùng mã, đã duyệt và trong bán kính 150m', () => {
  const db = seedDemo();
  const row = db.uploads[0];
  const near = nearbyApproved(db.uploads, row);
  assert.equal(near.length, 1);
  assert.equal(near[0].trafficSignId, row.trafficSignId);
  const moved = { ...near[0], latitude: 22 };
  assert.equal(nearbyApproved([moved], row).length, 0);
});
test('thay đổi demo tồn tại sau khi đọc lại; dữ liệu lưu hỏng được phục hồi', () => {
  const db = seedDemo();
  decideUpload(db, db.uploads[0].id, 'REJECT');
  writeDemo(db);
  assert.equal(readDemo().uploads[0].status, 'REJECTED');
  storage.set(DEMO_KEY, '{broken');
  assert.equal(readDemo().uploads[0].status, 'PENDING');
  storage.clear();
});
