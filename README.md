# Bukti Kompetensi Digital

Demo bukti kompetensi untuk lamaran teknisi instalasi listrik dengan data sintetis.
Issuer, wallet custodial dan verifier memakai mesin protokol walt.id yang nyata.
Target: APK Android custodial, portal Compose Web/Wasm, backend Kotlin/Ktor/JDK 21,
Keycloak/PostgreSQL dan Exposed/SQLite. Holder key serta credential tetap di server.

Status P1: fondasi repo dan CI; aplikasi rilis belum dibangun. Ini belum quickstart
aplikasi yang dapat dipakai. Build fork dan supply-chain acceptance dilacak pada
repo [waltid-identity](https://github.com/nusacred-lab/waltid-identity).

Satu issuer, satu perusahaan, satu lowongan dan data sintetis; CV, rekrutmen lengkap,
credential revocation dan banyak issuer/perusahaan di luar scope awal.
Infra demo satu VM, backup eksternal dan restore teruji diperlukan sebelum dibuka.

Pemeriksaan lokal: `python3 ci/check_policy.py`. CI menambah pemindaian seluruh
riwayat Git dan dependency review pada PR. Lihat SECURITY-PROJECT.md.
Kode proyek: Apache-2.0 (LICENSE). Tidak ada afiliasi resmi dengan walt.id.
