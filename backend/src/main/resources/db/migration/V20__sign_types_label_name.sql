-- Lát 2. Tên loại biển lấy từ labels.yaml ở lần nạp gần nhất. Lệnh nạp nhãn chỉ đổi `name`
-- khi `name` vẫn bằng giá trị này, tức quản trị viên chưa sửa (docs/06 §5).
-- Quyền của tài khoản ứng dụng trên sign_types cấp theo bảng ở V3, đã gồm cột mới.

ALTER TABLE sign_types
  ADD COLUMN label_name VARCHAR(200) NULL AFTER label_map_version;

UPDATE sign_types SET label_name = name WHERE label_id IS NOT NULL;
