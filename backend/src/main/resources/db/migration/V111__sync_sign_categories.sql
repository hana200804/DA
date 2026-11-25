-- Cập nhật bản sao nhóm cho biển đã tồn tại trước khi có đồng bộ trong giao dịch.
UPDATE signs s JOIN sign_types t ON t.code = s.sign_type_code
SET s.category_code = t.category_code, s.version = s.version + 1, s.updated_at = UTC_TIMESTAMP(3)
WHERE s.category_code <> t.category_code;
