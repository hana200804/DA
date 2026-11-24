-- Giữ chỉ mục theo trạng thái, thêm thứ tự nhận để quét lại và tìm đề xuất chờ lâu nhất.
ALTER TABLE submissions
  DROP INDEX ix_submissions_status,
  ADD INDEX ix_submissions_status_received (status, received_at);
