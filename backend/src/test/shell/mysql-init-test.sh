#!/usr/bin/env bash
set -euo pipefail

init_script="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)/infra/mysql/init/01-users.sh"
test_dir=$(mktemp -d)
trap 'rm -rf "$test_dir"' EXIT
export PATH="$test_dir:$PATH"
export MYSQL_ROOT_PASSWORD=test-root
export SIGNA_DB_MIGRATOR_USER=signa-deploy
export SIGNA_DB_APP_USER=signa.reader
export SIGNA_MIGRATOR_PASSWORD=$'ký tự \' \\; $ ` " cuối\\'
export SIGNA_APP_PASSWORD=$'mật\r\nkhẩu\\\'kép\'\''

cat > "$test_dir/mysql" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ " $* " == *' --binary-mode '* && " $* " == *' --default-character-set=utf8mb4 '* ]]
cat
MOCK
chmod +x "$test_dir/mysql"

bash "$init_script" > "$test_dir/generated.sql"
printf '%s\n' \
  "SET SESSION sql_mode = 'NO_BACKSLASH_ESCAPES';" \
  'CREATE DATABASE IF NOT EXISTS signa CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;' \
  "CREATE USER IF NOT EXISTS 'signa-deploy'@'%' IDENTIFIED BY 'ký tự '' \; \$ \` \" cuối\';" \
  "GRANT ALL PRIVILEGES ON signa.* TO 'signa-deploy'@'%' WITH GRANT OPTION;" \
  $'CREATE USER IF NOT EXISTS \'signa.reader\'@\'%\' IDENTIFIED BY \'mật\r\nkhẩu\\\'\'kép\'\'\'\'\';' \
  > "$test_dir/expected.sql"
diff -u "$test_dir/expected.sql" "$test_dir/generated.sql"

if SIGNA_DB_APP_USER="invalid'name" bash "$init_script" > "$test_dir/invalid.sql" 2>/dev/null; then
  printf '%s\n' 'Tên nằm ngoài tập cho phép phải bị từ chối' >&2
  exit 1
fi
test ! -s "$test_dir/invalid.sql"
