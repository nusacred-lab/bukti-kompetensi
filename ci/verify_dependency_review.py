"""Reject checksum, repository provenance or lock changes without a review ledger."""
import hashlib
import json
from pathlib import Path
import re
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
ledger = json.loads((root / "ci/dependency-review.json").read_text())
metadata = root / "gradle/verification-metadata.xml"
assert hashlib.sha256(metadata.read_bytes()).hexdigest() == ledger["metadataSha256"], "Unreviewed metadata"
ns = {"v": "https://schema.gradle.org/dependency-verification"}
tree = ET.parse(metadata)
assert tree.findtext("v:configuration/v:verify-metadata", namespaces=ns) == "true"
for forbidden in ("trusted-artifacts", "ignored-artifacts", "trusted-keys"):
    assert not tree.findall(".//v:" + forbidden, ns), "Broad trust rules are prohibited"
expected = {}
for component in tree.findall(".//v:component", ns):
    assert "SNAPSHOT" not in component.get("version")
    for artifact in component.findall("v:artifact", ns):
        hashes = artifact.findall("v:sha256", ns)
        assert len(hashes) == 1 and re.fullmatch("[a-f0-9]{64}", hashes[0].get("value"))
        key = (component.get("group"), component.get("name"), component.get("version"), artifact.get("name"))
        assert key not in expected
        expected[key] = hashes[0].get("value")
reviewed = {}
hosts = {"repo.maven.apache.org", "dl.google.com", "plugins.gradle.org", "github.com", "nodejs.org"}
methods = {"published-sha256", "download-sha256", "mapped-download-sha256", "inherited-reviewed-fork"}
for entry in ledger["artifacts"]:
    key = tuple(entry[field] for field in ("group", "name", "version", "artifact"))
    assert key not in reviewed and entry["method"] in methods
    source = urlsplit(entry["source"])
    assert source.scheme == "https" and source.hostname in hosts
    if entry["method"] == "inherited-reviewed-fork":
        assert entry["baselineCommit"] == "10068c7f5f1f94ffeb4e5198a24d6de1eb458bda"
    reviewed[key] = entry["sha256"]
assert reviewed == expected, "Every hash needs an exact review record"
lock_names = {"settings-gradle.lockfile", "custodian-backend/gradle.lockfile",
              "android-wallet/gradle.lockfile", "web-portals/gradle.lockfile",
              "backend-common/gradle.lockfile", "company-backend/gradle.lockfile",
              "issuer-backend/gradle.lockfile", "shared-contracts/gradle.lockfile", "integration-tests/gradle.lockfile"}
assert set(ledger["lockfiles"]) == lock_names
for name, digest in ledger["lockfiles"].items():
    assert hashlib.sha256((root / name).read_bytes()).hexdigest() == digest, "Unreviewed lockfile: " + name
print(f"PASS: {len(expected)} reviewed artifact hashes and {len(lock_names)} lockfiles")
