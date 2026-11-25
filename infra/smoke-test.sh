#!/usr/bin/env bash
# Chạy trên stack dev. Kịch bản tạo tài khoản, đề xuất và một biển thử trong các volume.
set -euo pipefail

: "${SIGNA_BOOTSTRAP_ADMIN_EMAIL:?Cần xuất SIGNA_BOOTSTRAP_ADMIN_EMAIL}"
: "${SIGNA_BOOTSTRAP_ADMIN_PASSWORD:?Cần xuất SIGNA_BOOTSTRAP_ADMIN_PASSWORD}"
gateway=${SIGNA_SMOKE_ORIGIN:-http://localhost:8080}
gateway=${gateway%/}
api="$gateway/api/v1"

for tool in curl jq python3; do
  command -v "$tool" >/dev/null || { printf 'Thiếu lệnh %s\n' "$tool" >&2; exit 1; }
done

umask 077
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
response="$work/response"
payload="$work/payload"

fail() {
  printf 'Lỗi %s\n' "$1" >&2
  exit 1
}

request() {
  local step=$1
  shift
  http=$(curl --silent --show-error --connect-timeout 3 --max-time 15 \
    --output "$response" --write-out '%{http_code}' "$@") || fail "$step (curl)"
}

expect() {
  local step=$1 expected=$2
  if [[ $http != "$expected" ]]; then
    local code
    code=$(jq -r '.code // .status // empty' "$response" 2>/dev/null || true)
    fail "$step (HTTP $http, cần $expected${code:+, $code})"
  fi
}

show() {
  local step=$1 filter=$2 summary
  summary=$(jq -ce "$filter" "$response") || fail "$step (nội dung phản hồi)"
  printf '%s HTTP %s %s\n' "$step" "$http" "$summary"
}

login() {
  local client=$1 role=$2
  # Các giá trị được đưa qua stdin để mật khẩu không xuất hiện trong đối số tiến trình.
  jq -n '{
    email: input, password: input, client: input
  }' --raw-input >"$payload" < <(printf '%s\n' "$3" "$4" "$client")
  request "b) Đăng nhập $role" -H 'Content-Type: application/json' \
    --data-binary "@$payload" "$api/auth/login"
  expect "b) Đăng nhập $role" 200
  jq -er --arg role "$role" \
    'select(.user.role == $role) | .token | select(type == "string" and length > 0) |
     "Authorization: Bearer " + .' "$response" >"$5" || fail "b) Token $role"
  show "b) Đăng nhập $role" '{role: .user.role}'
}

request 'a) Health' "$gateway/actuator/health"
expect 'a) Health' 200
show 'a) Health' 'select(.status == "UP") | {status}'
request 'a) Readiness' "$gateway/actuator/health/readiness"
expect 'a) Readiness' 200
show 'a) Readiness' 'select(.status == "UP") | {status}'

# Mỗi cặp điểm cách nhau khoảng 5 m.
python3 - "$work" >"$work/run-data" <<'PY'
import base64
from pathlib import Path
from random import uniform
from secrets import token_hex
import sys
from uuid import uuid4

print(token_hex(8), token_hex(24))
print(uuid4(), uuid4())
for _ in range(8):
    latitude = uniform(20.94, 21.13)
    longitude = uniform(105.69, 105.92)
    # Hộp bao phủ ít nhất 100 m tính từ mỗi điểm trong cặp.
    bbox = (f"{longitude - 0.0011:.7f},{latitude - 0.0011:.7f},"
            f"{longitude + 0.0011:.7f},{latitude + 0.0011:.7f}")
    print(f"{latitude:.7f}", f"{longitude:.7f}", f"{latitude + 0.00004:.7f}",
          f"{longitude + 0.00002:.7f}", bbox)

# Ảnh JPEG 24×24 tự sinh, vẽ một vòng tròn đỏ và nét gạch chéo.
crop = """
/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a
HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIy
MjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjL/wAARCAAYABgDASIA
AhEBAxEB/8QAGAABAQADAAAAAAAAAAAAAAAAAAYEBQf/xAApEAABAwMDAgUFAAAAAAAAAAABAgMR
BAYSAAVBYcEHEyExQiQyQ5Hw/8QAGAEAAgMAAAAAAAAAAAAAAAAAAAQCAwX/xAAeEQEAAwEAAwAD
AAAAAAAAAAABAgMRAAQhMRJh8P/aAAwDAQACEQMRAD8A7Bc1zUVsbYaqqObq5DDCTCnVdgOTx1JA
Mm7dt8sUq90dtphvbUKK1oXkHUthUGRlIMfLCOYjS7XWGPE+3nd0WpvbUNZIW4VBtLoKyDPsDl5U
9In01m/UeIVX+WntZhfVC9wWk/sNgj+P2LSlKUkHtqmmqqqE5wETVdzNTDE9+v47f2zc1Fc+2Cqp
Tg6iA+woyppXcHg89CCA1J2k0wx4n3C1taFN7ahrFaGwoNpdBQCI9gcvNjpMemmrapMo++Q82mFV
uQ+IOP003OrLmtmiufbDS1QwdRJYfSJU0ruDyOehAIk2rSvlilRtbVysN7ahQQhaMg6lsKkQcZBj
45xxMaaaJVRk7xT5ttUPwMT6aDj+t6stm2aK2NsFLSjN1cF99QhTquwHA46kklppqYAYcvZZKyTO
bq9//9k=
"""
Path(sys.argv[1], "crop.jpg").write_bytes(base64.b64decode(crop))
PY

{
  read -r run password
  read -r client_first client_second
  cat >"$work/locations"
} <"$work/run-data"

for user in 1 2; do
  email="signa-smoke-$run-$user@example.com"
  jq -n --arg name "Signa Smoke $user" --raw-input \
    '{email: input, password: input, displayName: $name}' \
    >"$payload" < <(printf '%s\n' "$email" "$password")
  request "b) Đăng ký người dùng $user" -H 'Content-Type: application/json' \
    --data-binary "@$payload" "$api/auth/register"
  expect "b) Đăng ký người dùng $user" 201
  show "b) Đăng ký người dùng $user" 'select(.role == "USER") | {role}'
  login MOBILE USER "$email" "$password" "$work/user-$user"
done
login WEB ADMIN "$SIGNA_BOOTSTRAP_ADMIN_EMAIL" "$SIGNA_BOOTSTRAP_ADMIN_PASSWORD" "$work/admin"
admin_header="@$work/admin"

code=P.123a
request 'c) Danh mục' "$api/sign-types"
expect 'c) Danh mục' 200
show 'c) Danh mục' 'select((.labelMapVersion | type) == "number") |
  {labelMapVersion, count: (.items | length)}'
catalog_version=$(jq -er --arg code "$code" \
  'select(any(.items[]; .code == $code and .recognizable)) | .labelMapVersion' "$response") \
  || fail "c) Thiếu nhãn $code"

select_location() {
  while read -r lat_first lng_first lat_second lng_second bbox; do
    request 'd) Kiểm tra vùng trên bản đồ' -G --data-urlencode "bbox=$bbox" \
      --data-urlencode zoom=17 "$api/signs"
    expect 'd) Kiểm tra vùng trên bản đồ' 200
    jq -e '(.items | type == "array") and (.mode == "SIGNS" or .mode == "CLUSTERS")' \
      "$response" >/dev/null || fail 'd) Bản đồ trả dữ liệu vùng không hợp lệ'
    if jq -e '.items | length > 0' "$response" >/dev/null; then
      continue
    fi

    request 'd) Kiểm tra biển chờ duyệt trong vùng' -H "$admin_header" -G \
      --data-urlencode "bbox=$bbox" --data-urlencode status=PENDING "$api/admin/review-queue"
    expect 'd) Kiểm tra biển chờ duyệt trong vùng' 200
    jq -e '.items | type == "array"' "$response" >/dev/null \
      || fail 'd) Hàng đợi trả dữ liệu vùng không hợp lệ'
    if jq -e '.items | length == 0' "$response" >/dev/null; then
      printf 'd) Đã chọn vùng trống quanh hai điểm thử\n'
      return
    fi
  done <"$work/locations"
  fail 'd) Không tìm được vùng trống sau 8 lần chọn'
}
select_location

captured_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
submit() {
  local user=$1 lat=$2 lng=$3 client_id=$4 heading=$5
  jq -n --arg clientId "$client_id" --arg code "$code" --arg capturedAt "$captured_at" \
    --argjson lat "$lat" --argjson lng "$lng" --argjson headingDeg "$heading" \
    --argjson labelMapVersion "$catalog_version" '{
      $clientId, signTypeCode: $code, confidence: 0.92, location: {$lat, $lng},
      $headingDeg, source: "CAMERA", flags: [],
      model: {name: "yolov8n", version: "yolov8n-v1.0", $labelMapVersion},
      $capturedAt
    }' >"$payload"
  request "d) Đề xuất $user" -H "@$work/user-$user" \
    -F "metadata=<$payload;type=application/json" -F "crop=@$work/crop.jpg;type=image/jpeg" \
    "$api/submissions"
  expect "d) Đề xuất $user" 201
  show "d) Đề xuất $user" 'select(.status == "RECEIVED") | {id, status}'
}
submit 1 "$lat_first" "$lng_first" "$client_first" 92
first_id=$(jq -er '.id' "$response")
submit 2 "$lat_second" "$lng_second" "$client_second" 97
second_id=$(jq -er '.id' "$response")

wait_for_group() {
  local user=$1 id=$2 attempt result status
  for ((attempt = 0; attempt < 30; attempt++)); do
    request "e) Đề xuất $user" -H "@$work/user-$user" "$api/me/submissions"
    expect "e) Đề xuất $user" 200
    result=$(jq -ce --arg id "$id" '.items[] | select(.id == $id) | {id, status, signId}' "$response") \
      || fail "e) Không thấy đề xuất $user ($id)"
    status=$(jq -er '.status | select(type == "string")' <<<"$result") \
      || fail "e) Đề xuất $user ($id) thiếu trạng thái"
    case "$status" in
      GROUPED)
        jq -e '.signId | type == "string" and length > 0' <<<"$result" >/dev/null \
          || fail "e) Đề xuất $user ($id) có trạng thái GROUPED nhưng thiếu signId"
        printf 'e) Đề xuất %s HTTP %s %s\n' "$user" "$http" "$result"
        return
        ;;
      RECEIVED) ;;
      *) fail "e) Đề xuất $user ($id) có trạng thái $status, cần GROUPED" ;;
    esac
    sleep 2
  done
  fail "e) Đề xuất $user không chuyển sang GROUPED trong 60 giây"
}
wait_for_group 1 "$first_id"
sign_id=$(jq -er --arg id "$first_id" '.items[] | select(.id == $id) | .signId' "$response")
wait_for_group 2 "$second_id"
jq -e --arg id "$second_id" --arg sign "$sign_id" \
  'any(.items[]; .id == $id and .signId == $sign)' "$response" >/dev/null \
  || fail 'e) Hai đề xuất thuộc hai biển khác nhau'

request 'e) Hàng đợi' -H "$admin_header" -G --data-urlencode "bbox=$bbox" "$api/admin/review-queue"
expect 'e) Hàng đợi' 200
pending=$(jq -ce --arg sign "$sign_id" --arg code "$code" \
  '.items[] | select(.id == $sign and .signTypeCode == $code and .status == "PENDING"
    and .distinctSubmitters == 2 and (.confidence | type) == "number") |
   {id, status, signTypeCode, confidence, distinctSubmitters}' "$response") \
  || fail 'e) Không thấy biển chờ duyệt có điểm tin cậy và hai người gửi'
printf 'e) Hàng đợi HTTP %s %s\n' "$http" "$pending"

request 'e) Chi tiết biển' -H "$admin_header" "$api/admin/signs/$sign_id"
expect 'e) Chi tiết biển' 200
version=$(jq -er '.version | select(type == "number")' "$response")
crop_url=$(jq -er --arg id "$first_id" '.submissions[] | select(.id == $id) | .cropUrl' "$response")
show 'e) Chi tiết biển' '{version, submissions: (.submissions | length)}'

jq -n --argjson version "$version" '{$version}' >"$payload"
request 'f) Phê duyệt' -H "$admin_header" -H 'Content-Type: application/json' \
  --data-binary "@$payload" "$api/admin/signs/$sign_id/approve"
expect 'f) Phê duyệt' 204
printf 'f) Phê duyệt HTTP %s\n' "$http"

request 'f) Bản đồ' -G --data-urlencode "bbox=$bbox" --data-urlencode "signTypeCodes=$code" \
  --data-urlencode zoom=17 "$api/signs"
expect 'f) Bản đồ' 200
published=$(jq -ce --arg sign "$sign_id" --arg code "$code" \
  'select(.mode == "SIGNS") | .items[] |
   select(.id == $sign and .signTypeCode == $code and .status == "APPROVED") |
   {id, status, confidence}' "$response") || fail 'f) Bản đồ chưa có biển đã duyệt'
printf 'f) Bản đồ HTTP %s %s\n' "$http" "$published"

request 'f) Lịch sử' -H "$admin_header" -G --data-urlencode entityType=SIGN \
  --data-urlencode "entityId=$sign_id" "$api/admin/audit-logs"
expect 'f) Lịch sử' 200
show 'f) Lịch sử' 'select(any(.items[]; .action == "CREATED" and .actorType == "SYSTEM")
  and any(.items[]; .action == "APPROVED" and .actorType == "USER")) |
  {total, actions: [.items[] | {action, actorType}]}'

[[ $crop_url == "$gateway"/sign-crops/*X-Amz-Signature=* ]] || fail 'g) URL ký sai origin hoặc bucket'
request 'g) Ảnh cắt' "$crop_url"
expect 'g) Ảnh cắt' 200
cmp -s "$response" "$work/crop.jpg" || fail 'g) Ảnh tải về khác ảnh đã gửi'
printf 'g) Ảnh cắt HTTP %s (%s byte)\n' "$http" "$(wc -c <"$response")"

request 'h) Web quản trị' "$gateway/"
expect 'h) Web quản trị' 200
grep -Eiq '<!doctype html' "$response" && grep -Fq 'Quản trị biển báo' "$response" \
  || fail 'h) Gateway không trả HTML của web quản trị'
printf 'h) Web quản trị HTTP %s (HTML)\n' "$http"

if [[ ${SIGNA_SMOKE_EXPECT_RECOGNITION_UNAVAILABLE:-true} == true ]]; then
  request 'i) Nhận diện' -F "image=@$work/crop.jpg;type=image/jpeg" "$api/recognition/detect"
  expect 'i) Nhận diện' 503
  show 'i) Nhận diện' 'select(.code == "RECOGNITION_UNAVAILABLE") | {code}'
else
  printf 'i) Bỏ qua phép thử khi dịch vụ nhận diện vắng mặt\n'
fi
