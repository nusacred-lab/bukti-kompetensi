"""Fail closed when a JVM scan reports no runtime package coverage."""
import json
from pathlib import Path
import sys

report = json.loads(Path(sys.argv[1] if len(sys.argv) > 1 else "build/p2-runtime-scan.json").read_text())
results = [row for row in report.get("Results", []) if row.get("Type") == "jar"]
count = sum(len(row.get("Packages", [])) for row in results)
if not count:
    raise SystemExit("Runtime scan has no JAR package coverage")
if any(v["Severity"] in {"HIGH", "CRITICAL"} for row in results for v in row.get("Vulnerabilities", [])):
    raise SystemExit("Runtime scan contains High/Critical findings")
print(f"PASS: JVM runtime scan covers {count} detected packages")
