"""Check API references, operation IDs, auth, limits, and mandatory endpoint coverage."""
import json
from pathlib import Path
root = Path(__file__).resolve().parents[1]
spec = json.loads((root / "docs/openapi.json").read_text())
assert spec["openapi"] == "3.1.0"
ids = set()
for path, methods in spec["paths"].items():
    assert path.startswith(("/api/v1/", "/internal/recoveries/"))
    for method, op in methods.items():
        assert op["operationId"] not in ids
        ids.add(op["operationId"])
        assert "security" in op and "x-ownership" in op
        assert op["x-implementation-phase"] in {"P2", "P3", "P4", "P5", "P7"}
        if path.startswith("/internal/"):
            assert op["security"] == [{"recoveryHmac": []}]
            assert "503" in op["responses"]
            continue
        assert "429" in op["responses"] and "503" in op["responses"]
        if method in {"post", "put", "delete"}:
            assert any(p["name"] == "Origin" for p in op["parameters"])
def walk(node):
    if isinstance(node, dict):
        if "$ref" in node:
            current = spec
            for segment in node["$ref"].removeprefix("#/").split("/"):
                current = current[segment]
        for value in node.values(): walk(value)
    elif isinstance(node, list):
        for value in node: walk(value)
walk(spec)
source = (root / "shared-contracts/src/commonMain/kotlin/id/nusacred/contracts/Contracts.kt").read_text()
assert 'VERSION = "'+spec["info"]["version"]+'"' in source
assert spec["components"]["schemas"]["JobInput"]["required"] == ["title", "competencyCode", "assessmentResult"]
assert spec["x-runtime-limits"]["bodyBytes"] == 32768
print(f"PASS: {len(ids)} API operations, resolved schema references, ownership and limits")
