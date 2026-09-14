#!/usr/bin/env bash
# Reload the configured catalog, then print the instance's in-memory status.
set -euo pipefail
set +x

usage() {
  cat <<'EOF'
Usage: bash scripts/reload-region-catalog.sh [BASE_URL]

BASE_URL defaults to http://localhost:8081/api/wsbd.
Set REGION_CATALOG_RELOAD_TOKEN to the token configured on the server.

Example (from the repository root):
  source build/local/region-catalog-reload.env
  bash scripts/reload-region-catalog.sh

Remote instance:
  bash scripts/reload-region-catalog.sh https://instance-host/api/wsbd

The server rereads its application configuration and JSON. No file is uploaded.
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi
if (( $# > 1 )); then
  usage >&2
  exit 1
fi
: "${REGION_CATALOG_RELOAD_TOKEN:?Set the server token first (or source build/local/region-catalog-reload.env)}"
base_url="${1:-http://localhost:8081/api/wsbd}"
base_url="${base_url%/}"
case "$base_url" in
  http://*|https://*) ;;
  *) printf 'BASE_URL must start with http:// or https://\n' >&2; exit 1 ;;
esac
if [[ "$REGION_CATALOG_RELOAD_TOKEN" == *$'\n'* || "$REGION_CATALOG_RELOAD_TOKEN" == *$'\r'* ]]; then
  printf 'The token must not contain newlines.\n' >&2
  exit 1
fi

response_file="$(mktemp)"
trap 'rm -f "$response_file"' EXIT

request() {
  local method="$1" operation="$2" http_code
  printf '\n%s %s/internal/regions/%s\n' "$method" "$base_url" "$operation"
  # Read the header from stdin so the token is not included in curl's arguments.
  if ! http_code="$(printf 'Authorization: Bearer %s\n' "$REGION_CATALOG_RELOAD_TOKEN" |
    curl --silent --show-error --connect-timeout 5 --max-time 30 \
      --request "$method" --header @- --output "$response_file" \
      --write-out '%{http_code}' "$base_url/internal/regions/$operation")"; then
    printf 'Request failed.\n' >&2
    return 1
  fi
  cat "$response_file"
  printf '\nHTTP %s\n' "$http_code"
  [[ "$http_code" == "200" ]]
}

result=0
request POST reload || result=1
# Check status even if reload failed or its response was lost.
request GET status || result=1
exit "$result"
