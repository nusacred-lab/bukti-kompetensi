# Digital Competency Proof

A synthetic-data demo of competency proof for an electrical installation
technician application. The issuer, custodial wallet, and verifier use the real
walt.id protocol engine. Target stack: custodial Android APK, Compose Web/Wasm
portals, Kotlin/Ktor/JDK 21 backend, Keycloak/PostgreSQL, and Exposed/SQLite.
Holder keys and credentials remain on the server.

P1 status: repository and CI foundations; the release application has not been
built. This is not yet a runnable application quickstart. Fork builds and
supply-chain acceptance are tracked in
[waltid-identity](https://github.com/nusacred-lab/waltid-identity).

Initial scope: one issuer, one company, one job opening, and synthetic data.
CVs, full recruitment, credential revocation, and multiple issuers/companies are
out of scope. Runtime will use one local Linux x86_64 host with Docker Compose,
Caddy and Pinggy Pro TLS passthrough. Primary builds run in GitHub Actions;
encrypted off-host backups and tested restoration remain required before opening
the demo. Cloud VM procurement is not a prerequisite.

Environment update, 21 October 2026: public HTTPS probes under
`{issuer,wallet,auth,verifier}.demo.inoerawan.id` passed and the owner confirmed
phone access. This foundation is sufficient to start P2; the APK, Keycloak,
recovery and full application acceptance remain unimplemented/unproven. Prerelease
application tests will use separate `*-p2.demo.inoerawan.id` routing and independent
realm/key/database state. Host availability, tunnel lifecycle, monitoring and
manual certificate renewal must be operated before final-demo opening.
The environment runbook is maintained as `docs/local-demo.md` in
`nusacred-lab/platform-infra`. These documentation changes are still local.

Local check: `python3 ci/check_policy.py`. CI also scans the full Git history
and reviews dependency changes on pull requests. See SECURITY-PROJECT.md.
Project code: Apache-2.0 (LICENSE). No official affiliation with walt.id.

All repository work must use English; see [AGENTS.md](AGENTS.md).
