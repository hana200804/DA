-- Vai trò và quyền theo docs/06 §4. Backend kiểm tra quyền, không kiểm tra tên vai trò.

INSERT INTO roles (code, name, description) VALUES
  ('USER', 'Người dùng', 'Nhận diện, tra cứu, đóng góp đề xuất, đánh giá và báo cáo'),
  ('MODERATOR', 'Kiểm duyệt viên', 'Phê duyệt biển, xử lý báo cáo, sửa biển'),
  ('ADMIN', 'Quản trị viên', 'Toàn quyền, gồm danh mục và người dùng');

INSERT INTO permissions (code, description) VALUES
  ('submission:create', 'Gửi đề xuất (FR-04)'),
  ('sign:rate', 'Đánh giá biển (FR-09)'),
  ('report:create', 'Báo cáo biển sai (FR-10)'),
  ('review:decide', 'Phê duyệt biển (FR-31)'),
  ('report:resolve', 'Xử lý báo cáo (FR-32)'),
  ('sign:edit', 'Thêm, sửa, gỡ biển trực tiếp (FR-33)'),
  ('audit:read', 'Xem lịch sử xử lý (FR-34)'),
  ('stats:read', 'Xem thống kê (FR-38)'),
  ('recognition:compare', 'So sánh ba mô hình (FR-37)'),
  ('catalog:manage', 'Quản lý danh mục biển (FR-35)'),
  ('user:manage', 'Quản lý người dùng (FR-36)');

INSERT INTO role_permissions (role_code, permission_code)
SELECT r.code, p.code
FROM roles r
JOIN permissions p
WHERE p.code IN ('submission:create', 'sign:rate', 'report:create')
   OR (r.code IN ('MODERATOR', 'ADMIN')
       AND p.code IN ('review:decide', 'report:resolve', 'sign:edit', 'audit:read',
                      'stats:read', 'recognition:compare'))
   OR (r.code = 'ADMIN' AND p.code IN ('catalog:manage', 'user:manage'));
