"""A0 抓包重放验证 — 确认抢单接口可重放 + 随机 Nami 是否可行。"""
import hashlib, json, time, uuid, base64
import urllib.request

BASE = "https://gw.xiaocantech.com/rpc"
SERVER = "Silkworm"
METHOD = "SilkwormService.GrabPromotionQuota"

# 抓包原值（favorites1.json 成功那条, code=0）
ORIG_NAMI = "6762225593567970"
ORIG_GAREN = 1783913626327
ORIG_ASHE = "68d194aeff6da5a1e6a46c4adaa4f33d"
X_SIVIR = ("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJVc2VySWQiOjUyNjMxMDYsImV4cCI6MTc4NjE0NzExM30."
           "2DlKRCCxB47Tfsyud5seq_czrbpwANgrqozNHRU2qA0")
X_SESSION = "925d8dc4-cac0-46d5-b7ad-9b2a881e16cc"
X_TEEMO = "222559356"
X_VAYNE = "5263106"

PROMO_ID = 118060132
SILK_ID = 222559356
CITY = 440111
LAT, LNG = 23.250941, 113.310739

def ashe(garen, nami):
    c = hashlib.md5((SERVER + "." + METHOD).lower().encode()).hexdigest()
    return hashlib.md5((c + str(garen) + nami).encode()).hexdigest()

def random_nami():
    u = uuid.uuid4().hex
    silk = "0"
    return u[:4] + silk + u[4:20 - len(silk) - 4]

def headers(garen, nami, ashe_v):
    return {
        "servername": SERVER, "methodname": METHOD,
        "X-Ashe": ashe_v, "X-Nami": nami, "X-Garen": str(garen),
        "X-Platform": "Android", "x-Annie": "XC", "X-Session-Id": X_SESSION,
        "User-Agent": "XC;Android;3.18.3;", "x-channel": "OPPO",
        "X-Vayne": X_VAYNE, "x-Teemo": X_TEEMO, "X-Sivir": X_SIVIR,
        "X-Version": "3.18.3.3", "X-CityCode": str(CITY), "X-City": str(CITY),
        "Content-Type": "application/json; charset=utf-8",
    }

def body():
    return json.dumps({
        "latitude": LAT, "city_code": CITY, "store_platform": 1,
        "longitude": LNG, "if_advance_order": False,
        "promotion_id": PROMO_ID, "silk_id": SILK_ID,
    })

def post(h):
    req = urllib.request.Request(BASE, data=body().encode(), headers=h, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")

def jwt_exp():
    try:
        p = X_SIVIR.split(".")[1]
        p += "=" * (-len(p) % 4)
        j = json.loads(base64.urlsafe_b64decode(p))
        days = (j["exp"] - time.time()) / 86400
        return json.dumps(j), round(days, 1)
    except Exception as e:
        return f"err {e}", None

print("=== JWT ===")
print(jwt_exp())

print("\n=== 基线: 原值 Nami + 原签名 ===")
print(post(headers(ORIG_GAREN, ORIG_NAMI, ORIG_ASHE)))

print("\n=== 随机 Nami + 当前时间 + 重算签名 ===")
g = int(time.time() * 1000)
n = random_nami()
print("nami:", n, "garen:", g, "ashe:", ashe(g, n))
print(post(headers(g, n, ashe(g, n))))
