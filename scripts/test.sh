#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
container="glicnote-api-test-$$"
cleanup() { docker rm -f "$container" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM
docker run --rm -d --name "$container" -p 127.0.0.1::5432 \
  -e POSTGRES_DB=glicnote -e POSTGRES_USER=glicnote -e POSTGRES_PASSWORD=test-only-password \
  postgres:17-alpine >/dev/null
for attempt in $(seq 1 40); do
  if docker exec "$container" pg_isready -U glicnote -d glicnote >/dev/null 2>&1; then break; fi
  if [ "$attempt" = 40 ]; then docker logs "$container"; exit 1; fi
  sleep 1
done
docker exec "$container" psql -U glicnote -d glicnote -v ON_ERROR_STOP=1 -c '
  CREATE ROLE anon NOLOGIN;
  CREATE ROLE authenticated NOLOGIN;
  CREATE ROLE service_role NOLOGIN;
  ALTER DEFAULT PRIVILEGES GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO anon, authenticated, service_role;
' >/dev/null
port=$(docker inspect -f '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}' "$container")
export TEST_DB_URL="jdbc:postgresql://127.0.0.1:$port/glicnote"
export TEST_DB_USERNAME=glicnote
export TEST_DB_PASSWORD=test-only-password
./mvnw --batch-mode verify "$@"
