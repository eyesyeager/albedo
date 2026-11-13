#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SNAPSHOT="$ROOT/packages/contracts/openapi.json"
CHECK=0
if [ "${1:-}" = "--check" ]; then
  CHECK=1
fi

if [ ! -x "$ROOT/backend/.venv/bin/python" ]; then
  echo "backend/.venv missing: run 'uv sync' first" >&2
  exit 1
fi

# 直接比对导出前后的字节，而不是依赖 `git diff`：后者只比较工作区与 index，
# 且 pathspec 相对当前目录，在 backend/ 下根本匹配不到仓库根的契约文件。
BEFORE="$(mktemp)"
trap 'rm -f "$BEFORE"' EXIT
if [ -f "$SNAPSHOT" ]; then
  cp "$SNAPSHOT" "$BEFORE"
else
  : > "$BEFORE"
fi

(cd "$ROOT/backend" && .venv/bin/python scripts/export_openapi.py)

if [ "$CHECK" = 1 ] && ! cmp -s "$BEFORE" "$SNAPSHOT"; then
  cp "$BEFORE" "$SNAPSHOT"
  echo "OpenAPI 契约快照已过期，请重新运行 packages/contracts/generate.sh" >&2
  exit 1
fi
