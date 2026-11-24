CREATE INDEX ix_reports_user_created ON sign_reports (user_id, created_at, id);
CREATE INDEX ix_reports_photo_key ON sign_reports (photo_key);
