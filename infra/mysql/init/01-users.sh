#!/bin/bash
# Tạo CSDL và hai tài khoản theo docs/06 §8. Chạy một lần khi khởi tạo volume MySQL.
# Compose và Testcontainers dùng chung file này.
#   signa_migrator: chạy Flyway, có GRANT OPTION để cấp quyền theo bảng cho signa_app.
#   signa_app: không có quyền mức CSDL; V3__app_grants.sql cấp quyền từng bảng,
#              riêng audit_logs chỉ SELECT, INSERT.
: "${SIGNA_MIGRATOR_PASSWORD:?Cần SIGNA_MIGRATOR_PASSWORD}"
: "${SIGNA_APP_PASSWORD:?Cần SIGNA_APP_PASSWORD}"

mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS signa CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'signa_migrator'@'%' IDENTIFIED BY '${SIGNA_MIGRATOR_PASSWORD}';
GRANT ALL PRIVILEGES ON signa.* TO 'signa_migrator'@'%' WITH GRANT OPTION;
CREATE USER IF NOT EXISTS 'signa_app'@'%' IDENTIFIED BY '${SIGNA_APP_PASSWORD}';
FLUSH PRIVILEGES;
SQL
