#!/usr/bin/env bash
# Run this ONCE after `docker compose up -d` (and after the mssql container is healthy)
# to create the empty `dropfolio` database. mssql/azure-sql-edge images don't auto-create
# databases the way postgres/mysql images do, so this step is manual.
#
# Usage: ./scripts/init-db.sh

set -euo pipefail

CONTAINER="dropfolio-mssql"
SA_PASSWORD="Dropfolio!DevOnly123"

echo "Waiting for mssql to be healthy..."
until [ "$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null)" = "healthy" ]; do
  sleep 2
  echo "  still waiting..."
done

echo "Creating database 'dropfolio' (if not exists)..."
docker exec -i "$CONTAINER" /opt/mssql-tools/bin/sqlcmd \
  -S localhost -U sa -P "$SA_PASSWORD" \
  -Q "IF DB_ID('dropfolio') IS NULL CREATE DATABASE dropfolio;"

echo "Done. Database 'dropfolio' is ready."
