# P2.3 login proof — 21 October 2026

The custodian now implements browser BFF authorization-code + PKCE and the native
AppAuth session-registration contract. This is a partial P2.3 proof: physical APK
browser completion and the other two role-specific portal backends remain open.
Wallet access is still unavailable until the persistent registry/recovery work
passes. No credential success stub or default wallet identity is introduced.

## Authentication boundary

The backend verifies discovery against a fixed issuer and exact endpoint paths.
Its private backchannel keeps the same TLS hostname and validates the certificate
chain; it never follows discovery/token/JWK URLs supplied by a caller or JWT.
Keys are loaded from the configured IdP at startup; unknown/rotated key IDs fail
closed until a controlled restart. Tokens require RS256 signatures, issuer,
audience, expiry, issued-at, authorized party, matching subject/session, a verified
email and a required role. ID-token nonce, optional access-token hash and fresh
`auth_time` bind the authorization to its single-use five-minute transaction.

Browser login uses a random state, nonce, S256 verifier and a distinct `__Host-`
Secure/HttpOnly/Lax flow cookie. Missing or mismatched bindings cannot consume a
legitimate login. The callback checks response issuer and rejects duplicate
parameters/replay; redirect destinations are fixed. IdP tokens stay server-side.
The browser receives an opaque `__Host-` Secure/HttpOnly cookie; logout requires
both the exact Origin and a session CSRF value. Dynamic email output is escaped,
and the account page restricts scripts with a nonce-based CSP. Authentication
responses use `no-store`; raw token values and claims are not logged.

Native login uses AppAuth 0.11.1 with system-browser discovery, S256 PKCE and a
backend-issued nonce. It submits its access/ID-token pair for verification once,
then uses an opaque backend session. The APK persists only that opaque session,
encrypted with a 256-bit Android Keystore AES/GCM key. IdP tokens and PKCE flows
remain ephemeral. Lost in-flight state cancels sign-in. Backup and device transfer
exclude application data, including session preferences. No client/service secret
or holder private key is bundled. Candidate release App Links remain P2.9 work;
this proof still uses the separate debug redirect scheme.

The registry in this increment is bounded in-memory login state, not the final
persistent access/recovery registry. Backend restart signs users out. Each account
check introspects the IdP using the dedicated confidential client; uncertainty
removes that login session. Introspection alone is not claimed to prove the
cross-service recovery invariant or same-timestamp authentication freshness.
`/auth/me` reports that credential access is unavailable; credential/consent
routes remain absent. Issuer/company BFF implementations and contexts remain open.

## Real-service checks

`test_p2_login.py` in `nusacred-lab/platform-infra` drives the actual Keycloak
username/password page and token endpoint through locally verified TLS. Dedicated
synthetic applicant and custodian-admin fixtures are preverified administratively;
this is not evidence that email verification links have been consumed.

Fifteen checks pass locally: anonymous rejection, native public-client PKCE and
opaque sessions, transaction replay, wrong PKCE, tampered signature, access/ID
tokens used directly as application sessions, wrong nonce, ID/access token
audience confusion, BFF login/cookies, callback replay, Origin/CSRF rejection,
proper BFF logout, missing/wrong browser bindings and response issuer, role
separation, and real IdP logout invalidation on the next application check.

The pinned Keycloak clients initially omitted the `basic` scope, so `auth_time`
was absent and login was correctly rejected. The explicit migration adds `basic`
and the exact API audience to the native/custodian BFF clients. Validation was
kept strict. Native callbacks include no password grant; app clients keep direct
password and implicit grants disabled. Fixture management never resets existing
passwords, deletes accounts or changes holder material.

## Build and supply chain

Nimbus JOSE/JWT 10.10 handles token parsing/verification; AppAuth 0.11.1 handles the
native flow. The updated ledger verifies 1,217 publisher artifact hashes and four
lockfiles. Strict JVM/APK/Wasm builds and Android lint pass. The built Android
runtime graph contains 133 Maven coordinates; its CycloneDX scan has zero
High/Critical findings and exact coverage is enforced. This is graph SCA, not a
claim of APK binary, embedded-native-library or complete build-tool scanning.
Five private runtime images also pass the existing image gate. Single-maintainer
and pending remote-CI limitations remain.

The auth and wallet prerelease ingress configuration is staged separately from
the private stack. It permits only login/static assets, with admin, master realm,
mailbox, password-change actions and credential paths denied. Activating this
internet-reachable login surface requires the owner's explicit confirmation.
The original four probe routes and historical walt.id networks are unchanged.

References: [AppAuth](https://github.com/openid/AppAuth-Android),
[Nimbus JOSE/JWT](https://connect2id.com/products/nimbus-jose-jwt),
[Keycloak OIDC endpoints](https://www.keycloak.org/securing-apps/oidc-layers), and
[Keycloak basic-scope migration](https://github.com/keycloak/keycloak/blob/main/docs/documentation/upgrading/topics/changes/changes-25_0_0.adoc).
