# MILESTONE 5 ASSESSMENT REPORT — AWAITING PM APPROVAL
## `drop/` Module — Manual Holdings CRUD

**Status:** 📋 ASSESSMENT ONLY — NO CODE/MIGRATION/IMPLEMENTATION CHANGES MADE
**Date:** 6 September 2026
**Based on:** `PRD.md` v2.0, `SYSTEM_ARCHITECTURE.md` v2.0, `ERD.md` v2.0, `API_CONTRACT.md` v2.0, `TECHNICAL_SPEC.md` v2.0, `CLAUDE_CONTEXT.md` v2.0 (semua dokumen "terbaru" yang baru di-upload)

---

# 1. Scope & Non-Scope

## In-scope (dikunci PM)

| Endpoint | Auth | Ownership |
|---|---|---|
| `POST /api/v1/drops` | Required | self |
| `GET /api/v1/drops` | Required | self |
| `PATCH /api/v1/drops/{id}` | Required | self |
| `DELETE /api/v1/drops/{id}` | Required | self |

## Ditemukan saat assessment — perlu klarifikasi (lihat §15 poin 1)

`API_CONTRACT.md` §5 (Domain: Drops) juga mengunci endpoint kelima:

```http
GET /api/v1/drops/{id}
```

Endpoint ini **tidak disebut** di daftar scope awal PM approval message, tapi **ada** di kontrak yang sudah LOCKED sebagai bagian dari domain yang sama. Karena scope freeze (§11 CLAUDE_CONTEXT) melarang penambahan endpoint tanpa approval, dan sebaliknya juga melarang meninggalkan endpoint yang sudah dikunci tanpa alasan eksplisit, saya tidak akan meng-assume salah satu — lihat §15 untuk opsi.

## Non-scope (dikonfirmasi TIDAK dikerjakan di Milestone 5)

- Steam login/OAuth, Steam account linking, Steam API Key user — semua sudah dihapus dari arsitektur, tidak relevan sama sekali untuk `drop/`.
- Steam Inventory API, inventory synchronization, `/inventory/sync`, inventory scheduler, Steam asset ID — dikonfirmasi tidak ada, `drops.source` selalu `MANUAL` per ERD §2.7.
- `portfolio/` (summary, breakdown, CSV export) — modul terpisah, bergantung pada `drop/`+`item/`+`pricing/`, tapi bukan scope Milestone 5.
- `alert/`, `notification/`, `admin/`, `scheduler/` — tidak disentuh.
- Audit logging untuk drop CRUD — **dikonfirmasi tidak diperlukan**. Scope audit MVP dikunci hanya 4 admin action (`ADMIN_CREATE_ITEM`, `ADMIN_UPDATE_ITEM`, `ADMIN_UPDATE_USER_STATUS`, `ADMIN_TRIGGER_SYNC` — TECHNICAL_SPEC.md §14/§17). Drop CRUD adalah self-service user action, bukan bagian dari 4 action tsb.
- Dedup/duplicate detection untuk drop — **dikonfirmasi tidak ada**. ERD §2.7 tidak punya `steam_asset_id`/unique constraint apa pun; PRD §33 eksplisit: "Tidak ada dedup berdasarkan Steam asset ID."
- Rate limiting khusus untuk `/drops/*` — tidak ada baris rate limit spesifik di API_CONTRACT §12 untuk endpoint ini (berbeda dari `/auth/login`, `/auth/register`, admin sync trigger yang eksplisit dikunci). Tidak akan menambahkan rate limit baru tanpa keputusan PM.

---

# 2. Endpoint Contract (Ringkasan dari API_CONTRACT.md §5, Bukan Ditulis Ulang Bebas)

## `POST /drops`
- Body: `{ itemId, quantity, acquisitionDate, acquisitionValueUsd? }`
- Validasi: `itemId` harus ada **dan** `isActive=true` (kalau tidak → `404`, bukan error terpisah — dikonfirmasi di kontrak: "404 (item tidak ditemukan/tidak aktif)"); `quantity > 0`; `acquisitionDate` tidak boleh di masa depan; `acquisitionValueUsd` opsional, jika ada `> 0`.
- `source` otomatis `MANUAL`, tidak bisa di-override client (tidak ada field `source` di `CreateDropRequest` sama sekali — TECHNICAL_SPEC.md §3.5).
- Response `201` + `Location: /api/v1/drops/{id}` (TECHNICAL_SPEC.md §12: Location header wajib diimplementasikan).
- **Tidak idempotent** — submit dua kali dengan `itemId` sama = dua row berbeda (API_CONTRACT.md §0.10, sengaja).

## `GET /drops`
- Ownership: implisit filter `user_id = currentUser`.
- Query params: `search` (nama item), `type` (`CASE|SKIN|GRAFFITI`), `source` (hanya `MANUAL` valid — lihat §15 poin 3 soal teks kontrak yang tampak rusak di sini), `dateFrom`/`dateTo` (rentang `acquisitionDate`), `minValue`/`maxValue` (USD — lihat §15 poin 2, ambigu field mana), `page`, `size`, `sort` (whitelist: `acquisitionDate`, `currentValueUsd`, `createdAt`).
- Response tiap row: `item` (nested `DropItemRef`: `id, name, type, iconUrl`), `source`, `quantity`, `acquisitionDate`, `acquisitionValueUsd`, `currentValueUsd`, `priceAvailable`.
- **Penting:** `currentValueUsd` adalah harga **per-unit** terkini (sama skala dengan `acquisitionValueUsd`), **bukan** `quantity × harga`. Contoh di kontrak: `quantity: 3, acquisitionValueUsd: 0.48, currentValueUsd: 0.52` — keduanya angka per-unit. Perkalian dengan quantity adalah tanggung jawab `portfolio/` (milestone lain), bukan `drop/`.

## `GET /drops/{id}`
- Ownership: self → `404` jika bukan milik user. (Lihat §15 poin 1 — status scope-nya belum jelas.)

## `PATCH /drops/{id}`
- Body partial: `quantity?`, `acquisitionDate?`, `acquisitionValueUsd?` — **`itemId` tidak ada di daftar field yang bisa diubah**, jadi immutable via PATCH (sama pola dengan `type`/`marketHashName` di Item Milestone 3).
- Validasi sama seperti create untuk field yang dikirim.
- Konvensi `null = leave unchanged` (mengikuti preseden `UpdateItemRequest` Milestone 3) — akan dipakai kecuali PM menyatakan lain untuk `acquisitionValueUsd` (field nullable yang mungkin perlu bisa "dikosongkan kembali" — lihat §15 poin 4).

## `DELETE /drops/{id}`
- Soft delete (`deleted_at`), ownership self.

---

# 3. Dependency Terhadap `item/` dan `pricing/`

**Wajib bergantung pada keduanya** — tidak ada cara memenuhi kontrak tanpa ini:

- **`item/`** — untuk validasi `POST /drops` (`itemRepository.existsById` + cek `isActive`, reuse `ItemRepository` apa adanya, tidak perlu perubahan), dan untuk join `name`/`type`/`iconUrl` ke `DropItemRef` di setiap response.
- **`pricing/`** — untuk mengisi `currentValueUsd`/`priceAvailable` di setiap `DropResponse`. Ini berarti reuse `PricingService.getPrice(itemId)` (Milestone 4), yang sudah membawa cache-aside + circuit breaker `redisCache` secara otomatis — **tidak perlu kode cache-aside baru di `drop/`**.

Tidak ada aturan di `SYSTEM_ARCHITECTURE.md` §2 yang melarang `drop → pricing`; larangan eksplisit yang ada hanya `pricing ⊥ notification` dan arah `portfolio → item+pricing`. Dependency `drop → item` + `drop → pricing` konsisten dengan pola yang sama, tidak melanggar aturan modul manapun.

**Catatan performa (bukan ambiguitas kontrak, murni implementasi):** `GET /drops` bisa mengembalikan hingga 100 row per page, tiap row butuh harga terkini. Rencana implementasi: kumpulkan `itemId` unik dalam satu page dulu, panggil `PricingService.getPrice(itemId)` sekali per itemId unik (bukan sekali per row) untuk mengurangi round-trip Redis/SQL, lalu map hasilnya balik ke tiap row. Ini murni detail efisiensi, tidak mengubah kontrak/behavior yang terlihat user.

---

# 4. Entity / DTO / Repository / Service / Controller yang Diperlukan

```text
drop/entity/Drop.java              — id, userId, itemId, source, quantity,
                                      acquisitionValueUsd, acquisitionDate (LocalDate),
                                      createdAt, updatedAt, deletedAt
drop/entity/DropSource.java        — enum, satu nilai: MANUAL (mencerminkan CHECK
                                      constraint ERD §2.7; desain internal, bukan
                                      keputusan kontrak — lihat §15 poin 3)

drop/repository/DropRepository.java       — findByIdAndUserId(id, userId) (WAJIB pola
                                             ini, bukan findById+cek manual — TECHNICAL_SPEC.md
                                             §5.3), plus Specification-based query untuk list
drop/repository/DropSpecifications.java   — search/type/source/dateFrom/dateTo, pola sama
                                             persis dengan item/repository/ItemSpecifications.java
                                             Milestone 3 (join ke Item untuk search/type)

drop/dto/DropResponse.java         — id, item: DropItemRef, source, quantity,
                                      acquisitionDate, acquisitionValueUsd, currentValueUsd,
                                      priceAvailable
drop/dto/DropItemRef.java          — id, name, type, iconUrl (nested, TIDAK di-share
                                      dengan AlertItemRef — TECHNICAL_SPEC.md §3.12)
drop/dto/CreateDropRequest.java    — itemId, quantity, acquisitionDate, acquisitionValueUsd?
drop/dto/UpdateDropRequest.java    — quantity?, acquisitionDate?, acquisitionValueUsd?

drop/mapper/DropMapper.java        — Drop entity + Item + PriceResponse → DropResponse
                                      (mapping eksplisit, bukan reflection — TECHNICAL_SPEC.md §2)

drop/service/DropService.java      — create/list/get/update/delete, ownership check,
                                      item-active validation, orkestrasi PricingService

drop/controller/DropController.java — bind DTO + panggil service + ApiResponse, tanpa
                                       business logic (pola sama seperti ItemController)

src/main/resources/db/migration/V5__init_drops.sql
```

**Tidak perlu perubahan** pada `common/security/SecurityConfig.java` — `/api/v1/drops/*` tidak butuh matcher baru karena default `anyRequest().authenticated()` sudah menangani (berbeda dari Milestone 4 yang butuh `permitAll()` eksplisit untuk endpoint publik).

---

# 5. Ownership & User-Scoping

Mengikuti pola wajib TECHNICAL_SPEC.md §5.3 persis seperti yang sudah diverifikasi bekerja di exception hierarchy:

```java
dropRepository.findByIdAndUserId(id, currentUserId)
    .orElseThrow(() -> new OwnershipMismatchException(...)) // atau ResourceNotFoundException
```

Repository query **selalu** menyertakan `userId` dan `deletedAt IS NULL` — tidak pernah `findById()` lalu cek manual.

**Reuse exception scaffolding yang sudah ada tapi belum pernah dipakai:** `common/exception/OwnershipMismatchException.java` sudah ada di codebase sejak sebelum Milestone 3 (kemungkinan skeleton Milestone 2), javadoc-nya secara eksplisit menyebut kasus "Optional.empty() pada query ownership-scoped" — persis kasus `drops`. Class ini dan `ResourceNotFoundException` menghasilkan response JSON yang identik (`404 NOT_FOUND`), jadi secara fungsional dapat dipertukarkan; saya berencana memakai `OwnershipMismatchException` untuk kasus `findByIdAndUserId` kosong pada `drop/` (sesuai desain aslinya), dan `ResourceNotFoundException` untuk kasus item tidak ada di catalog (konsisten dengan pola `PricingService` Milestone 4).

**Current user extraction:** sudah tersedia tanpa perlu kode baru — `JwtAuthenticationFilter` (Milestone 2 scaffolding) mengisi `Authentication.getPrincipal()` dengan `Long userId` langsung (bukan `UserDetails` custom). Controller bisa memakai `@AuthenticationPrincipal Long userId` langsung.

---

# 6. Validation Rules

| Field | Rule | Mekanisme |
|---|---|---|
| `itemId` | harus ada di catalog **dan** `isActive=true` | Service-level check (bukan Bean Validation) → `404` jika gagal (satu kode, tidak dipisah) |
| `quantity` | `> 0` | `@Positive` di DTO → `422` via existing `MethodArgumentNotValidException` handler |
| `acquisitionDate` | tidak boleh di masa depan | `@PastOrPresent` |
| `acquisitionValueUsd` | opsional; jika ada, `> 0` | `@Positive` (nullable-aware) |

Semua mekanisme validasi generik (`422 VALIDATION_ERROR`, `details` per-field) **reuse langsung** `GlobalExceptionHandler` yang sudah ada — **tidak perlu exception class baru** untuk validasi field-level.

---

# 7. Error Handling

| Skenario | Status | Category/Code | Exception |
|---|---|---|---|
| Tidak ada token | 401 | `UNAUTHORIZED` | ditangani filter chain (implisit, tidak perlu kode baru) |
| Item tidak ada / tidak aktif saat `POST /drops` | 404 | `NOT_FOUND` (generic, **bukan** kode baru seperti `ITEM_NOT_FOUND` — API_CONTRACT §0.6.2 eksplisit melarang mengarang kode baru) | `ResourceNotFoundException` (reuse Milestone 4 pattern) |
| Drop bukan milik user / tidak ada | 404 | `NOT_FOUND` | `OwnershipMismatchException` (reuse, lihat §5) |
| Field invalid (`quantity<=0`, tanggal masa depan, dst.) | 422 | `VALIDATION_ERROR` | Bean Validation, ditangani `GlobalExceptionHandler` existing |
| Sort field di luar whitelist / enum `type` invalid | 422 | `VALIDATION_ERROR` | Reuse `MethodArgumentTypeMismatchException` handler Milestone 3 |

**Tidak ada exception class baru yang perlu dibuat untuk Milestone 5** — semua kasus error di atas sudah punya padanan di hierarchy yang ada.

**Catatan penting (lihat §15 poin 5):** kontrak masih mencantumkan `409` sebagai status code valid untuk `PATCH /drops/{id}` dan `DELETE /drops/{id}`, tapi kode error `DROP_NOT_EDITABLE`/`DROP_NOT_DELETABLE` yang dulu jadi alasannya **sudah dihapus** dari registry §0.6.2 di dokumen v2.0 ini (karena alasannya — "source=STEAM_SYNC tidak boleh diedit" — sudah tidak berlaku, semua drop sekarang MANUAL). Tidak ada skenario locked lain yang menjelaskan kapan `409` terjadi untuk endpoint ini. Class scaffolding `DropNotEditableException`/`DropNotDeletableException` sudah ada di codebase (`common/exception/`) tapi berdasarkan pembacaan literal dokumen v2.0, **tidak ada lagi kondisi yang men-trigger keduanya**. Saya tidak akan menggunakan kedua class ini untuk Milestone 5 kecuali PM mengonfirmasi ada kondisi 409 baru — lihat §15.

---

# 8. Pagination / Sorting

- `page`/`size` — reuse konvensi identik Milestone 3 (`page>=1`, `size 1..100`, default `page=1,size=20`).
- Sort whitelist: `acquisitionDate`, `currentValueUsd`, `createdAt`. Field lain → `422` (reuse pattern Milestone 3).
- **Kompleksitas teknis (bukan ambiguitas kontrak):** `acquisitionDate`/`createdAt` adalah kolom asli di `drops`, sort langsung di level SQL seperti Item Milestone 3. `currentValueUsd` **bukan** kolom tersimpan — ini adalah nilai read-time dari `item_prices` snapshot terbaru. Sort berdasarkan field ini butuh join ke "harga terkini per item" di level query database (bukan sort in-memory setelah fetch, karena itu akan merusak korespondensi pagination `totalElements`/`totalPages`). Rencana implementasi: native query/JPQL dengan subquery korelasi (`ROW_NUMBER() OVER (PARTITION BY item_id ORDER BY fetched_at DESC)`), memakai index `IX_item_prices_item_fetched` yang memang didesain untuk kasus ini (ERD §2.6: "kritis untuk query harga terkini per item"). Ini murni keputusan teknis implementasi, bukan yang perlu di-escalate — dicatat di sini supaya PM tahu ini bagian paling kompleks di Milestone 5, bukan sekadar `ORDER BY` sederhana.

---

# 9. Database / Flyway Impact

`V5__init_drops.sql` — mengikuti ERD §2.7 persis:

```text
id, user_id (FK→users.id, NO ACTION), item_id (FK→items.id, NO ACTION),
source NVARCHAR(20) CHECK IN ('MANUAL'), quantity INT CHECK(>0) DEFAULT 1,
acquisition_value_usd DECIMAL(18,4) NULL, acquisition_date DATE NOT NULL,
created_at DATETIME2 DEFAULT SYSUTCDATETIME(), updated_at DATETIME2 NULL,
deleted_at DATETIME2 NULL

Index: IX_drops_user_id, IX_drops_item_id
```

- **Tidak ada FK CASCADE** — baik `users↔drops` maupun `items↔drops` = `NO ACTION` (ERD §3 Relationship Summary), konsisten dengan prinsip "item/user tidak pernah hard-delete, drop history dipertahankan".
- **Tidak ada unique constraint** — ERD eksplisit tidak ada dedup untuk drop manual.
- Migration chain: `V1→V2→V3→V4→V5`, tidak ada konflik nomor.

**Catatan:** ERD memakai `DATETIME2` untuk `created_at`/`updated_at`/`deleted_at` di tabel `drops` (bukan `DATETIMEOFFSET(6)` seperti `items`/`item_prices`). Saya akan **mengikuti ERD apa adanya** (`DATETIME2`) kecuali ini juga kena isu Instant/Hibernate 6 yang sama seperti V2's fix — perlu dicek saat implementasi apakah `DATETIME2` menyebabkan masalah mapping `Instant` yang sama seperti sebelum V2 fix. Jika iya, ini akan saya laporkan sebagai temuan teknis (bukan penyimpangan dari ERD tanpa pemberitahuan), bukan diam-diam diubah ke `DATETIMEOFFSET(6)`.

---

# 10. Redis / Pricing Impact

- **Tidak ada key Redis baru** — `drop/` tidak menyimpan apa pun ke Redis sendiri, murni konsumen `PricingService.getPrice(itemId)` yang sudah pakai `price:item:{itemId}` (Milestone 4, tidak berubah).
- **Tidak ada perubahan TTL/circuit breaker** — semua behavior cache-aside sepenuhnya di-inherit dari Milestone 4 tanpa modifikasi apa pun ke `pricing/`.
- **Tidak ada panggilan ke `MarketPriceProvider`** dari `drop/` — konsisten dengan prinsip "provider hanya dipanggil dari job background", `drop/` sama seperti `pricing/` murni synchronous read.

---

# 11. Security Implications

- Semua 4 (atau 5, lihat §15) endpoint: `Auth: Required`, tidak ada endpoint publik baru.
- Tidak ada role `ADMIN` — semua endpoint scoped ke `self`, tidak ada `@PreAuthorize hasRole(...)`.
- **Tidak perlu perubahan `SecurityConfig.java`** — default `anyRequest().authenticated()` sudah cukup.
- Ownership 404-bukan-403 adalah invariant paling kritis untuk diuji (§16.2 TECHNICAL_SPEC eksplisit menandai ini sebagai "risk area").

---

# 12. Test Plan

Mengikuti persis format wajib TECHNICAL_SPEC.md §16.2 per endpoint:

**`DropServiceTest` (unit, Mockito):**
- Create: item aktif → sukses; item tidak ada → `ResourceNotFoundException`; item `isActive=false` → `ResourceNotFoundException`; `quantity<=0`/tanggal masa depan/`acquisitionValueUsd<=0` → validasi gagal (via `@Valid`, biasanya diuji di controller test, bukan service).
- List: kombinasi filter (search/type/dateFrom/dateTo — tunggu keputusan minValue/maxValue), sort whitelist reject, pagination default.
- Update: field partial, `null=leave unchanged`, ownership mismatch → 404, item tidak dicoba diubah.
- Delete: soft delete (assert `deletedAt` terisi, row tidak hilang), ownership mismatch → 404, delete drop yang sudah di-delete → 404 (karena query filter `deletedAt IS NULL`).
- **Reuse `PricingService` via mock** — assert `DropResponse.currentValueUsd`/`priceAvailable` berasal dari `PriceResponse` yang dikembalikan mock, termasuk kasus `priceAvailable=false`.

**`DropControllerTest` (MockMvc + `@Import(SecurityConfig.class)`, pola sama Milestone 3/4):**
- Setiap endpoint: happy path, 401 tanpa token, 404 ownership-mismatch (**assert eksplisit ini, bukan 403** — sesuai §16.2 poin 5), 422 validasi.
- `GET /drops`: 422 untuk sort di luar whitelist, `meta` pagination shape.
- `POST /drops`: assert header `Location`.

**Manual verification (setelah automated test pass):**
- Insert item aktif via `POST /items` (reuse Milestone 3 flow) → `POST /drops` dengan `itemId` tsb → verify `GET /drops` menampilkan `currentValueUsd` sesuai data `item_prices` yang sudah ada dari Milestone 4 acceptance test.
- Update/delete drop milik user lain (pakai 2 akun) → assert `404`.

**Regression:** baseline **73 test existing wajib tetap PASS** setelah Milestone 5 (Milestone 3: 21, Milestone 4: 9, Milestone 2: 43 — total 73), ditambah seluruh test baru Milestone 5.

---

# 13. Existing Code yang Direuse (Ringkasan)

| Komponen | Sumber | Cara pakai |
|---|---|---|
| `ItemRepository.existsById` + query `isActive` | Milestone 3 | Validasi `POST /drops`, tanpa perubahan |
| `PricingService.getPrice(itemId)` | Milestone 4 | Isi `currentValueUsd`/`priceAvailable`, tanpa perubahan ke `pricing/` |
| `GlobalExceptionHandler` | Milestone 2/3 | Semua error 422/404/401 generik |
| `ResourceNotFoundException` | Milestone 4 | Item tidak ada/tidak aktif |
| `OwnershipMismatchException` | Skeleton (belum pernah dipakai) | Ownership mismatch drop |
| `ItemSpecifications` (pola, bukan class-nya) | Milestone 3 | Referensi bikin `DropSpecifications` |
| `JwtAuthenticationFilter` (principal = `Long userId`) | Milestone 2 skeleton | `@AuthenticationPrincipal Long userId` di controller |
| `SecurityConfig` default `anyRequest().authenticated()` | Milestone 2 | Tidak perlu matcher baru |

---

# 14. Potensi Konflik dengan Milestone 2–4

**Tidak ditemukan konflik kode.** Tidak ada file existing yang perlu dimodifikasi untuk security wiring (berbeda dari Milestone 4 yang menambah 1 baris `SecurityConfig`). `item/` dan `pricing/` dipakai read-only, tidak ada perubahan ke keduanya.

Satu catatan non-blocking: `common/exception/DropNotEditableException.java` dan `DropNotDeletableException.java` adalah scaffolding lama (kemungkinan dari draft Milestone 2 sebelum arsitektur Steam dihapus) yang sudah ada di codebase tapi **tidak pernah dipakai di kode manapun sampai sekarang**. Berdasarkan pembacaan dokumen v2.0 (lihat §7 di atas), kedua class ini sudah tidak punya kondisi trigger yang valid. Saya **tidak akan menghapus** file-file ini pada Milestone 5 (di luar scope untuk menyentuh file yang tidak terkait tanpa instruksi eksplisit), tapi juga tidak akan memakainya. Keputusan hapus/pertahankan sebagai dead code sepenuhnya di tangan PM, dicatat di sini murni supaya tidak jadi kejutan saat code review nanti.

---

# 15. Items Requiring PM Decision (Escalation — CLAUDE_CONTEXT.md §10)

Berikut adalah temuan yang **tidak bisa saya tentukan sendiri** dari lima dokumen sumber — saya berhenti di titik ini dan tidak menebak, sesuai Escalation Protocol.

### 1. `GET /drops/{id}` — termasuk Milestone 5 atau tidak?
`API_CONTRACT.md` §5 mengunci endpoint ini sebagai bagian dari domain Drops, tapi PM approval message untuk Milestone 5 hanya menyebut 4 endpoint (tidak termasuk `GET /drops/{id}`). Opsi:
- **(a)** Implementasikan sekarang juga (endpoint ke-5, sudah locked di kontrak, murah untuk ditambahkan karena `DropService`/`DropRepository`/`DropMapper` sudah harus ada untuk 4 endpoint lain — hanya butuh 1 method service + 1 controller method tambahan).
- **(b)** Tunda ke milestone berikutnya sesuai scope literal PM approval message.

Saya condong ke (a) karena marginal cost sangat kecil dan endpoint ini memang sudah locked di kontrak (bukan penambahan scope baru), tapi menunggu konfirmasi eksplisit sebelum coding.

### 2. `minValue`/`maxValue` (query param `GET /drops`) — filter berdasarkan field mana?
Kontrak hanya menulis `minValue, maxValue (USD)` tanpa menyebut nama field. Ada dua kandidat yang masuk akal secara produk: `acquisitionValueUsd` (harga saat didapat) atau `currentValueUsd` (harga read-time sekarang). Keduanya valid use-case ("holdings yang dulu murah" vs "holdings yang sekarang bernilai tinggi") tapi implementasinya sangat berbeda: filter `acquisitionValueUsd` adalah `WHERE` sederhana di kolom asli `drops`, sedangkan filter `currentValueUsd` butuh join ke snapshot harga terkini per item (kompleksitas sama seperti sort by `currentValueUsd`, §8). Ambiguitas ini sudah ada sejak versi dokumen sebelumnya (bukan regresi baru dari update v2.0), tapi belum pernah diimplementasikan sampai sekarang.

### 3. Query param `source` di `GET /drops` — teks kontrak tampak rusak
`API_CONTRACT.md` v2.0 menulis: `source (MANUAL|MANUAL)` — pola duplikasi ini konsisten dengan tanda-tanda proses replace-otomatis "STEAM_SYNC → MANUAL" yang tidak dibersihkan sepenuhnya di beberapa tempat lain di dokumen v2.0 (saya temukan beberapa contoh serupa di §15 poin 5 dan §12/§16.3, semuanya jejak leftover, tidak saya tandai satu-satu di sini). Karena satu-satunya nilai valid untuk `source` sekarang cuma `MANUAL` (ERD §2.7 CHECK constraint), saya berencana tetap menyediakan param ini (terima `MANUAL`, tolak nilai lain dengan `422` — pola sama seperti `type` di Item Milestone 3), tapi ingin dikonfirmasi apakah param yang secara fungsional selalu punya satu nilai valid ini tetap ingin dipertahankan di kontrak, atau lebih baik dihapus dari dokumen (perubahan dokumentasi, di luar wewenang saya sebagai programmer).

### 4. `UpdateDropRequest.acquisitionValueUsd` — bisakah dikosongkan kembali ke `null` via PATCH?
Mengikuti preseden Milestone 3 (`null = leave unchanged` untuk semua field PATCH), field ini secara default **tidak bisa** di-clear kembali ke `null` setelah pernah diisi (karena `null` di request diinterpretasikan sebagai "tidak diubah", bukan "kosongkan"). Ini konsisten dengan pola yang sudah established, tapi saya ingin eksplisit dikonfirmasi karena field ini nullable secara desain (opsional saat create) — mungkin ada kebutuhan produk untuk mengoreksi entry yang salah dengan mengosongkannya. Jika tidak dikonfirmasi lain, saya akan lanjut dengan konvensi default (`null = leave unchanged`).

### 5. `409` di status code list `PATCH /drops/{id}` dan `DELETE /drops/{id}` — kondisi apa yang men-trigger?
Sudah dijelaskan di §7 — kode error yang dulu jadi alasan (`DROP_NOT_EDITABLE`/`DROP_NOT_DELETABLE`, untuk drop bersumber `STEAM_SYNC`) sudah dihapus dari registry §0.6.2 karena semua drop sekarang `MANUAL`. Tidak ada kondisi locked lain yang menjelaskan `409` untuk kedua endpoint ini. Opsi:
- **(a)** Hapus `409` dari status code list kedua endpoint (perubahan dokumentasi, bukan wewenang saya).
- **(b)** PM konfirmasi ada kondisi baru yang perlu men-trigger `409` (mis. concurrent update conflict — belum ada mekanisme optimistic locking yang dikunci di dokumen manapun untuk `drops`).
- **(c)** Biarkan sebagai dead status code di dokumentasi (tidak pernah benar-benar terjadi), saya tidak implementasikan apa pun untuk itu.

Saya akan default ke **(c)** kecuali PM memilih (b) — artinya saya tidak akan membuat kode apa pun yang melempar `409` untuk PATCH/DELETE drop di Milestone 5.

---

# 16. Konfirmasi Tidak Ada Scope Creep

- Tidak ada endpoint baru di luar yang sudah locked di `API_CONTRACT.md` §5 (kecuali klarifikasi §15 poin 1 di atas — itu pun bukan endpoint baru, sudah locked di kontrak, hanya belum eksplisit disebut di approval message).
- Tidak ada perubahan HTTP method, response shape, status code (di luar yang sudah didiskusikan di §15), atau authentication/authorization rule.
- Tidak ada exception/error code baru yang diciptakan di luar registry §0.6.2.
- Tidak ada modifikasi ke `item/`, `pricing/`, atau modul lain manapun.
- Tidak mengimplementasikan `portfolio/`, `alert/`, `notification/`, `admin/`, `scheduler/`, atau apa pun terkait Steam.
- Tidak ada audit logging baru untuk drop CRUD (dikonfirmasi di luar 4 action MVP).
- Tidak ada rate limiting baru untuk `/drops/*`.

---

**Menunggu keputusan PM untuk 5 poin di §15 (khususnya poin 1 dan 2, yang paling mempengaruhi bentuk implementasi) sebelum memulai coding.**

**STOP — belum ada kode, migration, atau file implementasi yang dibuat/diubah pada tahap ini.**
