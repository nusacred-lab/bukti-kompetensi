"""Sign the exact CI-built unsigned APK offline, preserving the signing store locally.

First verify the unsigned APK's GitHub attestation against the expected workflow,
repository, commit and branch. Read its SHA-256 from that verified CI candidate.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def payload(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Duplicate APK ZIP entries')
        return {name: hashlib.sha256(archive.read(name)).hexdigest() for name in names}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--unsigned', type=Path, required=True)
    p.add_argument('--unsigned-sha256', required=True)
    p.add_argument('--source-commit', required=True)
    p.add_argument('--apksigner', type=Path, required=True)
    p.add_argument('--signing-directory', type=Path, required=True)
    p.add_argument('--assetlinks', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    if not re.fullmatch('[a-f0-9]{40}', a.source_commit) or not re.fullmatch('[a-f0-9]{64}', a.unsigned_sha256):
        p.error('Require full source commit and CI unsigned APK checksum')
    if digest(a.unsigned) != a.unsigned_sha256:
        p.error('Unsigned APK differs from the attested CI candidate')
    if 'runtime' not in a.output.resolve().parts:
        p.error('Keep release signing work in private runtime')
    a.output.mkdir(mode=0o700, parents=True, exist_ok=False)
    target = a.output / 'nusacred-wallet-0.6.0-p6.apk'
    subprocess.run([str(a.apksigner.resolve()), 'sign', '--ks', str(a.signing_directory.resolve() / 'wallet-candidate.p12'),
        '--ks-key-alias', 'wallet-candidate', '--ks-pass', 'file:' + str(a.signing_directory.resolve() / 'store-password'),
        '--v1-signing-enabled', 'false', '--out', str(target), str(a.unsigned.resolve())], check=True)
    verified = subprocess.check_output([str(a.apksigner.resolve()), 'verify', '--verbose', '--print-certs', str(target)], text=True)
    certs = set(re.findall(r'certificate SHA-256 digest:\s*([a-fA-F0-9]{64})', verified))
    if 'Number of signers: 1' not in verified or len(certs) != 1:
        raise ValueError('Require one verified APK signer')
    cert = certs.pop().upper()
    allowed = {s.replace(':', '').upper() for e in json.loads(a.assetlinks.read_text())
        if e['target'].get('package_name') == 'id.nusacred.wallet' for s in e['target']['sha256_cert_fingerprints']}
    if cert not in allowed:
        raise ValueError('APK signing identity does not match App Links')
    if payload(target) != payload(a.unsigned):
        raise ValueError('Signing changed APK payload; rebuilding or repackaging is forbidden')
    record = dict(schemaVersion=1, applicationId='id.nusacred.wallet', versionCode=5, versionName='0.6.0-p6',
        sourceRevision=a.source_commit, unsignedSha256=a.unsigned_sha256, signedSha256=digest(target), certificateSha256=cert,
        payloadUnchanged=True, signing='offline-apksigner-v2-v3', scope='synthetic-isolated-demo-only')
    (a.output / 'apk-signing.json').write_text(json.dumps(record, indent=2) + '\n')
    (a.output / 'SHA256SUMS').write_text(record['signedSha256'] + '  ' + target.name + '\n')
    print('PASS: exact CI APK signed offline; payload and App Links signer verified')


if __name__ == '__main__':
    main()
