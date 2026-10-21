# P2.0 requirements clarification R1

Source: direct instructions from the project owner in the project chat on
21 October 2026. The owner confirmed containerized services, a physical Android
APK as the custodial wallet interface, and recovery as a required assessed flow.
Implementation in the repositories takes priority; other delivery requirements
will be addressed after the use-case components work. This records the owner's
clarification rather than claiming an independent review of an unseen brief.

| Confirmed requirement | Implementation boundary |
| --- | --- |
| All services run in containers | JVM backends, compiled portal hosting, Keycloak/PostgreSQL/SMTP and walt.id issuer/wallet/verifier run in Compose. Host-only proof servers are not the deployment target. |
| Physical custodial Android APK | The native client runs on the phone and talks to the custodian backend. It does not run as a server container. |
| Recovery is required | Recovery must retain the identity-to-wallet mapping, holder key and credential, revoke old access/consent and prove presentation of an existing credential. |
| Code and working components first | Continue functional integration and its necessary tests; defer video, concept note and other handover material. Security and recovery acceptance still apply. |

Continue the existing server-custodial design: Wallet API2 holds keys and
credentials, Keycloak authenticates users, and the custodian backend owns the
access registry, consent and recovery gate. Each account has a stable wallet
binding. APKs contain neither holder private keys nor service/admin secrets.
Recovery changes account access while retaining wallet material. This continues
the established design; no new custody provider or device-held signing scheme is
introduced. [Wallet API2's wallet model](https://docs.walt.id/community-stack/wallet2/wallets)
provides the key/credential container used by this design.

The P2 application image now hosts the built JVM service and compiled portal on
the same origin. Its Compose override uses an internal application network and
loopback diagnostics. Login, wallet integration, recovery and App Links are still
open acceptance items; a working container is not a G2 pass.
