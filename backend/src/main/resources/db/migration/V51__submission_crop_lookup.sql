-- Đối chiếu từng lô ảnh trong MinIO với khoá đã lưu của đề xuất.
ALTER TABLE submissions ADD INDEX ix_submissions_crop_key (crop_key);
