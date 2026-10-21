"""Compile the provider against the exact reviewed Keycloak runtime, without Maven resolution."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

p = argparse.ArgumentParser(__doc__)
p.add_argument('--image', required=True)
p.add_argument('--jdk', type=Path, required=True)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
assert re.fullmatch(r'sha256:[a-f0-9]{64}', a.image), 'Use the scanned immutable image ID'
root = Path(__file__).resolve().parents[1]
source = root / 'keycloak-recovery-provider/src/main'
a.output.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='keycloak-provider-') as tmp:
    work = Path(tmp)
    container = subprocess.check_output(['docker', 'create', a.image], text=True).strip()
    try:
        subprocess.run(['docker', 'cp', container + ':/opt/keycloak/lib/lib/main', str(work / 'lib')], check=True)
    finally:
        subprocess.run(['docker', 'rm', container], check=True, stdout=subprocess.DEVNULL)
    classes = work / 'classes'
    classes.mkdir()
    subprocess.run([str(a.jdk / 'bin/javac'), '--release', '21', '-cp', str(work / 'lib/*'),
                    '-d', str(classes), *map(str, sorted(source.rglob('*.java')))], check=True)
    jar = a.output / 'nusacred-recovery-provider.jar'
    subprocess.run([str(a.jdk / 'bin/jar'), '--create', '--file', str(jar),
                    '--date=2026-10-21T00:00:00Z', '-C', str(classes), '.',
                    '-C', str(source / 'resources'), '.'], check=True)
    manifest = {'schemaVersion': 1, 'keycloakImage': a.image,
                'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
                'sources': {str(f.relative_to(root)): hashlib.sha256(f.read_bytes()).hexdigest()
                            for f in sorted(source.rglob('*')) if f.is_file()},
                'compileClasspath': {f.name: hashlib.sha256(f.read_bytes()).hexdigest()
                                     for f in sorted((work / 'lib').glob('*.jar'))}}
    (a.output / 'provider-build.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print('PASS: provider compiled against immutable Keycloak image; source and classpath recorded')
