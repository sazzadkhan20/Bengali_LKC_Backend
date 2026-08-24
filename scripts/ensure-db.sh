#!/usr/bin/env bash
# Ensure Docker Desktop Postgres (bengali-lkc-db) is up before IntelliJ / bootRun.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker Desktop is not running."
  echo "Start Docker Desktop, wait until it is ready, then re-run this script."
  if [[ -f "/c/Program Files/Docker/Docker/Docker Desktop.exe" ]]; then
    echo "Attempting to launch Docker Desktop..."
    "/c/Program Files/Docker/Docker/Docker Desktop.exe" >/dev/null 2>&1 &
  fi
  exit 1
fi

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "Created .env from .env.example"
fi

echo "Starting dedicated Postgres container (bengali-lkc-db)..."
docker compose up -d db

echo "Waiting for database health..."
for i in $(seq 1 60); do
  status="$(docker inspect -f '{{.State.Health.Status}}' bengali-lkc-db 2>/dev/null || echo starting)"
  if [[ "$status" == "healthy" ]]; then
    echo "Postgres is healthy on localhost:${POSTGRES_PORT:-5433}"
    docker compose ps
    exit 0
  fi
  sleep 2
done

echo "ERROR: Postgres did not become healthy in time."
docker compose logs db | tail -n 40
exit 1
