# Test IAM / Google SSO bằng terminal — hướng dẫn chạy tay

> Toàn bộ test trong file này chạy bằng `curl`/`psql`/`python3` ở terminal, không cần Swagger,
> không cần Postman. Chỉ **1 bước duy nhất** (login Google thật) bắt buộc dùng browser — vì OAuth2
> redirect cả trang, không mô phỏng được bằng `curl`. Mọi bước còn lại đều test được 100% bằng
> terminal, kể cả **PKCE** (không cần mở DevTools).
>
> Quy ước: `$JWT` là biến bạn tự set sau khi login; không commit giá trị thật vào Git/chat.

## 0. Chuẩn bị môi trường

```bash
cd ~/IdeaProjects/HotelBookingSystem/backend

# 1. Postgres
docker compose up -d postgres
until docker exec hotelbooking-postgres pg_isready -U hotelbooking -d hotelbooking >/dev/null 2>&1; do sleep 1; done

# 2. IAM — PHẢI chạy trước Catalog/Booking (thứ tự migrate)
source .env.local
./gradlew :iam-service:bootRun
# đợi dòng "Started IamServiceApplication" rồi mở terminal khác cho các lệnh dưới

# 3. (terminal khác) Catalog
./gradlew :catalog-service:bootRun

# 4. (terminal khác) Gateway
./gradlew :api-gateway:bootRun
```

Kiểm nhanh cả 3 đã lên:

```bash
for p in 8080 8081 8084; do
  lsof -iTCP:$p -sTCP:LISTEN -P >/dev/null 2>&1 && echo "port $p: UP" || echo "port $p: DOWN"
done
```

---

## 1. JWKS — public key, không cần auth

```bash
curl -s http://localhost:8084/.well-known/jwks.json | python3 -m json.tool
```

**Kỳ vọng:** JSON có `"keys": [{"kty":"RSA","kid":"stayhub-key-1","alg":"RS256","n":"...","e":"AQAB"}]`.
**Không được có** field `"d"` (private exponent) — nếu có là lỗi lộ private key, phải báo ngay.

```bash
# Chắc chắn không lộ private key
curl -s http://localhost:8084/.well-known/jwks.json | grep -q '"d"' \
  && echo "NGUY HIỂM: lộ private key!" || echo "OK: chỉ có public key"
```

---

## 2. `/auth/start` — allowlist `app`, không cần login

```bash
echo "--- thiếu app (kỳ vọng 400) ---"
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8084/auth/start"

echo "--- app sai (kỳ vọng 400) ---"
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8084/auth/start?app=khong-ton-tai"

echo "--- app=app1 hợp lệ, chưa có session (kỳ vọng 302 sang Google) ---"
curl -s -o /dev/null -w "%{http_code}\n" "http://localhost:8084/auth/start?app=app1"
```

---

## 3. PKCE S256 — xác minh bằng terminal, không cần DevTools

`curl` có thể follow redirect từ `/auth/start` tới tận URL `authorize` thật của Google (dừng lại
đúng trước khi cần nhập mật khẩu, vì đó là trang HTML). Ta chỉ cần xem **header `Location`** của
bước redirect cuối để lấy query string gửi Google — đây chính là bằng chứng PKCE.

```bash
# Cookie jar riêng để giữ session trong lúc test (xoá sau khi xong)
rm -f /tmp/stayhub-cj.txt

curl -s -D - -o /dev/null -c /tmp/stayhub-cj.txt -b /tmp/stayhub-cj.txt \
  "http://localhost:8084/auth/start?app=app1" | grep -i "^location:"

# Dòng trên trả Location: http://localhost:8084/oauth2/authorization/google
# Theo tiếp bước đó để lấy URL Google thật:
curl -s -D - -o /dev/null -c /tmp/stayhub-cj.txt -b /tmp/stayhub-cj.txt \
  "http://localhost:8084/oauth2/authorization/google" | grep -i "^location:"
```

**Kỳ vọng:** dòng `Location:` thứ 2 trỏ tới `https://accounts.google.com/o/oauth2/v2/auth?...`
và trong query string **phải có cả 2**:
- `code_challenge=<chuỗi base64url>`
- `code_challenge_method=S256`

```bash
# Kiểm tự động, không cần đọc mắt
LOC=$(curl -s -D - -o /dev/null -b /tmp/stayhub-cj.txt \
  "http://localhost:8084/oauth2/authorization/google" | grep -i "^location:")
echo "$LOC" | grep -q "code_challenge=" && echo "✅ có code_challenge" || echo "❌ THIẾU code_challenge"
echo "$LOC" | grep -q "code_challenge_method=S256" && echo "✅ method=S256" || echo "❌ không phải S256"
```

Nếu cả 2 dòng ra `✅` — PKCE PASS, không cần mở browser bước này.

---

## 4. Login Google thật (bắt buộc dùng browser, không tránh được)

```bash
echo "http://localhost:8084/auth/start?app=app1"
```

Dán URL trên vào browser, đăng nhập bằng email đã seed trong `app_user`
(`docker/seed/seed-app-users.local.sql`). Sau khi redirect xong tới
`http://localhost:3000/callback/#token=...` ("can't be reached" là bình thường), copy đoạn sau
`#token=`.

```bash
export JWT="dán-jwt-vào-đây"
```

---

## 5. Decode & verify chữ ký JWT bằng terminal

```bash
python3 - << 'PYEOF'
import base64, json, os
token = os.environ["JWT"]
header_b64, payload_b64, sig_b64 = token.split(".")
def decode(s):
    s += "=" * (-len(s) % 4)
    return json.loads(base64.urlsafe_b64decode(s))
print("HEADER :", decode(header_b64))
print("PAYLOAD:", decode(payload_b64))
PYEOF
```

**Kỳ vọng payload có đủ:** `iss="http://localhost:8084"`, `tenant_id` là **số** (không phải chuỗi
`"1"`), `role` là `"USER"` hoặc `"ADMIN"`, `email`, `sub`, `exp - iat = 900` (15 phút).

Verify chữ ký thật (không chỉ tin nội dung, mà chứng minh IAM thật sự ký bằng private key khớp
với JWKS đang public):

```bash
python3 -m pip show pyjwt >/dev/null 2>&1 || pip install --quiet pyjwt cryptography
python3 - << 'PYEOF'
import os, urllib.request, json
from jwt import PyJWKClient, decode as jwt_decode

jwks_url = "http://localhost:8084/.well-known/jwks.json"
client = PyJWKClient(jwks_url)
token = os.environ["JWT"]
signing_key = client.get_signing_key_from_jwt(token)
claims = jwt_decode(token, signing_key.key, algorithms=["RS256"], issuer="http://localhost:8084")
print("✅ Chữ ký hợp lệ, verify bằng đúng public key JWKS hiện tại")
print(claims)
PYEOF
```

Nếu báo lỗi `InvalidSignatureError` — có nghĩa IAM đã restart (key RSA mới) sau khi JWT được phát;
login lại lấy token mới.

---

## 6. Test case 403 (email chưa provision)

```bash
# Xem danh sách hiện có
docker exec hotelbooking-postgres psql -U hotelbooking -d hotelbooking \
  -c "SELECT id, email, tenant_id, role FROM app_user ORDER BY id;"

# Xoá tạm 1 dòng (nhớ email để khôi phục)
docker exec hotelbooking-postgres psql -U hotelbooking -d hotelbooking \
  -c "DELETE FROM app_user WHERE email = 'legiabao9873@gmail.com';"
```

Mở **cửa sổ Incognito mới** → `http://localhost:8084/auth/start?app=app1` → login lại bằng
`legiabao9873@gmail.com`. **Kỳ vọng:** dừng ở `localhost:8084/login/oauth2/code/google?...`,
`HTTP ERROR 403`, không có `#token=` nào trong URL.

Khôi phục dữ liệu:

```bash
docker exec hotelbooking-postgres psql -U hotelbooking -d hotelbooking \
  -c "INSERT INTO app_user (email, tenant_id, role) VALUES ('legiabao9873@gmail.com', 1, 'ADMIN');"
```

---

## 7. Test SSO App1 → App2 — **hoàn toàn bằng terminal**, không cần mở tab 2

Mẹo: copy giá trị cookie `JSESSIONID` từ browser (sau khi đã login App1 ở bước 4) vào `curl`, rồi
gọi `/auth/start?app=app2` bằng `curl` — nếu IAM trả thẳng JWT mới **không** redirect sang Google,
đó là bằng chứng SSO, kiểm tra được bằng script, không cần mắt nhìn:

1. Trong browser (đã login App1), mở DevTools → **Application** → **Cookies** →
   `http://localhost:8084` → copy giá trị cột **Value** của `JSESSIONID`.
2. Chạy:

```bash
export IAM_SESSION="dán-giá-trị-JSESSIONID-từ-browser"

curl -s -D - -o /dev/null \
  -H "Cookie: JSESSIONID=$IAM_SESSION" \
  "http://localhost:8084/auth/start?app=app2" | grep -i "^location:"
```

**Kỳ vọng:** `Location: http://localhost:3001/callback/#token=...` — redirect **thẳng ra JWT mới
luôn**, không có bước nào trỏ `accounts.google.com`. Đây là bằng chứng SSO xác nhận bằng script,
không phụ thuộc cảm quan "có bị hỏi mật khẩu hay không" nữa.

---

## 8. Test Gateway verify JWT (độc lập với Catalog/Booking)

```bash
echo "--- Thiếu JWT (kỳ vọng 401) ---"
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/hotels

echo "--- JWT giả (kỳ vọng 401) ---"
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/hotels \
  -H "Authorization: Bearer invalid.token.here"

echo "--- JWT thật hợp lệ (kỳ vọng 200) ---"
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/hotels \
  -H "Authorization: Bearer $JWT"
```

---

## 9. Test "Catalog tự verify lại, không tin Gateway" (gọi thẳng `:8081`, bỏ qua Gateway)

```bash
echo "--- Gọi thẳng Catalog, JWT rỗng (kỳ vọng 401, không phải Gateway chặn) ---"
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/hotels

echo "--- Gọi thẳng Catalog, JWT thật (kỳ vọng 200, Catalog tự verify được) ---"
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/hotels \
  -H "Authorization: Bearer $JWT"
```

Nếu dòng 2 ra `200` — chứng minh Catalog **không** phụ thuộc Gateway để bảo mật, tự đứng độc lập
được.

---

## 10. Test role-based 403 (cần 1 JWT role USER — nếu chỉ có email ADMIN thì bỏ qua mục này)

Nếu bạn có thêm 1 Gmail thật khác được seed role `USER` (ví dụ sửa
`docker/seed/seed-app-users.local.sql` thêm 1 dòng với email Gmail thật thứ 2), login bằng email
đó lấy `$JWT_USER`, rồi:

```bash
echo "--- USER gọi API chỉ ADMIN mới được (kỳ vọng 403) ---"
curl -s -o /dev/null -w "%{http_code}\n" \
  "http://localhost:8080/api/admin/hotels/1/inventory?checkIn=2026-11-24&checkOut=2026-11-26" \
  -H "Authorization: Bearer $JWT_USER"
```

---

## 11. Bảng tổng hợp để tick khi test xong

| # | Test | Lệnh ở mục | Kỳ vọng | PASS? |
|---|---|---|---|---|
| 1 | JWKS không lộ private key | §1 | chỉ có `n`,`e`,`kid`,`kty`,`alg` | |
| 2 | `/auth/start` thiếu/sai `app` | §2 | 400, không redirect | |
| 3 | PKCE `code_challenge` + `S256` | §3 | cả 2 field có mặt | |
| 4 | Login Google thật → JWT | §4 | có `#token=` | |
| 5 | JWT đúng claim + verify chữ ký | §5 | script `✅` | |
| 6 | 403 khi chưa provision | §6 | không có `#token=` | |
| 7 | SSO app1→app2 bằng cookie | §7 | redirect thẳng, không qua Google | |
| 8 | Gateway chặn JWT sai/thiếu | §8 | 401 | |
| 9 | Catalog tự verify độc lập | §9 | 401 thiếu JWT / 200 có JWT | |
| 10 | Role-based 403 (USER gọi ADMIN API) | §10 | 403 | |

## 12. Dọn dẹp

```bash
rm -f /tmp/stayhub-cj.txt
pkill -f IamServiceApplication
pkill -f CatalogServiceApplication
pkill -f ApiGatewayApplication
docker stop hotelbooking-postgres
```
