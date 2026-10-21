# P2 persistent access and recovery proof

The custodian uses a separate SQLite registry. Keycloak 26.8.1 uses PostgreSQL
18.6. Wallet2 uses the pinned `nusacred-lab/waltid-identity` fork at
`10068c7f5f1f94ffeb4e5198a24d6de1eb458bda`, its own persistent database, and fresh
synthetic keys. All server components run in containers. This is a prerelease
proof, not a production acceptance claim.

## Access registry

`AccessRegistry` owns versioned schema initialization and migration, foreign keys, WAL, full
synchronous commits, and the unique `(issuer, subject)` account constraint. Email
never selects an account. A unique account binding reserves its operation before
Wallet2 is called. Named key and credential stores carry the durable operation
reference. An uncertain result enters `RECONCILE_REQUIRED`; retry never creates a
second wallet. Reconciliation reads existing references and requires exactly one
matching wallet and holder key. Missing or ambiguous references remain closed;
key/credential deletion is never a recovery step.

Application session tokens are opaque and only their hashes, IdP session IDs,
account revisions, expiry and revocation are persisted. IdP access tokens remain
memory-only. Startup revokes previous application sessions. A process restart
requires sign-in and preserves account identity, wallet, key and credential.

One custodian process owns the registry. Its monitor serializes sensitive reads,
provisioning, consent dispatch and recovery. Network calls do not hold SQLite
transactions. A consent binds the account/session/revision, original request URL,
wallet/key, credential/query selection, displayed disclosures and preview hash.
It is single-use and expires. Recovery revokes sessions and cancels pending
consents. A request already dispatched upstream cannot be recalled; an ambiguous
dispatch stays `RECONCILE_REQUIRED`.

## Keycloak guard and transaction boundary

`GuardedUpdatePassword` replaces `UPDATE_PASSWORD` with factory priority 100.
Keycloak validates the email action token or authenticated required-action
context before entering it. The provider:

1. Acquires a PostgreSQL transaction-scoped advisory lock for the realm/subject.
   This serializes guarded password writes across browser flows. The query has
   a ten-second timeout. PostgreSQL releases the lock on commit, rollback or crash.
2. Sends an HMAC-authenticated `begin` request and waits for a durable registry
   acknowledgement. The account revision increments and access becomes `LOCKED`
   before Keycloak attempts the password write. No acknowledgement means no write.
3. Uses Keycloak's password validation/update, removes the user's IdP sessions,
   and stores an administrator-only commit marker in that same IdP transaction.
   The reset flow ends and requires a separate fresh login.
4. Sends `complete` only after the IdP transaction commits. Completion permits
   `AWAITING_FRESH_LOGIN`; it does not itself grant wallet access.

The databases are independent. The guard intentionally holds its per-account
PostgreSQL transaction lock across a bounded coordinator request; the application
SQLite transaction is already committed before acknowledgement. Missing or lost
completion leaves the registry closed. An offline operator can reconcile only a
matching committed IdP marker, after explicitly revoking IdP sessions again.
No Keycloak administrative credential is mounted into a runtime backend.

A new validated flow may supersede an abandoned recovery only while holding the
IdP write fence. It obtains a newer registry revision. An already known older
operation or completion cannot supersede or reopen the newer revision. Retrying
the same validated flow uses the same operation identifier even after rollback.

HMAC binds timestamp, endpoint and exact body, with a 30-second clock window.
Replay of a completed or superseded operation never opens access. The dedicated
secret is mounted from private runtime, not source. Only fixed internal service
origins are permitted. The optional fault proxy drops completion acknowledgements
and has no public listener.

## Fresh authentication and password paths

Every custodian application session registration and use checks the persistent gate and
account revision. IdP issuer/audience/nonce/signature/role and live introspection
checks also remain mandatory. Authentication after the completion second is fresh.
When `auth_time` equals that second, access additionally requires the server's
nonce-bound login flow to have started strictly after the durable millisecond
completion boundary, within that same second. OIDC verifies the nonce, forces
interactive login, rejects future authentication times and introspects the IdP
session before registration. A flow started before or at completion cannot reopen
the gate, including after another valid login opens it. A pre-existing ID token
cannot bind itself to a new nonce. Unknown or ambiguous same-second flows fail
closed. Both services share the host clock.

Registry schema 2 adds the millisecond flow boundary transactionally. Migration
from schema 1 retains its known recovery boundary at the end of the recorded
second, so historical timestamp uncertainty never creates access. Wallet/key/
credential bindings survive migration. Restart still revokes application sessions.

Account/account-console clients and direct password/service-account grants are
disabled. Email reset and authenticated required actions use the guard.
Application-role tokens cannot call administrative credential APIs. The private
host bootstrap operator remains a trusted offline administrative boundary;
its credential must never be copied to the application, APK or public ingress.
Unmanaged profile attributes are disabled and the commit marker is admin-only.

An anonymous forgotten-password request sends the standard response and does not
invoke the gate. Invalid or expired email tokens cannot lock an account. Public
recovery/credential routing uses the separately approved infrastructure override,
activated for P2 testing on 21 October 2026. Thirty public ingress checks and ten
public HTTPS recovery checks passed, including retained-credential presentation
accepted by the verifier. Physical APK acceptance remains separate.

## Reproduction and evidence

Use the companion platform-infra scripts with an ignored private runtime:

- `configure_p2_login.py`: exact clients/audiences, candidate HTTPS callback and
  synthetic role fixtures.
- `configure_p2_recovery.py`: restricted password paths and protected commit marker.
- `run_p2_stack.py`: compose the six private overrides and wait for actual listeners;
  `--mode lost-completion` adds only the disposable fault proxy.
- `test_p2_recovery.py`: real email reset, two old sessions/refresh tokens, consent,
  retained credential and actual verifier acceptance; optional required-action,
  abandoned-flow and lost-completion variants.
- `test_p2_password_paths.py`, `test_p2_recovery_unavailable.py`,
  `test_p2_recovery_concurrency.py`, `test_p2_wallet_restart.py`: additional private
  integration proofs. Raw pages, email links, passwords and tokens stay private.
- `RealWalletReconciliationProof`: a one-shot test-class runner against actual
  Wallet2, with a lost result injected after its commit and before registry
  acknowledgement. It is excluded from the application distribution.

Build the provider with `ci/build_keycloak_provider.py --image <reviewed-local-image-id>
--jdk <jdk21-directory> --output <private-runtime-directory>`. It extracts compile
JARs from that immutable scanned image without starting it, records each classpath
hash and source hash, and produces a deterministic provider JAR. No Maven
repository resolution or broad trust exception is introduced.

The signed candidate uses package `id.nusacred.wallet`, HTTPS App Links at
`wallet-p2.demo.inoerawan.id`, CameraX 1.6.2 and ZXing 3.5.4. Link context is encrypted
with Android Keystore, expires after four minutes and requires explicit review
and consent. Logout removes context and session. QR frames never leave the phone.
The signing key/password and local backup remain in ignored private runtime.

Physical candidate evidence on 21 October 2026 includes Android domain verification,
actual HTTPS callback login through the system browser with PKCE, pending-context
retention without automatic consent, and explicit disclosure review/presentation
of the retained synthetic demo credential. The isolated verifier returned
`SUCCESSFUL`. Early phone login attempts failed or stalled; a fresh browser flow
later passed. The early transport cause remains unconfirmed. Safe APK diagnostics
log only exception class or HTTP status, never URLs, tokens or account data.

The synthetic recovery email link was opened on the physical phone and a new
password submitted there. The old native session was denied. Only the P2 candidate
was then uninstalled and freshly installed, removing local application data and
Keystore entries. The same account logged in through the HTTPS callback and
presented its unchanged retained credential after explicit review/consent; the
verifier returned `SUCCESSFUL`. Server-side credential bytes match the pre-recovery
fingerprint. A real Chrome button opened the signed APK with the same HTTPS context
encoded in the QR, without automatic consent. The test button uses Chrome's
`intent:` syntax with HTTPS and no package override, allowing Android to resolve
the verified domain.

The camera preview uses CameraX's compatible TextureView mode to avoid a SurfaceView
overlay covering sibling controls in the scrolling Compose layout. See the
[official CameraX implementation-mode contract](https://developer.android.com/reference/androidx/camera/view/PreviewView.ImplementationMode).
On 22 October 2026, the human positioned the phone and confirmed recognition.
The supported optical-decoder event was observed after a baseline with no saved
request. The signed APK showed the credential sent, and the exact fresh verifier
session shared with the tested browser button returned `SUCCESSFUL`. No automated
link/frame was injected during scanning, and the later inspection script stopped
before any review/send action because the human had already completed it.

G2 passed for the synthetic P2 prerelease proof on 22 October 2026. This includes
physical optical scanning and presentation, exact browser-context matching,
HTTPS callback/context retention, fresh-install recovery and the recovery/isolation
matrix. This does not promote the build to production or close later project gates.

## Implementation references

- [Keycloak 26.8.1 UPDATE_PASSWORD implementation](https://github.com/keycloak/keycloak/blob/26.8.1/services/src/main/java/org/keycloak/authentication/requiredactions/UpdatePassword.java).
- [Keycloak 26.8.1 JPA connection provider](https://github.com/keycloak/keycloak/blob/26.8.1/model/jpa/src/main/java/org/keycloak/connections/jpa/JpaConnectionProvider.java).
- [PostgreSQL 18 advisory-lock semantics](https://www.postgresql.org/docs/18/functions-admin.html#FUNCTIONS-ADVISORY-LOCKS).

The provider is PostgreSQL-specific; changing the IdP database or Keycloak version
requires repeating the guard, fencing and recovery proofs.


## Scan sessions and Android restoration

The P2 native client uses a bounded 900-second access-token lifetime; portal
clients retain the realm default. Live IdP introspection and the registry gate
still reject recovery/revocation immediately. There is no persisted IdP refresh
token and no automatic extension of this session. `/auth/me` exposes its actual
`expires_at`; the APK encrypts that expiry alongside its opaque session. Legacy
local session records require a fresh login when the APK is updated.

Expiry updates the UI without closing an active camera or losing a still-valid
four-minute request context. A recognized QR explicitly tells the user to review
or sign in. Optical decoding diagnostics record only a supported/unsupported
event, never payloads, frames or account data. A consumed launch Intent is cleared;
Activity restoration uses the encrypted current context instead of replaying an
older launch link over a later scan. An invalid wallet request is checked against
`/auth/me` before treating it as session revocation, so a request failure cannot
silently sign out an otherwise valid account. Recovery and expired IdP sessions
remain denied by the server.


Physical timeout regression also passed with a deliberately temporary 30-second
native session: the camera stayed open, the unexpired request survived, expiry
was visible, and the server denied the expired session. The client was restored
to 900 seconds in a finally block and normal login/presentation passed. A rejected
request left a valid account signed in. Actual rotation recreated the Activity
and retained the newer request over its old launch Intent; the original device
rotation policy was restored. These tests used the final signed candidate.
