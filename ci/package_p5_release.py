"""Package an already signed P5 APK and source-bound checksum manifest for a draft release.

The signing store and passwords are never copied. Run from a clean committed checkout
and pass the exact APK just built with the private release signing configuration.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
from datetime import datetime, timezone

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--apk', type=Path, required=True)
p.add_argument('--apksigner', type=Path, required=True)
p.add_argument('--assetlinks', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
root = Path(__file__).resolve().parents[1]
assert not subprocess.check_output(['git', 'status', '--porcelain'], cwd=root).strip(), 'Commit the reviewed source before packaging'
revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
verified = subprocess.check_output([str(a.apksigner.resolve()), 'verify', '--verbose', '--print-certs', str(a.apk.resolve())], text=True)
fingerprint = re.search(r'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)', verified).group(1).upper()
links = json.loads(a.assetlinks.read_text())
entry = next(v for v in links if v['target']['package_name'] == 'id.nusacred.wallet')
assert fingerprint in {s.replace(':', '').upper() for s in entry['target']['sha256_cert_fingerprints']}, 'APK certificate does not match App Links'
assert 'runtime' in a.output.resolve().parts, 'Keep release preparation in the private runtime'
a.output.mkdir(parents=True, exist_ok=False, mode=0o700)
target = a.output/'nusacred-wallet-0.5.0-p5.apk'
shutil.copyfile(a.apk, target)
digest = hashlib.sha256(target.read_bytes()).hexdigest()
(a.output/'SHA256SUMS').write_text(digest+'  '+target.name+'\n')
manifest = {'schemaVersion': 1, 'kind': 'local-signed-preview', 'sourceRepository': 'nusacred-lab/competency-proof',
            'sourceRevision': revision, 'createdAt': datetime.now(timezone.utc).isoformat(),
            'apk': {'name': target.name, 'sha256': digest, 'certificateSha256': fingerprint,
                    'applicationId': 'id.nusacred.wallet', 'versionName': '0.5.0-p5', 'versionCode': 2},
            'limits': ['Synthetic prerelease candidate', 'Local build manifest; not a CI attestation',
                       'P6 supply-chain release acceptance remains separate']}
(a.output/'build-manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
print('Signed APK, App Links fingerprint and checksums verified at source '+revision)
