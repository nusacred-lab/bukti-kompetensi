# P2 stack proof — 21 October 2026

Owner: repository maintainer, assisted by Codex. This is a technical proof branch.
G2 remains open. The owner has now clarified the deployment/APK/recovery requirements directly;
see [P2.0 clarification R1](P2-requirements.md). Other handover requirements are
deferred until the use-case components work.

## Built and exercised

| Target | Evidence and limits |
| --- | --- |
| `custodian-backend` | Ktor/JVM distribution builds; real Ktor test host checks liveness and absence of wallet/recovery/readiness routes. No wallet adapter or authentication exists yet. |
| `android-wallet` | Debug APK `id.nusacred.wallet.debug`, min SDK 30, compile/target SDK 37. Installed and launched on a physical V2327 running Android 16 / API 36. Initial empty content was fixed by setting the application theme, explicit surface/text colors and safe drawing insets; the title and status message are visually verified. This is not the candidate release APK. |
| `web-portals` | Production Compose/Wasm distribution builds and visibly renders its title/status in a Chromium browser. No portal login exists yet. |

The proof has no credential success responses, embedded holder keys or client
secrets. The Android manifest disables backup and cleartext networking. There is
no network login implementation in the APK yet, and no App Links or scanner claim.

## Frozen build inputs

| Input | Version |
| --- | --- |
| JDK | Temurin 21.0.12.1+1, Linux x64 |
| Gradle | 9.8.0, inherited wrapper and distribution checksum from fork commit `10068c7f5f1f94ffeb4e5198a24d6de1eb458bda` |
| Kotlin / Compose compiler | 2.4.20 |
| Compose Multiplatform | 1.12.1 |
| Ktor | 3.6.0 |
| Android Gradle Plugin | 9.3.3, built-in Kotlin support |
| Android activity Compose | 1.13.0 |
| Android SDK / build tools | API 37.0 revision 2 / 37.0.0 |
| Kotlin/Wasm Node / Yarn / Binaryen | 26.2.0 / 1.22.22 / 130 |

The local system JDK 21.0.6 was used for discovery only; the complete strict build
was repeated with the checksum-verified maintained JDK above. Its archive SHA-256
is `ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94`.
The inherited CI spelling `21.0.12+1` was unavailable from the vendor API; the
available update `21.0.12.1+1` is explicitly pinned for this application.

`gradle/libs.versions.toml`, four Gradle lockfiles and dependency verification
metadata record the exact resolved inputs. `ci/dependency-review.json` records
publisher checksums/downloads and exact matches to the accepted fork ledger.
Strict verification is enabled during normal builds; CI never generates hashes.
Review is limited to one maintainer. Checksums establish consistency with the
recorded publisher bytes, not independent approval or a complete security audit.

## Reproduce

Install the pinned JDK, Android command-line tools with accepted licenses, Android
API 37.0 and build tools 37.0.0. Set `JAVA_HOME` and `ANDROID_HOME`, then run from the
repository root:

```sh
python3 ci/check_policy.py
python3 ci/verify_dependency_review.py
./gradlew :custodian-backend:test :custodian-backend:installDist :android-wallet:assembleDebug :web-portals:wasmJsBrowserDistribution --dependency-verification strict --no-daemon
```

Outputs are under `custodian-backend/build/install/`,
`android-wallet/build/outputs/apk/debug/` and
`web-portals/build/dist/wasmJs/productionExecutable/`. The runnable backend listens
on loopback port 18080 and only exposes `GET /health/live`. It deliberately has no
readiness response while identity/wallet integration is absent. Serve the web
distribution over HTTP on loopback for a browser smoke test.

`P2 stack proof` CI adds the same strict build and scans actual JVM runtime JARs.
The runtime scan must use Trivy `rootfs`: a source `fs` scan returned no packages
and was rejected as evidence. The corrected local scan detects 45 packages and
zero High/Critical findings. Full Android/AAR, Wasm/frontend and build-tool SCA,
release signing, SBOM/provenance and CI acceptance remain required work.

## Identity and recovery boundaries

The separate infrastructure branch implements private Keycloak 26.8.1 with
PostgreSQL 18.6 and private Mailpit. Discovery/JWKS, distinct clients/roles,
verification/password-action email delivery and restart persistence are proven.
Email-link consumption and actual password replacement are not proven by email
delivery. See `docs/P2-identity-proof.md` in `nusacred-lab/platform-infra`.

P2.3–P2.9 remain open: native PKCE and web BFF validation, stable identity-to-wallet
mapping, persistent recovery gate/SPI, revocation and concurrency/failure tests,
old-credential presentation and candidate APK scanner/App Links. This branch
neither opens credential services to public ingress nor changes G1 containment.

## Container follow-up

`deploy/Containerfile.custodian` packages the verified JVM distribution and built
portal assets together using the accepted fork's digest-pinned JRE base. The
image runs as UID/GID 10001 and accepts `HTTP_HOST`, `HTTP_PORT` and `PORTAL_ROOT`;
without these settings the local diagnostic default remains loopback. Invalid
ports or a missing configured asset directory fail startup. Only compiled assets
are added to the image using a whitelist Docker build context.

After the strict distribution build, build the image from this repository:

```sh
docker build -f deploy/Containerfile.custodian --build-arg SOURCE_REVISION="$(git rev-parse HEAD)" -t competency-proof-p2-custodian:0.2.0 .
```

Run it with `p2/application.compose.yaml` in `nusacred-lab/platform-infra`, after
scanning and recording its immutable image ID. `python3 ci/check_container_probe.py`
checks the real loopback endpoint, compares served HTML/JS/Wasm to the built asset
bytes and rejects exposed unimplemented or private paths. This replaces host-run
probe servers for application testing. Android remains native on the phone.
