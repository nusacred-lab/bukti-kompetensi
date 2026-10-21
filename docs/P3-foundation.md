# P3 application foundation — 22 October 2026

Owner: abeinoe, assisted by Codex. This change builds on accepted synthetic-demo
P2/G2 source, without merging or promoting the P2/P3 branches to production.
P3 local acceptance passed; required remote checks and draft PR references are
recorded in the workspace validation index. No independent two-person review is
claimed. Credential business-flow acceptance remains P4.

## Implementation and boundaries

| Task | Implementation and evidence |
| --- | --- |
| P3.1 | Added JVM/Wasm `shared-contracts`, `backend-common`, separate issuer/company JVM applications and `integration-tests`. Android and Web/Wasm consume the shared contract. Existing OIDC/BFF behavior is extracted through `OidcRegistry`; the P2 recovery implementation remains in the custodian. No client has a private holder key or Wallet2 auth account. Historical Python harness remains reproducible. |
| P3.2 | `openapi.json` specifies 21 application operations plus two private recovery operations, DTOs, English status/reason values, ownership, roles, auth, request limits, TTL and errors. `check_api_contract.py` resolves schema references and checks ownership/auth metadata. JVM contract tests compare the DTO fields used by both clients with the OpenAPI definitions and exercise real Ktor browser requests. The receiving adapter is explicitly marked P4. |
| P3.3 | Custodian schema 3 persists previews, consent idempotency and audit. Ownership binds account/session/revision; the preview hash binds request URL, wallet/key reference, credential, disclosures and recipient. Selected inputs are compared again before dispatch. Lost responses become `RECONCILE_REQUIRED`; retries never resend. New per-service application stores have transactional, checksummed migrations, constraints, audit, actor/payload-scoped idempotency, job snapshots, trust revisions and immutable final-result rows. |
| P3.4 | Explicit mapping covers candidate, issuer admin/operator, wallet admin and company admin. Privileged organization membership comes from a server-only subject-to-organization mount, never a request field or user-editable profile. Public job readers cannot enumerate results. Viewer cookies authorize only their own result sessions; a company admin can read results only for its assigned organization. Real Keycloak tests prove assigned-admin mutation, applicant denial and unassigned-admin denial on the new issuer/company binaries. |
| P3.5 | Exact origin and CSRF checks for browser mutations, separate host-only secure/HttpOnly cookies, bounded rate buckets, 32 KiB JSON bodies, bounded paging for trust entries, sensitive-response no-store and sanitized error/correlation envelopes. Server adapters keep fixed private origins, no redirects, timeouts and bounded responses. The existing presentation-reference allowlist is retained; arbitrary host/userinfo/metadata URLs are rejected. New infra mounts/databases are private and add no host ports or public routes. |

The persistence layer deliberately retains the reviewed SQLite JDBC dependency
proven in P2. An additional ORM is not introduced merely to create migrations.
The store supports one writer per service; it is not a multi-replica database.
Protocol dispatch occurs outside SQL transactions. The custodian monitor still
serializes dispatch authorization with recovery, preserving the P2 safety boundary.

A P3 offer or verification-session response reserves durable work; it is not a
successful issuance/presentation response. The company finalization primitive is
internal to the storage module and has no browser endpoint. P4 must supply actual
verified claims, full VC/VP policies, recipient validation and upstream adapters
before any business-credential acceptance is claimed. Decline is a local terminal
state; this phase does not claim an upstream cancellation signal.

Credential-list responses remain the bounded Wallet2 metadata array for P2
compatibility (upstream response cap 2 MiB), with no pagination parameter. Trust
lists use offset 0–10000, limit 1–100, default 25, and a nullable next offset.
Poll with backoff, starting at two seconds and capped at 15 seconds. Preview TTL
is 240 seconds, below the 300-second context maximum; app/browser-viewer sessions
are at most 1800 seconds and OIDC expiry can shorten app sessions. P2 native access
retains its tested 900-second IdP limit. Completed/declined/expired results cannot
be revived by retries or late callbacks.

## Local validation

- 30 JVM tests passed: shared state machine (1), common storage/security (10),
  custodian registry/recovery/reference regressions (17), browser/DTO contracts (2).
- Strict offline dependency verification passed for all three JVM distributions,
  Android debug APK, Android lint and production Web/Wasm distribution. Android
  lint has zero errors, 13 warnings and one hint; no warning suppression was added.
- 1293 reviewed artifact hashes and nine lockfiles passed the review ledger.
  All resolved module coordinates were already reviewed; verification metadata,
  repositories and artifact versions are unchanged.
- Trivy scanned the actual runtime JAR directories of all three applications,
  each detecting 49 packages and zero High/Critical findings. Empty scan coverage
  remains a hard failure. Final-image/release acceptance remains P6/P7.
- Gitleaks source scan, repository policy and diff checks passed. Public evidence
  excludes credentials, tokens, reset links, databases and full credential bytes.
- The optional P3/P2 Compose composition passed configuration validation with
  synthetic placeholders; no active ingress or container was changed.
- `platform-infra/scripts/test_p3_backends.py` starts disposable loopback JVM
  processes, validates real Keycloak login over verified TLS and tests mutation,
  replay/conflict, origin, CSRF, viewer ownership and missing organization. It
  terminates its processes after the proof. It does not change passwords, wallet
  state, active containers or realm configuration. Temporary IdP login sessions
  are normal test sessions, not a new public deployment.

`FoundationTest` also synchronizes concurrent trust mutation/evaluation and checks
that the saved outcome agrees with its saved revision. Migration tests reject
edited history, roll back invalid SQL and preserve P2 wallet/key references while
upgrading schema 1. Registry restart invalidates old app sessions; it never restores
authorization just because a preview is durable. Ambiguous operations stay closed.

## Reproduce

Use the pinned P2 JDK/Android SDK and reviewed Gradle cache. From this repository:

```sh
python3 ci/check_policy.py
python3 ci/verify_dependency_review.py
python3 ci/check_api_contract.py
./gradlew :shared-contracts:jvmTest :backend-common:test :integration-tests:test :custodian-backend:test :custodian-backend:installDist :issuer-backend:installDist :company-backend:installDist :android-wallet:assembleDebug :android-wallet:lintDebug :web-portals:wasmJsBrowserDistribution --dependency-verification strict --no-daemon
```

For real identity testing, use the infrastructure script with the existing ignored
P2 runtime, application checkout and pinned JDK directory. Its output contains
only sanitized assertions; process logs and disposable databases stay private.
Organization-assignment files contain `{subject: organization_id}` entries and are
loaded once at startup. Restart the backend after an assignment change to revoke
its old in-memory sessions. Missing assignments fail closed. See the infra
`docs/P3-backends.md` for mount and rollback instructions.

Schema 2 binaries reject schema 3. Back up the registry before upgrading; rollback
requires the matching binary and consistent pre-migration database backup. Do not
start an old binary on a migrated volume. Public domain/App Links, candidate APK
replacement, complete portal journeys and restore/operations remain their planned
P4–P8 acceptance work.

Framework test behavior follows [Ktor 3.6 testing](https://ktor.io/docs/server-testing.html).
The shared module follows [Kotlin platform sharing](https://kotlinlang.org/docs/multiplatform-share-on-platforms.html).
