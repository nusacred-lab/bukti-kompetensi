"""Export the built target's locked Maven runtime graph for vulnerability review."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
from urllib.parse import quote

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--module", choices=["android-wallet", "custodian-backend"], required=True)
parser.add_argument("--configuration", required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
lock = root / args.module / "gradle.lockfile"
components = []
for row in lock.read_text().splitlines():
    if row.startswith("#") or "=" not in row:
        continue
    name, configurations = row.split("=", 1)
    if args.configuration not in configurations.split(",") or name == "empty":
        continue
    group, artifact, version = name.split(":")
    components.append({"type": "library", "group": group, "name": artifact, "version": version,
                       "purl": f"pkg:maven/{quote(group)}/{quote(artifact)}@{quote(version)}"})
if not components:
    raise SystemExit("No runtime components were found; refusing empty SCA coverage")
report = {"bomFormat": "CycloneDX", "specVersion": "1.6", "version": 1,
          "metadata": {"timestamp": datetime.now(timezone.utc).isoformat(),
                       "component": {"type": "application", "name": args.module, "version": "0.2.0-p2"},
                       "properties": [{"name": "nusacred:lock-sha256", "value": hashlib.sha256(lock.read_bytes()).hexdigest()},
                                      {"name": "nusacred:configuration", "value": args.configuration},
                                      {"name": "nusacred:coverage", "value": "locked Maven runtime graph; not APK binary or build-tool coverage"}]},
          "components": components}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, indent=2) + "\n")
print(f"PASS: {len(components)} runtime coordinates exported for {args.module}:{args.configuration}")
