# Dropfolio Backend

Dropfolio adalah backend untuk aplikasi pelacakan portfolio item CS2 (Counter-Strike 2) yang dibangun menggunakan Spring Boot 3.3.x dan Java 21 dengan arsitektur modular monolith.

## Status

### MVP Status

✅ Milestone 1 — Common Infrastructure
✅ Milestone 2 — Authentication & User Management
✅ Milestone 3 — Item Catalog
✅ Milestone 4 — Portfolio
✅ Milestone 5 — Drops
✅ Milestone 6 — Steam Integration
✅ Milestone 7 — Price Tracking & Scheduler
✅ Milestone 8 — Notifications & Email Worker
✅ Milestone 9 — Admin Module
✅ Milestone 10 — Production Readiness

### Test Status

```bash
346 tests
0 failures
0 errors
BUILD SUCCESS
```

### CI Status

GitHub Actions menjalankan:

```text
Push / Pull Request
        ↓
Maven Test
        ↓
Docker Build
        ↓
PASS
```

---

# Tech Stack

| Component        | Technology            |
| ---------------- | --------------------- |
| Language         | Java 21               |
| Framework        | Spring Boot 3.3.x     |
| Database         | Microsoft SQL Server  |
| Cache            | Redis                 |
| Migration        | Flyway                |
| Security         | Spring Security + JWT |
| Email            | SMTP + Mailpit        |
| Scheduler        | Spring Scheduling     |
| Resilience       | Resilience4j          |
| Containerization | Docker                |
| CI/CD            | GitHub Actions        |

---

# Project Structure

```text
backend
├── src/main/java
├── src/main/resources
│   ├── db/migration
│   └── application.yml
├── src/test/java
├── scripts
├── .github/workflows
├── Dockerfile
├── docker-compose.yml
├── .env.example
├── pom.xml
└── README.md
```

---

# Local Development

## Requirements

* Java 21
* Docker Desktop
* Git

## Start Dependencies

```bash
docker compose up -d
```

Services:

| Service      | Port |
| ------------ | ---- |
| MSSQL        | 1433 |
| Redis        | 6379 |
| Mailpit SMTP | 1025 |
| Mailpit UI   | 8025 |

---

## Configure Environment

```bash
cp .env.example .env
```

Isi minimal:

```env
JWT_SECRET=your-secret
```

---

## Run Application

```bash
mvn spring-boot:run
```

atau

```bash
docker compose up -d
```

---

## Run Tests

```bash
mvn clean test
```

---

# Docker

## Build Image

```bash
docker build -t dropfolio-backend .
```

## Run Container

```bash
docker compose up -d
```

## Health Check

```bash
curl http://localhost:8080/actuator/health
```

Expected:

```json
{
  "status": "UP"
}
```

---

# API Endpoints

Base URL:

```text
/api/v1
```

---

## Authentication

| Method | Endpoint       |
| ------ | -------------- |
| POST   | /auth/register |
| POST   | /auth/login    |
| POST   | /auth/refresh  |
| POST   | /auth/logout   |

---

## User

| Method | Endpoint             |
| ------ | -------------------- |
| GET    | /users/me            |
| PATCH  | /users/me            |
| DELETE | /users/me            |
| POST   | /users/me/password   |
| POST   | /users/me/logout-all |

---

## Items

| Method | Endpoint    |
| ------ | ----------- |
| GET    | /items      |
| GET    | /items/{id} |
| POST   | /items      |
| PATCH  | /items/{id} |

---

## Drops

| Method | Endpoint    |
| ------ | ----------- |
| GET    | /drops      |
| GET    | /drops/{id} |
| POST   | /drops      |
| PATCH  | /drops/{id} |
| DELETE | /drops/{id} |

---

## Portfolio

| Method | Endpoint             |
| ------ | -------------------- |
| GET    | /portfolio/summary   |
| GET    | /portfolio/breakdown |
| GET    | /portfolio/export    |

---

## Alerts

| Method | Endpoint     |
| ------ | ------------ |
| GET    | /alerts      |
| GET    | /alerts/{id} |
| POST   | /alerts      |
| PATCH  | /alerts/{id} |
| DELETE | /alerts/{id} |

---

## Notifications

| Method | Endpoint                   |
| ------ | -------------------------- |
| GET    | /notifications             |
| PATCH  | /notifications/{id}/read   |
| PATCH  | /notifications/read-all    |
| GET    | /notifications/preferences |
| PATCH  | /notifications/preferences |

---

## Pricing

| Method | Endpoint         |
| ------ | ---------------- |
| GET    | /prices/{itemId} |

---

## Admin

| Method | Endpoint                    |
| ------ | --------------------------- |
| GET    | /admin/dashboard            |
| GET    | /admin/users                |
| GET    | /admin/users/{id}           |
| PATCH  | /admin/users/{id}/status    |
| GET    | /admin/audit-logs           |
| GET    | /admin/sync-jobs            |
| GET    | /admin/sync-jobs/{id}       |
| POST   | /admin/sync-jobs/price-sync |

---

# Monitoring

## Actuator

```text
GET /actuator/health
```

Health checks:

* Database
* Redis
* SMTP
* Disk Space

---

# Mailpit

SMTP:

```text
localhost:1025
```

Web UI:

```text
http://localhost:8025
```

---

# CI/CD

GitHub Actions:

```text
Push
 ↓
Maven Test
 ↓
Docker Build
 ↓
Success
```

Workflow file:

```text
.github/workflows/backend-ci.yml
```

---

# Deployment Roadmap

## Phase 1

✅ Dockerfile
✅ .dockerignore
✅ env.example
✅ Docker Compose

## Phase 2

✅ GitHub Actions
✅ Maven Test
✅ Docker Build

## Phase 3

⬜ Azure App Service
⬜ Azure SQL Database
⬜ Azure Redis

## Phase 4

⬜ Staging Deployment

## Phase 5

⬜ Production Hardening

---

# License

Private project — Dropfolio.
