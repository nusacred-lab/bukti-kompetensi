# P6 release contract

The infrastructure repository coordinates immutable application/fork commits on
GitHub-hosted runners. CI builds the release APK once, plus all three application
images with portal assets. No signing store or password enters GitHub.

The unsigned APK receives a GitHub build attestation. Before offline signing,
verify it with `gh attestation verify`, pinning the infrastructure repository,
release workflow, workflow commit and branch. Read the expected unsigned SHA-256
from the verified candidate record. Run `python3 ci/package_p6_release.py --help`
for offline signing. It rejects changes to the unsigned checksum, ZIP payload and
App Links signing certificate. Preserve the previous signing identity for APK
updates; version code 5 identifies P6.

Images are scanned by immutable identity, exported once and pushed without
rebuilding. The manifest binds source commits, registry digests, SBOMs, provenance
and the unsigned APK. Offline signing binds that attested APK to the final signed
APK and public certificate. Include both the signed APK and signing record in G6
evidence; CI success alone does not close G6.

The Compose package is maintained in `nusacred-lab/platform-infra/release`. The
installation host needs Docker Compose, Python 3 and OpenSSL, without Gradle, a
JDK, an Android SDK or an application/fork checkout. Install the APK separately.
Public networking, external backups, operator alerts and participant access
remain G7 acceptance work.
