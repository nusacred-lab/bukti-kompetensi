"""Check the actual container entry point, compiled portal assets and API boundaries."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.error
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--origin", default="http://127.0.0.1:18080")
args = parser.parse_args()
if args.origin != "http://127.0.0.1:18080":
    parser.error("This private stack probe is restricted to the local diagnostic endpoint.")


def get(path):
    try:
        with urllib.request.urlopen(args.origin + path, timeout=15) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


status, body = get("/health/live")
assert status == 200 and json.loads(body) == {"status": "up", "scope": "stack-probe"}
assets = Path(__file__).resolve().parents[1] / "web-portals/build/dist/wasmJs/productionExecutable"
for path in [assets / "index.html", assets / "web-portals.js", *assets.glob("*.wasm")]:
    status, body = get("/" if path.name == "index.html" else "/" + path.name)
    assert status == 200 and hashlib.sha256(body).digest() == hashlib.sha256(path.read_bytes()).digest()
for path in ("/health/ready", "/credentials", "/wallets", "/internal/recoveries", "/lib/",
             "/..%2flib/", "/%2e%2e/lib/", "/gradle/verification-metadata.xml"):
    assert get(path)[0] in (400, 404), "Unexpected exposed route: " + path
print("PASS: container liveness, compiled asset bytes and unavailable/protected routes")
