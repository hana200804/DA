#!/bin/bash
# Tạo CSDL và hai tài khoản theo docs/06 §8. Chạy một lần khi khởi tạo volume MySQL.
# Compose và Testcontainers dùng chung file này.
#   signa_migrator: chạy Flyway, có GRANT OPTION để cấp quyền theo bảng cho signa_app.
#   signa_app: không có quyền mức CSDL; V3__app_grants.sql cấp quyền từng bảng,
#              riêng audit_logs chỉ SELECT, INSERT.
: "${SIGNA_MIGRATOR_PASSWORD:?Cần SIGNA_MIGRATOR_PASSWORD}"
: "${SIGNA_APP_PASSWORD:?Cần SIGNA_APP_PASSWORD}"

migrator_user="${SIGNA_DB_MIGRATOR_USER:-signa_migrator}"
app_user="${SIGNA_DB_APP_USER:-signa_app}"
name_pattern='^[A-Za-z0-9_.-]{1,32}$'
if [[ ! $migrator_user =~ $name_pattern || ! $app_user =~ $name_pattern ]]; then
  printf '%s\n' 'Tên tài khoản MySQL phải có 1–32 ký tự A–Z, a–z, 0–9, _, . hoặc -.' >&2
  exit 1
fi
if [[ $migrator_user == "$app_user" || $migrator_user == root || $app_user == root ]]; then
  printf '%s\n' 'Tài khoản MySQL của migrator, app và root phải khác nhau.' >&2
  exit 1
fi

# NO_BACKSLASH_ESCAPES giữ nguyên dấu gạch chéo. Dấu nháy đơn trong literal được nhân đôi.
migrator_password=${SIGNA_MIGRATOR_PASSWORD//\'/\'\'}
app_password=${SIGNA_APP_PASSWORD//\'/\'\'}

mysql --protocol=socket --binary-mode --default-character-set=utf8mb4 -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
SET SESSION sql_mode = 'NO_BACKSLASH_ESCAPES';
CREATE DATABASE IF NOT EXISTS signa CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '${migrator_user}'@'%' IDENTIFIED BY '${migrator_password}';
GRANT ALL PRIVILEGES ON signa.* TO '${migrator_user}'@'%' WITH GRANT OPTION;
CREATE USER IF NOT EXISTS '${app_user}'@'%' IDENTIFIED BY '${app_password}';
SQL
