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
G2 passed for the synthetic prerelease demo on 22 October 2026; see the workspace P2 validation index for the final optical QR and recovery evidence. Earlier P2 documents retain their dated execution history. See [persistent recovery proof](docs/P2-recovery-proof.md),
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

## P3 application foundation

The application now includes `shared-contracts` (JVM/Wasm, also consumed by
Android), `backend-common`, separate `issuer-backend` and `company-backend`
distributions, and `integration-tests`. The existing custodian registry/recovery
boundary remains in `custodian-backend`; OIDC/BFF code is shared through an
explicit recovery-registry interface. The Keycloak SPI remains independently
packaged as proven in P2.

See [the API contract](docs/openapi.json) and [P3 validation](docs/P3-foundation.md)
for ownership, role mapping, request limits, state transitions, migration and
reproduction. Stable business wire enums use English. P2 diagnostic paths remain
compatibility aliases for the proven candidate APK. Issuance/presentation
reservations do not claim a completed P4 business flow.

## P4 credential business services

The P4 branch connects authorized assessments/offers, recipient-bound receiving,
job verification sessions, explicit credential selection and persistent trust
policy decisions to real private fork services. See [P4 behavior, migrations and
validation](docs/P4-credential-business.md). API contract, recovery isolation and
strict dependency verification remain enforced. Final portal/APK journeys and
public deployment retain their P5–P8 acceptance gates.

## P7 custodian participant invitations

The custodian portal separates Service overview (registry and wallet readiness),
Recovery & access (recovery gates and uncertain operations), and Participant
invitations (approved email, recipient confirmation and delivery request status).
Participants register through a 15-minute email link, then use the Android wallet.
Public self-registration remains disabled. Only a signed-in wallet administrator
can list or request invitations. Browser writes require exact Origin and CSRF;
durable recipient/request reservations prevent duplicate sends after retries or
restart. An ambiguous outcome requires operator reconciliation.

Deployment requires a newly verified application release and private
`WALLET_INVITATION_DB_PATH` / `WALLET_INVITATION_CLIENT_SECRET_FILE` configuration;
these features are disabled when no directory client is configured. The additive
SQLite journal belongs in the existing private custodian volume and backup
lifecycle. Existing wallet registry schema, signing keys and credentials are
unchanged. The companion infrastructure repository documents dedicated client
provisioning in `operations/README.md`. Source checks do not establish public
rollout, browser rendering or SMTP inbox acceptance.
