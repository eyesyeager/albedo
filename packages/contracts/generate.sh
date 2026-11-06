#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT/backend"

if [ ! -x .venv/bin/python ]; then
  echo "backend/.venv missing: run 'uv sync' first" >&2
  exit 1
fi

.venv/bin/python scripts/export_openapi.py

if [ "${1:-}" = "--check" ] && ! git diff --quiet -- packages/contracts/openapi.json; then
  echo "OpenAPI 契约快照已过期，请重新运行 packages/contracts/generate.sh" >&2
  exit 1
fi
