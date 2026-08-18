#!/usr/bin/env bash
# =============================================================================
# 缓存失效命令（ADR-013 ② 的落地形态，api-spec §7.2.1）
#
# 用途：DBA 改库后，L1（进程内）/ L2（Redis）在 TTL 内仍返回旧值，表现为"改了没生效"
#      （M1 缺陷 D-003 / D-006 已实测踩坑）。本脚本把"等 TTL"变成"调一次接口即刻生效"。
#
# 用法：
#   ./evict-cache.sh <scope> <reason> [--tenant <tenantId>] [--host <host>]
#                    [--group <configGroup>] [--agent <agentId>]
#                    [--token <jwt>] [--base <baseUrl>]
#
#   scope   tenant | host | sysconfig | agentVersion | all
#   reason  1~200 字符，🔴 审计必需；scope=all 时必须写工单号
#
# 权限：🔴 仅平台管理员（eyesUser role=ADMIN）。
#
# 🔴 本接口不会删除运行时状态键（chat:idem / chat:cancel / tool:confirm / limit:msg），
#    即便 scope=all —— 它们不是缓存，删除会破坏幂等、停止生成、工具确认与限流语义。
#
# 退出码：0 = 全部作用域完成；1 = 部分/全部失败（code=30061，见 data.incompleteScopes[]）
# =============================================================================
set -euo pipefail

if [[ $# -lt 2 ]]; then
  sed -n '2,22p' "$0"
  exit 2
fi

SCOPE="$1"; shift
REASON="$1"; shift
TENANT_ID=""
HOST_ARG=""
CONFIG_GROUP=""
AGENT_ID=""
TOKEN="${ALBEDO_TOKEN:-}"
BASE_URL="http://127.0.0.1:8080"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tenant) TENANT_ID="$2"; shift 2 ;;
    --host)   HOST_ARG="$2"; shift 2 ;;
    --group)  CONFIG_GROUP="$2"; shift 2 ;;
    --agent)  AGENT_ID="$2"; shift 2 ;;
    --token)  TOKEN="$2"; shift 2 ;;
    --base)   BASE_URL="$2"; shift 2 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

if [[ -z "${TOKEN}" ]]; then
  echo "错误：缺少 token（--token 或环境变量 ALBEDO_TOKEN）" >&2
  exit 2
fi

PAYLOAD="{\"scope\":\"${SCOPE}\",\"reason\":\"${REASON}\""
[[ -n "${TENANT_ID}" ]]    && PAYLOAD="${PAYLOAD},\"tenantId\":\"${TENANT_ID}\""
[[ -n "${HOST_ARG}" ]]     && PAYLOAD="${PAYLOAD},\"host\":\"${HOST_ARG}\""
[[ -n "${CONFIG_GROUP}" ]] && PAYLOAD="${PAYLOAD},\"configGroup\":\"${CONFIG_GROUP}\""
[[ -n "${AGENT_ID}" ]]     && PAYLOAD="${PAYLOAD},\"agentId\":\"${AGENT_ID}\""
PAYLOAD="${PAYLOAD}}"

# /api/v1/platform/** 是平台白名单路径，不需要租户 Host
RESPONSE=$(curl -sS -X POST "${BASE_URL}/api/v1/platform/cache/evict" \
  -H "authorization: ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "${PAYLOAD}")

echo "${RESPONSE}"

CODE=$(printf '%s' "${RESPONSE}" | sed -n 's/.*"code"[[:space:]]*:[[:space:]]*\([0-9-]*\).*/\1/p' | head -1)
if [[ "${CODE}" == "0" ]]; then
  echo "✅ 缓存已失效（L1 + L2）；后续请求不会再命中旧值"
  exit 0
fi
echo "❌ 失效未全部完成：code=${CODE}（30061 请按 data.incompleteScopes[] 重试）" >&2
exit 1
