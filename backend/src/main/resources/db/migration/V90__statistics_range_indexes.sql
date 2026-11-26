-- Lọc thống kê theo thời điểm nhận đề xuất hoặc thời điểm quyết định của biển.
ALTER TABLE submissions ADD INDEX ix_submissions_received (received_at);
ALTER TABLE signs ADD INDEX ix_signs_status_reviewed (status, reviewed_at);
