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
out of scope. A single demo VM, external backups, and tested restoration are
required before opening the demo.

Local check: `python3 ci/check_policy.py`. CI also scans the full Git history
and reviews dependency changes on pull requests. See SECURITY-PROJECT.md.
Project code: Apache-2.0 (LICENSE). No official affiliation with walt.id.

All repository work must use English; see [AGENTS.md](AGENTS.md).
