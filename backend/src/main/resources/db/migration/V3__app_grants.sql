-- Tài khoản ứng dụng chỉ có quyền theo từng bảng; audit_logs chỉ SELECT, INSERT (FR-23, docs/06 §8).
-- Migration chạy bằng tài khoản migrator có GRANT OPTION. ${app_user} lấy từ cấu hình Flyway.

GRANT SELECT ON roles TO ${app_user};
GRANT SELECT ON permissions TO ${app_user};
GRANT SELECT ON role_permissions TO ${app_user};
GRANT SELECT, INSERT, UPDATE ON users TO ${app_user};
GRANT SELECT, INSERT, UPDATE ON sign_categories TO ${app_user};
GRANT SELECT, INSERT, UPDATE ON sign_types TO ${app_user};
GRANT SELECT, INSERT, UPDATE ON signs TO ${app_user};
GRANT SELECT, INSERT, UPDATE, DELETE ON sign_code_weights TO ${app_user};
GRANT SELECT, INSERT, UPDATE ON submissions TO ${app_user};
GRANT SELECT, INSERT, UPDATE ON sign_ratings TO ${app_user};
GRANT SELECT, INSERT, UPDATE ON sign_reports TO ${app_user};
GRANT SELECT, INSERT ON audit_logs TO ${app_user};
