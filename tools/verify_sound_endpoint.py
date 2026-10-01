"""Check that the deployed sound endpoint rejects unauthenticated requests."""
import json
import urllib.error
import urllib.request
from pathlib import Path

url = "https://asia-northeast3-knok-641b9.cloudfunctions.net/analyzeRecordingSound"
results = []
for name, extra in [("missing_auth_and_app_check", {}), ("forged_auth_and_app_check", {
    "Authorization": "Bearer invalid-test-token", "X-Firebase-AppCheck": "invalid-test-token"
})]:
    request = urllib.request.Request(url, data=json.dumps({"data": {}}).encode(),
        headers={"Content-Type": "application/json", **extra}, method="POST")
    try:
        with urllib.request.urlopen(request, timeout=45) as response:
            status, body = response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        status, body = error.code, json.loads(error.read())
    result = {"case": name, "http_status": status, "error_status": body.get("error", {}).get("status")}
    results.append(result)
    print(json.dumps(result), flush=True)
output = Path(__file__).resolve().parents[1] / "artifacts/gemini_validation/server-auth-check.json"
output.write_text(json.dumps(results, indent=2), encoding="utf-8")
raise SystemExit(0 if all(item["http_status"] == 401 and item["error_status"] == "UNAUTHENTICATED" for item in results) else 1)
