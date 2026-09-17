# Dropfolio Deployment Guide

Panduan deployment untuk Dropfolio Backend.

---

# Deployment Stages

Project ini menggunakan pendekatan bertahap:

```text
Local Development
        ↓
Docker
        ↓
GitHub Actions
        ↓
Azure Staging
        ↓
Azure Production
```

---

# 1. Local Development

## Start Infrastructure

Jalankan seluruh dependency lokal:

```bash
docker compose up -d
```

Container yang akan aktif:

| Service      | Port |
| ------------ | ---- |
| MSSQL        | 1433 |
| Redis        | 6379 |
| Mailpit SMTP | 1025 |
| Mailpit UI   | 8025 |

Verifikasi:

```bash
docker ps
```

Semua container harus berstatus:

```text
healthy
```

atau

```text
up
```

---

## Run Backend

### Menggunakan Maven

```bash
mvn spring-boot:run
```

### Menggunakan Docker

Build image:

```bash
docker build -t dropfolio-backend .
```

Run:

```bash
docker run -p 8080:8080 dropfolio-backend
```

---

# 2. Health Check

Endpoint health:

```text
http://localhost:8080/actuator/health
```

Test:

```bash
curl http://localhost:8080/actuator/health
```

Expected:

```json
{
  "status": "UP"
}
```

Komponen yang diperiksa:

* SQL Server
* Redis
* SMTP
* Disk Space

---

# 3. Mailpit

SMTP lokal:

```text
localhost:1025
```

Web UI:

```text
http://localhost:8025
```

Digunakan untuk development dan testing email.

---

# 4. Docker Deployment

## Build

```bash
docker build -t dropfolio-backend .
```

## Verify

```bash
docker images
```

Harus muncul:

```text
dropfolio-backend
```

---

## Run

```bash
docker run \
-p 8080:8080 \
--env-file .env \
dropfolio-backend
```

---

# 5. GitHub Actions

Workflow:

```text
.github/workflows/backend-ci.yml
```

Pipeline:

```text
Push / Pull Request
        ↓
Checkout
        ↓
Java 21
        ↓
Maven Test
        ↓
Docker Build
        ↓
PASS
```

Verifikasi:

```bash
mvn clean test
```

Expected:

```text
BUILD SUCCESS
```

---

# 6. Environment Variables

Buat file:

```text
.env
```

Berdasarkan:

```text
.env.example
```

Variabel penting:

```env
DB_URL=
DB_USERNAME=
DB_PASSWORD=

REDIS_HOST=
REDIS_PORT=

JWT_SECRET=

SMTP_HOST=
SMTP_PORT=
SMTP_USERNAME=
SMTP_PASSWORD=
```

Jangan commit file `.env`.

---

# 7. Azure Deployment (Planned)

Deployment target:

## Resource Group

```text
dropfolio-rg
```

## Azure SQL Database

Menyimpan seluruh data aplikasi.

## Azure Cache for Redis

Caching dan refresh-token storage.

## Azure App Service

Menjalankan container backend.

## Azure Storage

Backup dan export file.

---

# 8. Staging Verification Checklist

Setelah deploy ke Azure:

```text
□ App Service running
□ SQL connected
□ Redis connected
□ Flyway executed
□ Scheduler active
□ Email Worker active
□ Health endpoint UP
```

Health endpoint:

```text
https://<domain>/actuator/health
```

---

# 9. Production Checklist

Sebelum go-live:

```text
□ HTTPS enabled
□ Custom domain configured
□ Azure SQL backups enabled
□ Redis persistence configured
□ SMTP production account configured
□ Logging configured
□ Monitoring configured
□ Alerts configured
```

---

# 10. Rollback Strategy

Jika deployment gagal:

1. Stop deployment.
2. Re-deploy image versi sebelumnya.
3. Verifikasi health endpoint.
4. Verifikasi database migration.
5. Verifikasi scheduler dan email worker.

---

# Troubleshooting

## Health Endpoint DOWN

Cek:

```bash
docker logs dropfolio-backend
```

Lalu:

```bash
curl http://localhost:8080/actuator/health
```

Periksa komponen:

* db
* redis
* mail

---

## Database Connection Error

Verifikasi:

```bash
docker ps
```

Pastikan:

```text
dropfolio-mssql
```

berstatus healthy.

---

## Redis Connection Error

Verifikasi:

```bash
docker ps
```

Pastikan:

```text
dropfolio-redis
```

berstatus healthy.

---

## SMTP Connection Error

Verifikasi:

```bash
docker ps
```

Pastikan:

```text
dropfolio-mailpit
```

berstatus running.

---

# Current Status

```text
Phase 1 — Docker            ✅ Complete
Phase 2 — CI/CD             ✅ Complete
Phase 3 — Azure Setup       ⬜ Pending
Phase 4 — Staging Deploy    ⬜ Pending
Phase 5 — Production        ⬜ Pending
```

# SIMPLIFIED version:

# Local Setup

docker compose up -d

# Health Check

http://localhost:8080/actuator/health

# Mailpit

http://localhost:8025

# Stop

docker compose down