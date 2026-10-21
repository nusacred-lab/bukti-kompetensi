# Digital Competency Proof

A synthetic-data demo of competency proof for an electrical installation
technician application. The issuer, custodial wallet, and verifier use the real
walt.id protocol engine. Target stack: custodial Android APK, Compose Web/Wasm
portals, Kotlin/Ktor/JDK 21 backend, Keycloak/PostgreSQL, and JDBC/SQLite.
Holder keys and credentials remain on the server.

P2 now includes three role-separated OIDC BFFs, native AppAuth/PKCE, a persistent
account-to-wallet registry and guarded password recovery. Private real-service
proofs cover revocation, concurrency, interrupted recovery, retained credentials
and actual verifier acceptance. A signed Android candidate includes QR scanning
and verified App Links. Physical credential/recovery journeys remain to be closed;
G2 is still open. See [persistent recovery proof](docs/P2-recovery-proof.md),
[login evidence](docs/P2-login-proof.md) and [initial stack proof](docs/P2-stack-proof.md).
Fork supply-chain acceptance is tracked in
[waltid-identity](https://github.com/nusacred-lab/waltid-identity).

Initial scope: one issuer, one company, one job opening, and synthetic data.
CVs, full recruitment, credential revocation, and multiple issuers/companies are
out of scope. Runtime will use one local Linux x86_64 host with Docker Compose,
Caddy and Pinggy Pro TLS passthrough. Primary builds run in GitHub Actions;
encrypted off-host backups and tested restoration remain required before opening
the demo. Cloud VM procurement is not a prerequisite.

Environment update, 21 October 2026: public HTTPS probes under
`{issuer,wallet,auth,verifier}.demo.inoerawan.id` passed and the owner confirmed
phone access. P2 uses separate prerelease routing, realm, keys and databases.
Public prerelease login and the Android certificate association are active;
credential/recovery testing was explicitly approved and is active. P2/G2 passed
for the synthetic prerelease on 22 October 2026, including physical scan,
recovery and fresh-install presentation. Host
availability, tunnel lifecycle, monitoring, certificate renewal and off-host
backup remain final-demo requirements. The companion `nusacred-lab/platform-infra`
repository maintains `docs/local-demo.md` and `docs/P2-recovery-proof.md`.

Local check: `python3 ci/check_policy.py`. CI also scans the full Git history
and reviews dependency changes on pull requests. See SECURITY-PROJECT.md.
Project code: Apache-2.0 (LICENSE). No official affiliation with walt.id.

All repository work must use English; see [AGENTS.md](AGENTS.md).
