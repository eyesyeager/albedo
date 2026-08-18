#!/usr/bin/env bash
# =============================================================================
# 配置校验命令（ADR-013 ① 的落地形态，api-spec §7.3.1）
#
# 用途：一期没有管理后台，租户级配置由 DBA 直接写库（DEC-010）。
#      本脚本让 DBA 在<b>改库前后</b>都能一条命令自查，避免"用户成为第一个发现
#      配置错误的人"（AC-CFG-003）。
#
# 用法：
#   ./validate-config.sh <host> <objectType> <objectId> [token] [baseUrl]
#
#   host        租户 Host（决定租户上下文，如 albedo-gift.eyescode.top）
#   objectType  agent | agentVersion | skill | skillVersion | mcp | localTool
#               | toolGrant | siteConfig
#   objectId    对象 ID
#   token       eyesUser JWT（默认取环境变量 ALBEDO_TOKEN）
#   baseUrl     服务地址（默认 http://127.0.0.1:8080）
#
# 权限：租户作用域对象需租户 TENANT_ADMIN / TENANT_OPERATOR；
#      objectType=localTool 需平台管理员（eyesUser role=ADMIN）。
#
# 退出码：0 = 校验通过（code=0）；1 = 校验失败或请求失败（含 30060）
# =============================================================================
set -euo pipefail

if [[ $# -lt 3 ]]; then
  sed -n '2,25p' "$0"
  exit 2
fi

HOST="$1"
OBJECT_TYPE="$2"
OBJECT_ID="$3"
TOKEN="${4:-${ALBEDO_TOKEN:-}}"
BASE_URL="${5:-http://127.0.0.1:8080}"

if [[ -z "${TOKEN}" ]]; then
  echo "错误：缺少 token（第 4 个参数或环境变量 ALBEDO_TOKEN）" >&2
  exit 2
fi

RESPONSE=$(curl -sS -X POST "${BASE_URL}/api/v1/admin/config/validate" \
  -H "Host: ${HOST}" \
  -H "authorization: ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"objectType\":\"${OBJECT_TYPE}\",\"objectId\":\"${OBJECT_ID}\",\"includeReferences\":true}")

echo "${RESPONSE}"

# /api/v1/** 恒 HTTP 200，业务结果由 code 承载（框架 §14.1），故只判 code
CODE=$(printf '%s' "${RESPONSE}" | sed -n 's/.*"code"[[:space:]]*:[[:space:]]*\([0-9-]*\).*/\1/p' | head -1)
if [[ "${CODE}" == "0" ]]; then
  echo "✅ 校验通过（warnings 不阻断，请仍然人工过一遍）"
  exit 0
fi
echo "❌ 校验未通过：code=${CODE}（30060 表示配置非法，见 data.violations[]）" >&2
exit 1
