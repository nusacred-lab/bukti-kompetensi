"""Fail closed when a Maven scan reports no or incomplete runtime coverage."""
import json
from pathlib import Path
import sys

report = json.loads(Path(sys.argv[1] if len(sys.argv) > 1 else "build/p2-runtime-scan.json").read_text())
results = [row for row in report.get("Results", []) if row.get("Type") == "jar"]
count = sum(len(row.get("Packages", [])) for row in results)
if not count:
    raise SystemExit("Runtime scan has no JAR package coverage")
if len(sys.argv) > 2:
    sbom = json.loads(Path(sys.argv[2]).read_text())
    expected = {item["purl"] for item in sbom["components"]}
    detected = {package.get("Identifier", {}).get("PURL") for row in results for package in row.get("Packages", [])}
    if not expected or detected != expected:
        raise SystemExit("Runtime scan does not cover the complete locked Maven graph")
if any(v["Severity"] in {"HIGH", "CRITICAL"} for row in results for v in row.get("Vulnerabilities", [])):
    raise SystemExit("Runtime scan contains High/Critical findings")
print(f"PASS: Maven runtime scan covers {count} detected packages")
