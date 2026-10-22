# P5 demonstration experience

Three independently operated organizations use shared accessible components without
sharing a corporate identity. LSP Nusantara (LSP Teknik Ketenagalistrikan Nusantara,
demo) is the purple credential issuer; NusaCred Wallet (PT Dompet Identitas Nusantara,
demo) is the teal custodial wallet provider; Karya Instalasi (PT Karya Instalasi
Nusantara, demo) is the copper/navy employer and verifier. Each portal shows its own
wordmark, role, legal organization, browser title and explanation of independent
responsibilities. Login and recovery pages identify the owning organization as
their authentication operator. Each organization has its own Keycloak runtime,
database, bootstrap administrator, realm, signing keys, business client and secret.
Users and browser sessions are not federated between organizations. Identity
labels accompany backend authorization and cryptographic trust, which remain
mandatory. The UI reads real BFF data; counts and outcomes are never fabricated.

Entity domains are `lsp-nusantara.demo.inoerawan.id`,
`dompet-identitas.demo.inoerawan.id`, `karya-instalasi.demo.inoerawan.id` and
`login-dompet-identitas.demo.inoerawan.id`. Staff login uses
`login-lsp-nusantara.demo.inoerawan.id/realms/lsp-nusantara` and
`login-karya-instalasi.demo.inoerawan.id/realms/karya-instalasi`.
The wallet retains its existing `competency-proof-p2` realm and issuer so the APK,
account registry and biometric refresh policy remain compatible. Staff accounts
are moved out of the wallet realm with their original subjects and password
hashes, preserving business organization assignments. The wallet realm no longer
contains staff business clients or roles. Staff recovery is local to its own IdP;
only wallet recovery uses the custodial recovery coordinator. Public identity
routes remain explicitly allowlisted and administrative routes remain private.
Legacy public entry points redirect to
their respective entity. The signed APK uses the new exact wallet/App Link and IdP
origins (versionCode 4, retained certificate). The private credential-protocol
identities and issuer/holder keys do not change. Changing the IdP URL requires the
reviewed offline registry migration; preserve account IDs, subjects and wallet/key
bindings, revoke old sessions and cancel old pending consent before reopening
access. It requires a fresh interactive login, not a newly provisioned wallet.

## Wallet

Sign in, view credentials, open a credential detail, or scan an offer/request. A
wallet App Link carries a short-lived reference; receiving and presentation both
require a separate review and approval. The review names the recipient, selected
credential, shared information and expiry. A different credential creates a new
bound preview. An expired, revoked or cancelled review cannot authorize a send.

The protocol preview describes mandatory claims as objects. The custodian maps
their exact paths to the native contract's string list and binds the full claim
descriptors into the preview hash. Consent must return the same paths; unsupported
selective or optional claims fail closed for this fixed competency profile.

Access and ID tokens remain ephemeral. After the initial browser/PKCE login, the
app may retain a rotating refresh token only when the user enables strong device
biometrics. A per-use biometric Android Keystore key wraps a random 256-bit vault
key; the vault encrypts the refresh token, previous opaque session and deadlines
with AES-GCM. Authenticated vault updates rotate the token without another prompt
while the app remains unlocked. No refresh token is written under the legacy
non-biometric session key. Backgrounding the protected wallet drops its in-memory
vault key and identity; returning requires the system biometric prompt. Device PIN
is not silently substituted for the strong-biometric key; secure OIDC sign-in is
the fallback. Changed enrollment or a missing Keystore key requires fresh login.

The approved policy is a five-minute access token, 24-hour refresh inactivity limit
and seven-day absolute login maximum. Offline access is not requested. The app
consumes its saved refresh token before dispatch, persists the rotated replacement
only after success, and never blindly retries a token after a lost response.
`POST /api/v1/sessions/renew` accepts refreshed access/ID proofs only for the same
subject, IdP sid, original authentication time and recovery revision. It rotates
the opaque session and atomically transfers existing reviews, operation outcomes and
idempotency records only within that same IdP login. Renewal never approves or
dispatches an operation; the APK retrieves its safe status/review. A reset, revoked session,
logout or backend restart cannot be bypassed by refresh. Backend renewal anchors
are bounded and memory-only; restart deliberately requires a new interactive login.

Credential contents and holder signing keys never persist in the APK. Backup and
device-transfer rules exclude all app data. Copied ciphertext cannot authenticate
without the original Keystore key and user biometric. Logout erases the vault,
wrapping key, opaque session, context and operation, and attempts server-side session
and refresh-token revocation. Offline logout cannot promise immediate remote
revocation; local erasure is immediate and IdP deadlines remain enforced.

A restored activity checks an existing operation and retrieves an owned pending
review without approving it. An ambiguous operation exposes a status check and is
never resubmitted automatically. Sign-in recovery uses the guarded identity login
flow. It does not bypass the coordinated recovery provider or expose account-console
password paths.

## Portals

- Issuer: organization-scoped assessment input, recipient binding, offer state and
  an expiring QR/App Link for the addressed wallet account.
- Custodian: aggregate wallet/recovery/uncertain-operation status and recovery
  guidance, restricted to the wallet administrator. No key export or applicant list.
- Company: job requirements, trust revisions and organization-owned verification
  records. The public page displays jobs and only the current visitor's result.

Each portal uses its own BFF, HttpOnly cookie, role mapping and CSRF check. Applicant
results cannot be enumerated from the public page. Only a selected verification
record polls the protocol service. The immutable business decision is explained in
use-case language; a valid credential can still fail the role requirements.

The public QR endpoint returns a bit matrix generated by the reviewed ZXing library.
It contains the same verified Android App Link as the button. The vector rendering
includes a quiet zone. The web fallback explains how to continue in the installed
wallet without transmitting a credential or approving consent.

## Build and prepare a release

Use the pinned toolchain and strict dependency verification. Build the web production
bundle and three JVM distributions, then package the deploy Containerfiles. The
Docker build context permits only built distributions, web assets and the license.

For the APK, set `P2_SIGNING_DIRECTORY` to the private directory containing the
existing `wallet-candidate.p12` and `store-password`; then run
`:android-wallet:assembleRelease :android-wallet:lintRelease`. Keep the original
signing certificate to update an installed candidate and preserve verified links.
Never commit or upload the keystore, password, session records or private runtime.

After committing the reviewed source and rebuilding, run `ci/package_p5_release.py`
with the signed APK, Android SDK `apksigner`, infrastructure `p2/assetlinks.json` and
a new private runtime output directory. It verifies the signature/fingerprint and
produces an APK, `SHA256SUMS` and source-bound `build-manifest.json` for a draft GitHub
prerelease. The local manifest is explicitly not a CI attestation; the full release
pipeline and acceptance remain P6 work.

G5 requires actual browser and physical Android journeys, including optical QR,
phone links, lifecycle, logout and backup/restore. JVM/API tests alone cannot close
this gate. Record observed results and any pending physical interaction separately.


## Initial page delivery

The initial HTML includes an accessible loading screen before WebAssembly starts.
A small public bootstrap script applies the destination entity branding, keeps the
screen visible until Compose publishes its mounted workspace identity, and removes
it after two animation frames. Slow/offline/script failures show a generic retry
message without printing error objects or account data. Retry reloads the page; it
does not approve or resubmit a business operation. Reduced-motion settings are
respected and the initial HTML also explains disabled JavaScript.

Image packaging fingerprints both public JavaScript files with their SHA-256
content digest and updates the HTML references. WebAssembly retains its existing
webpack content hashes. The public edge compresses only these exact versioned
assets and permits immutable caching only for successful GET/HEAD responses.
HTML, APIs, login, credential responses, errors and unversioned paths retain
`no-store`. No service worker or browser storage of personal data is introduced.
Existing compiled JVM/Wasm outputs can be reused when only these public resources
change; normal CI still compiles the full project before image packaging.

## Public issuer discovery and company approval

The LSP publishes `/?view=issuer-profile` and anonymous `GET /api/v1/issuer-profile`.
Its public DID comes from the issuer's existing public identity file; private key
configuration is never mounted into the portal backend. The profile explicitly
states that it is self-published and synthetic, with no real accreditation claim.
The wallet provider neither publishes this profile nor chooses company trust.

In the company workspace, **Trusted issuers → Review issuer** retrieves the LSP
publication from a configured exact HTTPS endpoint. The company backend validates
TLS, rejects redirects and arbitrary source URLs, and bounds the response. A review
is stored for 15 minutes and bound to its administrator and organization. Discovery
and review do not change trust. **Approve trust** requires a verification method,
20–1,000 character evidence note, and explicit confirmation of the organization
and exact identity. Use **Demo walkthrough only** for the synthetic organizations;
real verification requires a known independent contact or authoritative directory.
A self-published profile, HTTPS, or a valid credential signature is insufficient
to establish real accreditation.

Approval re-fetches the publication and rejects a changed profile or expired
review. The database records its profile snapshot/hash, approving account,
organization, method, evidence and time, together with a trust revision and audit
event. Retries are idempotent; another administrator or organization cannot use
the review. There is no raw-DID browser/API shortcut. Existing trust entries are
retained with a visible **review needed** notice until reviewed. Removing trust
retains approval/audit history and never changes finalized verification results.
Published scope is descriptive; approval currently trusts the exact issuer identity
under the existing competency type and job policies, not a separate per-issuer
competency allowlist. A changed key must be reviewed as a new identity; approving
it does not silently remove an old trusted key.

Application database migration 3 adds review and approval records plus a nullable
approval reference on trust entries. Back up issuer/company stores consistently
before activation. Old binaries reject the newer schema; rollback requires their
matching pre-migration snapshots, not merely old container images. No Android,
Keycloak session policy, issuer signing key or holder key changes are required.
