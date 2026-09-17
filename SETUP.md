# Setup Backend Dropfolio — dari VSCode Kosong

Panduan ini asumsi kondisi Anda: baru ada VSCode, belum ada JDK/Maven/Docker terinstall.

## 1. Install tool wajib (sekali saja, di luar VSCode)

| Tool | Versi | Kenapa |
|---|---|---|
| **JDK** | 21 (Temurin/Eclipse Adoptium direkomendasikan) | Project pakai Java 21 |
| **Docker Desktop** | terbaru | Menjalankan Azure SQL Edge + Redis lokal tanpa install manual |
| Maven | *opsional* — lihat catatan di bawah | Build tool |

**Cek instalasi:**
```bash
java -version   # harus muncul 21.x
docker --version
```

> **Soal Maven:** Anda TIDAK perlu install Maven secara global. Extension VSCode di langkah 2
> akan otomatis pakai Maven wrapper/embedded. Kalau nanti mau run `mvn` dari terminal manual,
> baru install Maven (`brew install maven` / `choco install maven` / `sudo apt install maven`).

## 2. Extension VSCode

Buka Extensions (Ctrl+Shift+X), install:
- **Extension Pack for Java** (Microsoft) — bundle: Language Support, Debugger, Test Runner, Maven for Java, Project Manager for Java
- **Spring Boot Extension Pack** (VMware) — Spring Boot Dashboard, Spring Initializr, Spring Boot Tools

Setelah install, buka folder `backend/` di VSCode (`File > Open Folder`). Tunggu sampai status bar kanan bawah selesai "Loading Java Projects" — ini bisa lama di run pertama karena Maven akan download semua dependency dari `pom.xml`.

## 3. Jalankan database + Redis lokal (Docker)

```bash
cd backend
docker compose up -d
```

Ini menjalankan 2 container: `dropfolio-mssql` (Azure SQL Edge, port 1433) dan `dropfolio-redis` (port 6379).

Tunggu sampai container `dropfolio-mssql` berstatus `healthy` (cek dengan `docker ps`), lalu buat database-nya (**sekali saja**):

```bash
./scripts/init-db.sh
```

## 4. Setup environment variable

```bash
cp .env.example .env
```

Isi `.env` — untuk dev lokal dengan setup Docker di atas, hampir semua default sudah cocok. Yang **wajib** diganti:
- `JWT_SECRET` — generate dengan `openssl rand -base64 64` (atau `Get-Random` kalau di Windows PowerShell tanpa openssl, cari tool base64 random lain)
- `STEAM_API_KEY` — baru dibutuhkan mulai milestone `steam/` (belum sekarang), boleh diisi placeholder dulu

VSCode tidak otomatis baca `.env` untuk `mvn` biasa. Cara paling gampang: **export ke shell sebelum run**, atau pakai extension **"DotENV"** agar `.env` ke-load, atau isi env var lewat `launch.json` (lihat langkah 5).

## 5. Run & test dari VSCode

- **Test:** buka `GlobalExceptionHandlerTest.java` di `src/test/java/...`, klik ikon ▶️ run test di sebelah nama class/method (muncul otomatis dari Test Runner extension). Atau dari terminal: `mvn test` (setelah export env var, meski milestone 1 test tidak butuh DB/Redis karena murni unit test exception handler).
- **Run app:** mulai Milestone 2, app sudah bisa run penuh (entity `User`/`Role`/`UserRole` + 4 endpoint auth sudah ada). Flyway otomatis membuat tabel dan seed data role saat startup pertama — tidak perlu jalankan SQL manual apapun selain `./scripts/init-db.sh` (yang hanya membuat database kosongnya).
  ```bash
  export $(cat .env | xargs)   # load .env ke shell (macOS/Linux)
  mvn spring-boot:run
  ```
  Windows PowerShell: pakai extension DotENV atau set manual tiap `$env:DB_URL="..."` dst.

  Test cepat setelah app jalan (`http://localhost:8080`):
  ```bash
  curl -X POST http://localhost:8080/api/v1/auth/register \
    -H "Content-Type: application/json" \
    -d '{"email":"test@example.com","password":"Passw0rd1","displayName":"Test User"}'
  ```

## Ringkasan alur kerja per milestone selanjutnya

Tidak perlu setup ulang — Docker + `.env` di atas dipakai terus sampai project selesai. Tiap milestone baru = saya kirim file baru/update, Anda tinggal `git pull`/replace file, lalu `mvn test` lagi.
