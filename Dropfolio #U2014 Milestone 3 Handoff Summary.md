# Dropfolio — Milestone 3 Handoff Summary
## Item Catalog — Programmer & PM Context

**Project:** Dropfolio Backend  
**Milestone:** 3 — Item Catalog  
**Status:** ✅ **COMPLETE — IMPLEMENTED, TESTED, AND MANUALLY VERIFIED**  
**Date:** 2026-09-04

---

# 1. Executive Summary

Milestone 3 fokus pada implementasi **Item Catalog** untuk Dropfolio Backend.

Milestone ini sudah:

- diimplementasikan,
- berhasil di-build,
- seluruh automated tests berhasil,
- database migration V3 berhasil diterapkan,
- dan seluruh core endpoint sudah diverifikasi secara manual menggunakan Postman.

### Final automated test result

```text
Tests run: 64
Failures: 0
Errors: 0
Skipped: 0

BUILD SUCCESS
```

Final Maven result:

```text
[INFO] Tests run: 64, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**Kesimpulan PM:** Milestone 3 dapat dianggap **DONE**.

**Kesimpulan Programmer:** Tidak ada failing test atau known implementation blocker dari hasil verifikasi Milestone 3.

---

# 2. Environment Used

Milestone 3 diverifikasi menggunakan:

- Java 21.0.12.1 LTS
- Maven 3.9.16
- Spring Boot 3.3.4
- SQL Server-compatible Azure SQL Edge
- Redis 7 Alpine
- Flyway
- Hibernate/JPA
- JWT / BCrypt
- Postman
- VS Code Spring Boot extension

Project backend:

```text
F:\dropfolio-milestone3-item\backend
```

Application berjalan pada:

```text
http://localhost:8080
```

---

# 3. Important Environment Issue Resolved

Pada awal testing, `mvn clean test` sempat dijalankan menggunakan **JDK 25**.

Testing gagal bukan karena bug Item Catalog, tetapi karena dependency Byte Buddy yang digunakan Mockito pada project belum mendukung Java 25 secara resmi.

Root issue:

```text
Java 25 not supported by current Byte Buddy version
```

Environment kemudian dikembalikan ke:

```text
Java 21.0.12.1 LTS
```

Setelah menggunakan Java 21:

```text
mvn clean test
```

berhasil:

```text
64/64 tests PASS
BUILD SUCCESS
```

### Important instruction

Untuk project ini, gunakan **Java 21**, bukan Java 25.

Jangan mengubah dependency hanya untuk mengakomodasi Java 25 kecuali ada keputusan teknis baru yang memang disengaja.

---

# 4. Milestone 3 Scope

Milestone 3 menambahkan **global Item Catalog**.

Item catalog bersifat global dan **bukan ownership-scoped**.

Tidak ada:

```text
userId
ownerId
```

pada Item.

Item yang tersedia di catalog merepresentasikan item global yang dapat digunakan oleh fitur-fitur berikutnya.

---

# 5. Item API

Endpoint yang tersedia:

## Public endpoints

### GET list

```http
GET /api/v1/items
```

Fungsi:

- list item
- search
- filter berdasarkan type
- sorting
- pagination

Tidak membutuhkan authentication.

---

### GET detail

```http
GET /api/v1/items/{id}
```

Fungsi:

- mengambil detail satu item.

Tidak membutuhkan authentication.

---

## ADMIN endpoints

### POST

```http
POST /api/v1/items
```

Hanya role:

```text
ADMIN
```

yang dapat membuat item.

Response sukses:

```text
201 Created
```

dengan `Location` header.

---

### PATCH

```http
PATCH /api/v1/items/{id}
```

Hanya role:

```text
ADMIN
```

yang dapat mengubah item.

Tidak ada DELETE endpoint pada Milestone 3.

Status aktif/nonaktif diubah melalui:

```json
{
  "isActive": false
}
```

atau:

```json
{
  "isActive": true
}
```

---

# 6. Item Entity

File:

```text
item/entity/Item.java
```

Enum:

```text
item/entity/ItemType.java
```

Item memiliki konsep:

- id
- name
- type
- marketHashName
- iconUrl
- isActive
- createdAt
- updatedAt

Timestamp menggunakan pendekatan yang kompatibel dengan Java `Instant` dan Hibernate 6.

Database menggunakan:

```sql
DATETIMEOFFSET(6)
```

---

# 7. Database Migration

Migration baru:

```text
src/main/resources/db/migration/V3__init_items.sql
```

Migration membuat table:

```text
items
```

V3 berhasil dijalankan oleh Flyway.

Startup log menunjukkan:

```text
Migrating schema to version "3 - init items"
Successfully applied V3
```

Schema sekarang berada pada:

```text
version 3
```

### Important

V3 adalah migration berikutnya setelah V2.

Jangan mengubah nomor migration atau membuat migration baru dengan nomor yang bertabrakan.

---

# 8. Item Repository & Search

Files:

```text
item/repository/ItemRepository.java
item/repository/ItemSpecifications.java
```

Listing mendukung:

```text
search
type
sort
page
size
```

Allowed sort fields:

```text
name
createdAt
```

Sort lain tidak diperbolehkan.

Contoh:

```http
GET /api/v1/items?sort=name
```

atau:

```http
GET /api/v1/items?sort=createdAt
```

Invalid:

```http
GET /api/v1/items?sort=price
```

menghasilkan:

```text
422 VALIDATION_ERROR
```

---

# 9. Pagination Rules

Pagination menggunakan:

```text
page >= 1
size = 1..100
```

Default yang diverifikasi:

```text
page = 1
size = 20
```

Invalid:

```text
page=0
```

→ 422

Invalid:

```text
size=0
```

→ 422

Invalid:

```text
size=101
```

→ 422

Valid:

```text
size=100
```

→ 200

Response metadata:

```json
{
  "page": 1,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

---

# 10. Validation

Create request:

```text
item/dto/CreateItemRequest.java
```

Validation mencakup:

### name

- wajib
- tidak boleh blank
- maximum 200 karakter

### type

- wajib
- harus merupakan enum `ItemType`

### marketHashName

- wajib
- tidak boleh blank
- maximum 300 karakter
- harus unik

### iconUrl

- maximum 500 karakter

---

# 11. PATCH Semantics

Update request:

```text
item/dto/UpdateItemRequest.java
```

Field yang dapat diubah:

```text
name
iconUrl
isActive
```

Field berikut **TIDAK PATCHABLE**:

```text
type
marketHashName
```

Jika field tersebut dikirim pada request PATCH, field tersebut tidak mengubah nilai existing.

`null` berarti:

```text
leave unchanged
```

### Important validation detail

`UpdateItemRequest.name` menggunakan:

```java
@Size(max = 200)
```

bukan `@NotBlank`.

Karena itu:

```json
{
  "name": ""
}
```

secara kontrak masih valid.

Testing sudah mengonfirmasi bahwa empty string diterima.

Jangan mengubah behavior ini menjadi `@NotBlank` tanpa ada perubahan specification/contract yang eksplisit.

---

# 12. marketHashName Uniqueness

`marketHashName` harus unik dalam catalog.

Jika mencoba membuat item dengan `marketHashName` yang sudah ada:

```text
409 CONFLICT
```

Response yang diverifikasi:

```json
{
  "success": false,
  "error": {
    "category": "CONFLICT",
    "code": "CONFLICT",
    "message": "marketHashName already exists in the catalog"
  }
}
```

Custom exception:

```text
common/exception/ItemMarketHashNameConflictException.java
```

Tidak ada registry-specific error code pada Milestone 3.

---

# 13. Security Rules

`SecurityConfig` dimodifikasi untuk Item API.

Public:

```text
GET /api/v1/items
GET /api/v1/items/{id}
```

ADMIN only:

```text
POST /api/v1/items
PATCH /api/v1/items/{id}
```

Expected behavior:

```text
No token + POST       -> 401
USER + POST           -> 403
ADMIN + POST          -> 201
USER + PATCH          -> 403
ADMIN + PATCH         -> 200
```

Semua behavior tersebut sudah diverifikasi.

---

# 14. Global Exception Handling

File:

```text
common/exception/GlobalExceptionHandler.java
```

ditambahkan handling untuk:

```text
HttpMessageNotReadableException
MethodArgumentTypeMismatchException
```

Tujuannya agar invalid enum/value pada request dapat menghasilkan:

```text
422 VALIDATION_ERROR
```

bukan generic 500.

Contoh:

```http
GET /api/v1/items?type=INVALID
```

menghasilkan:

```text
422
```

dengan:

```text
Invalid value for parameter: type
```

Create dengan:

```json
{
  "type": "INVALID"
}
```

juga menghasilkan:

```text
422
```

---

# 15. Audit Integration

Item service sudah diintegrasikan dengan existing audit mechanism.

Create:

```java
@Auditable(action="ADMIN_CREATE_ITEM")
```

Update:

```java
@Auditable(action="ADMIN_UPDATE_ITEM")
```

AuditAspect sendiri tetap merupakan existing skeleton dan bukan bagian yang diperluas dalam Milestone 3.

---

# 16. Automated Tests

Milestone 3 menambahkan:

```text
item/service/ItemServiceTest.java
```

dan:

```text
item/controller/ItemControllerTest.java
```

Result:

```text
ItemControllerTest
11/11 PASS

ItemServiceTest
10/10 PASS
```

Total keseluruhan project:

```text
64/64 PASS
```

Existing Milestone 2 tests juga tetap PASS.

Test breakdown:

```text
AuthControllerTest       11/11
AuthServiceTest           8/8
RefreshTokenServiceTest   9/9
GlobalExceptionHandlerTest 11/11
RateLimitFilterTest       4/4
ItemControllerTest       11/11
ItemServiceTest          10/10
--------------------------------
TOTAL                    64/64
```

---

# 17. Manual Postman Verification

Seluruh core functionality Milestone 3 sudah diuji manual.

## Verified PASS

### Catalog

```text
GET /items
GET /items/{id}
```

### Create

```text
POST /items
```

### Update

```text
PATCH /items/{id}
```

### Authentication

```text
POST without Authorization -> 401
POST as USER -> 403
POST as ADMIN -> 201
PATCH as USER -> 403
PATCH as ADMIN -> 200
```

### Error handling

```text
duplicate marketHashName -> 409
invalid create name -> 422
invalid create type -> 422
invalid GET type -> 422
invalid sort -> 422
page=0 -> 422
size=0 -> 422
size=101 -> 422
nonexistent GET -> 404
nonexistent PATCH -> 404
```

### Query functionality

```text
search
type filter
sort=name
sort=createdAt
pagination
combined query
```

### Active state

```text
isActive=false -> PASS
isActive=true  -> PASS
```

### PATCH restrictions

```text
type cannot be changed
marketHashName cannot be changed
```

sudah diverifikasi.

---

# 18. Example Item Used During Manual Testing

Item test yang dibuat:

```json
{
  "name": "AK-47 | Redline",
  "type": "SKIN",
  "marketHashName": "AK-47 | Redline (Field-Tested)",
  "iconUrl": "https://example.com/ak47-redline.png"
}
```

Item berhasil dibuat dengan:

```text
id = 1
isActive = true
```

Current catalog state pada akhir testing pada dasarnya sudah dikembalikan ke state normal:

```text
name = AK-47 | Redline
type = SKIN
marketHashName = AK-47 | Redline (Field-Tested)
isActive = true
```

---

# 19. Existing Milestone 2 Compatibility

Milestone 3 tidak mengganti architecture utama Milestone 2.

Existing:

- authentication
- JWT
- refresh token
- Redis
- BCrypt
- rate limiting
- global exception handling
- role system

tetap berjalan.

Automated tests Milestone 2 tetap PASS setelah Item Catalog ditambahkan.

Ini penting karena Item Catalog harus dianggap sebagai **extension dari backend existing**, bukan replacement terhadap auth architecture.

---

# 20. Files Added / Modified

## Added

```text
item/entity/Item.java
item/entity/ItemType.java

item/repository/ItemRepository.java
item/repository/ItemSpecifications.java

item/dto/ItemResponse.java
item/dto/CreateItemRequest.java
item/dto/UpdateItemRequest.java

item/mapper/ItemMapper.java

item/service/ItemService.java

item/controller/ItemController.java

src/main/resources/db/migration/V3__init_items.sql

common/exception/ItemMarketHashNameConflictException.java

item/service/ItemServiceTest.java
item/controller/ItemControllerTest.java
```

## Modified

```text
common/exception/GlobalExceptionHandler.java
common/security/SecurityConfig.java
```

No other accepted Milestone 2 modules were intentionally redesigned.

---

# 21. Important Design Decisions

The following decisions have already been made and verified.

### Decision 1 — Item is global

Item tidak memiliki ownership/user relationship.

---

### Decision 2 — GET catalog is public

Authentication tidak diperlukan untuk membaca catalog.

---

### Decision 3 — Mutations are ADMIN only

Create/update membutuhkan:

```text
ROLE_ADMIN
```

---

### Decision 4 — No DELETE

Item tidak dihapus secara hard-delete melalui API.

Gunakan:

```text
PATCH isActive
```

untuk enable/disable.

---

### Decision 5 — marketHashName immutable through PATCH

`marketHashName` dapat ditentukan ketika create, tetapi tidak dapat diubah melalui PATCH.

---

### Decision 6 — type immutable through PATCH

`type` juga tidak dapat diubah melalui PATCH.

---

### Decision 7 — Empty PATCH name is valid

Karena contract menggunakan `@Size(max = 200)`, bukan `@NotBlank`.

---

### Decision 8 — Sorting whitelist

Hanya:

```text
name
createdAt
```

yang diperbolehkan.

---

### Decision 9 — Java 21 baseline

Project menggunakan Java 21.

---

# 22. Potential Interpretation Flag

Ada satu behavior yang perlu diketahui PM/programmer berikutnya.

GET catalog saat ini **tidak memiliki filter `isActive` pada locked query parameters**.

Response tetap mengekspos:

```text
isActive
```

Interpretasi implementasi saat ini adalah catalog listing dapat mengembalikan item inactive, bukan secara diam-diam melakukan active-only filtering.

Jangan mengubah behavior tersebut tanpa specification baru atau keputusan PM.

---

# 23. Current Status

### Milestone 3

```text
IMPLEMENTATION     ✅
COMPILATION        ✅
UNIT TESTS         ✅
CONTROLLER TESTS   ✅
DATABASE MIGRATION ✅
APPLICATION START  ✅
POSTMAN TESTING    ✅
SECURITY TESTING   ✅
VALIDATION TESTING ✅
BUILD              ✅
```

Overall:

```text
64/64 automated tests PASS
BUILD SUCCESS
```

**Milestone 3 = DONE.**

---

# 24. PM Handoff

Dari perspektif PM, tidak ada pekerjaan Milestone 3 yang tersisa berdasarkan scope yang sudah diverifikasi.

Jangan membuka kembali Milestone 3 hanya untuk melakukan refactor kosmetik atau mengubah behavior yang sudah sesuai contract.

Jika ada requirement baru terkait:

- active-only catalog
- item ownership
- delete item
- editable `type`
- editable `marketHashName`
- perubahan validation
- perubahan sorting
- perubahan authorization

maka perlakukan sebagai **new requirement / future milestone change**, bukan asumsi bahwa Milestone 3 belum selesai.

---

# 25. Programmer Handoff

Jika melanjutkan development setelah Milestone 3:

1. Pertahankan Java 21.
2. Jangan merusak Auth/Security behavior Milestone 2.
3. Pertahankan Flyway migration chain V1 → V2 → V3.
4. Jangan mengubah PATCH semantics tanpa specification.
5. Jangan membuat Item menjadi user-owned tanpa requirement eksplisit.
6. Jangan menambahkan DELETE endpoint tanpa requirement.
7. Pertahankan ADMIN-only mutation.
8. Jalankan regression test setelah perubahan:

```powershell
mvn clean test
```

Expected baseline:

```text
Tests run: 64
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
```

Jika milestone berikutnya menambahkan test baru, angka total test tentu boleh meningkat, tetapi **baseline 64 existing tests harus tetap PASS** kecuali ada perubahan specification yang disengaja.

---

# 26. Final Handoff Statement

> **Milestone 3 — Item Catalog telah selesai diimplementasikan dan diverifikasi.**
>
> Backend berhasil menjalankan Flyway V3, Item Catalog API berfungsi, authorization ADMIN/USER berjalan sesuai requirement, validation dan error handling telah diverifikasi, search/filter/sort/pagination telah diuji, PATCH semantics telah diuji, dan seluruh automated regression suite menghasilkan **64/64 PASS dengan BUILD SUCCESS**.
>
> **Claude sebagai Programmer:** anggap Milestone 3 sebagai stable baseline dan jangan melakukan perubahan behavior tanpa requirement baru.
>
> **Claude sebagai PM:** tandai Milestone 3 sebagai **COMPLETED** dan lanjutkan planning/development ke milestone berikutnya.