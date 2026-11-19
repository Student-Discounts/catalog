#!/usr/bin/env bash
set -euo pipefail
: "${CATALOG_SCHEMA_REGISTRY_URL:?Set CATALOG_SCHEMA_REGISTRY_URL}"
registry="${CATALOG_SCHEMA_REGISTRY_URL%/}"
subject="catalog.search.events-value"
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
jq -n --rawfile schema "$root/src/main/resources/contracts/catalog-changed.schema.json" '{schemaType:"JSON",schema:$schema}' > "$work/schema.json"
curl --fail-with-body --silent --show-error --connect-timeout 3 --max-time 15 \
  -X PUT -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  --data '{"compatibility":"BACKWARD_TRANSITIVE"}' "$registry/config/$subject"
curl --fail-with-body --silent --show-error --connect-timeout 3 --max-time 15 \
  -X POST -H 'Content-Type: application/vnd.schemaregistry.v1+json' \
  --data-binary "@$work/schema.json" "$registry/subjects/$subject/versions?normalize=true"
