#!/usr/bin/env bash
# Chạy trên stack dev. Kịch bản tạo tài khoản, đề xuất và một biển thử trong các volume.
set -euo pipefail

admin_email=${SIGNA_BOOTSTRAP_ADMIN_EMAIL:?Cần truyền SIGNA_BOOTSTRAP_ADMIN_EMAIL qua môi trường. Xem infra/README.md}
admin_password=${SIGNA_BOOTSTRAP_ADMIN_PASSWORD:?Cần truyền SIGNA_BOOTSTRAP_ADMIN_PASSWORD qua môi trường. Xem infra/README.md}
unset SIGNA_BOOTSTRAP_ADMIN_EMAIL SIGNA_BOOTSTRAP_ADMIN_PASSWORD
export -n admin_email admin_password
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
  printf '%s\0' "$3" "$4" "$client" | jq -Rs 'split("\u0000") |
    {email: .[0], password: .[1], client: .[2]}' >"$payload"
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
request 'a) Health URL-encode' "$gateway/actuator/%68ealth"
expect 'a) Health URL-encode' 200
show 'a) Health URL-encode' 'select(.status == "UP") | {status}'
for path in /actuator/prometheus /actuator/env /actuator/health/liveness; do
  request "a) $path" "$gateway$path"
  expect "a) $path" 404
  printf 'a) %s HTTP %s\n' "$path" "$http"
done

# Mỗi cặp điểm cách nhau khoảng 5 m.
python3 - "$work" >"$work/run-data" <<'PY'
import base64
from pathlib import Path
from random import uniform
from secrets import token_hex
import sys
from uuid import uuid4

print(token_hex(8), token_hex(24))
for _ in range(8):
    latitude = uniform(20.94, 21.13)
    longitude = uniform(105.69, 105.92)
    # Hộp bao phủ ít nhất 100 m tính từ mỗi điểm trong cặp.
    bbox = (f"{longitude - 0.0011:.7f},{latitude - 0.0011:.7f},"
            f"{longitude + 0.0011:.7f},{latitude + 0.0011:.7f}")
    print(f"{latitude:.7f}", f"{longitude:.7f}", f"{latitude + 0.00004:.7f}",
          f"{longitude + 0.00002:.7f}", bbox, uuid4(), uuid4())

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
login WEB ADMIN "$admin_email" "$admin_password" "$work/admin"
admin_header="@$work/admin"

code=P.123a
request 'c) Danh mục' "$api/sign-types"
expect 'c) Danh mục' 200
show 'c) Danh mục' 'select((.labelMapVersion | type) == "number") |
  {labelMapVersion, count: (.items | length)}'
catalog_version=$(jq -er --arg code "$code" \
  'select(any(.items[]; .code == $code and .recognizable)) | .labelMapVersion' "$response") \
  || fail "c) Thiếu nhãn $code"

location_available() {
  local bbox=$1
  request 'd) Kiểm tra vùng trên bản đồ' -G --data-urlencode "bbox=$bbox" \
    --data-urlencode zoom=17 "$api/signs"
  expect 'd) Kiểm tra vùng trên bản đồ' 200
  jq -e '(.items | type == "array") and (.mode == "SIGNS" or .mode == "CLUSTERS")' \
    "$response" >/dev/null || fail 'd) Bản đồ trả dữ liệu vùng không hợp lệ'
  jq -e '.items | length == 0' "$response" >/dev/null || return 1

  request 'd) Kiểm tra biển chờ duyệt trong vùng' -H "$admin_header" -G \
    --data-urlencode "bbox=$bbox" --data-urlencode status=PENDING "$api/admin/review-queue"
  expect 'd) Kiểm tra biển chờ duyệt trong vùng' 200
  jq -e '.items | type == "array"' "$response" >/dev/null \
    || fail 'd) Hàng đợi trả dữ liệu vùng không hợp lệ'
  jq -e '.items | length == 0' "$response" >/dev/null
}

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
wait_for_submission() {
  local user=$1 id=$2 attempt result status
  for ((attempt = 0; attempt < 30; attempt++)); do
    request "e) Đề xuất $user" -H "@$work/user-$user" "$api/me/submissions"
    expect "e) Đề xuất $user" 200
    result=$(jq -ce --arg id "$id" '.items[] | select(.id == $id) | {id, status, signId}' "$response") \
      || fail "e) Không thấy đề xuất $user ($id)"
    status=$(jq -er '.status | select(type == "string")' <<<"$result") \
      || fail "e) Đề xuất $user ($id) thiếu trạng thái"
    if [[ $status != RECEIVED ]]; then
      submission_status=$status
      submission_sign_id=
      if [[ $status == GROUPED ]]; then
        submission_sign_id=$(jq -er '.signId | select(type == "string" and length > 0)' <<<"$result") \
          || fail "e) Đề xuất $user ($id) có trạng thái GROUPED nhưng thiếu signId"
      fi
      printf 'e) Đề xuất %s HTTP %s %s\n' "$user" "$http" "$result"
      return
    fi
    sleep 2
  done
  fail "e) Đề xuất $user vẫn ở trạng thái RECEIVED sau 60 giây"
}

attempts=0
grouped=false
while read -r lat_first lng_first lat_second lng_second bbox client_first client_second; do
  location_available "$bbox" || continue
  attempts=$((attempts + 1))
  printf 'd) Gửi cặp đề xuất thử lần %s\n' "$attempts"

  submit 1 "$lat_first" "$lng_first" "$client_first" 92
  first_id=$(jq -er '.id' "$response")
  submit 2 "$lat_second" "$lng_second" "$client_second" 97
  second_id=$(jq -er '.id' "$response")
  wait_for_submission 1 "$first_id"
  first_status=$submission_status
  sign_id=$submission_sign_id
  wait_for_submission 2 "$second_id"

  if [[ $first_status == GROUPED && $submission_status == GROUPED ]]; then
    [[ $sign_id == "$submission_sign_id" ]] || fail 'e) Hai đề xuất thuộc hai biển khác nhau'
    grouped=true
    break
  fi
  printf 'e) Cặp thử lần %s có trạng thái %s và %s\n' \
    "$attempts" "$first_status" "$submission_status" >&2
  if ((attempts == 5)); then
    break
  fi
done <"$work/locations"
if ((attempts == 0)); then
  fail 'd) Không tìm được vùng trống sau 8 lần chọn'
fi
[[ $grouped == true ]] \
  || fail "e) Không gom cụm được sau $attempts lần gửi (lần cuối: $first_status và $submission_status)"

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
[[ $crop_url != *[[:cntrl:]]* && $crop_url != *'"'* && $crop_url != *\\* ]] \
  || fail 'g) URL ký chứa ký tự chưa được mã hóa'
request 'g) Ảnh cắt' --config - < <(printf 'url = "%s"\n' "$crop_url")
expect 'g) Ảnh cắt' 200
cmp -s "$response" "$work/crop.jpg" || fail 'g) Ảnh tải về khác ảnh đã gửi'
printf 'g) Ảnh cắt HTTP %s (%s byte)\n' "$http" "$(wc -c <"$response")"

request 'h) Web quản trị' "$gateway/"
expect 'h) Web quản trị' 200
if grep -Eiq '<!doctype html' "$response" && grep -Fq 'Quản trị biển báo' "$response"; then
  printf 'h) Web quản trị HTTP %s (HTML)\n' "$http"
else
  fail 'h) Gateway không trả HTML của web quản trị'
fi

if [[ ${SIGNA_SMOKE_EXPECT_RECOGNITION_UNAVAILABLE:-true} == true ]]; then
  request 'i) Nhận diện' -F "image=@$work/crop.jpg;type=image/jpeg" "$api/recognition/detect"
  expect 'i) Nhận diện' 503
  show 'i) Nhận diện' 'select(.code == "RECOGNITION_UNAVAILABLE") | {code}'
else
  printf 'i) Bỏ qua phép thử khi dịch vụ nhận diện vắng mặt\n'
fi
