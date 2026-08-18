#!/usr/bin/env bash
# =============================================================================
# MCP 凭据离线加密（ADR-012 第 6~7 条 / REQ-MCP-004 / AC-MCP-007）
#
# 🔴 为什么是"离线"：ADR-012 已否决"应用内提供加密接口"（在生产暴露一个可提交明文的
#    端点 = 新攻击面，明文会经过 HTTP、Servlet 日志与异常链路）。
#    工具位于 backend/src/test，Maven 不会把它打进生产 JAR。
#
# 🔴 本工具只加密，不提供解密（不给"验证一下明文对不对"的回显后门）。
# 🔴 明文只从 stdin 读，不作为命令行参数 —— 否则会进入 shell history 与 ps 输出。
#
# 用法：
#   ./encrypt-mcp-credential.sh <tenantId> <mcpKey>
#   # 交互式输入：第 1 行 = 凭据明文；第 2 行 = app.crypto.secret
#   # 或管道：printf '%s\n%s\n' "$PLAIN" "$SECRET" | ./encrypt-mcp-credential.sh gift crm
#
# 输出（仅三项，绝不含明文）：
#   credential_cipher=v1:{base64url(iv)}:{base64url(ct||tag)}
#   credential_last4=9f2c
#   credential_key_version=1
#
# 随后由 DBA 写库（🔴 SQL 中不含明文，binlog 与 SQL 审计日志天然安全）：
#   UPDATE mcp_servers
#      SET credential_cipher='<cipher>', credential_last4='<last4>',
#          credential_key_version=1, credential_updated_at=UTC_TIMESTAMP(3)
#    WHERE tenant_id='<tenantId>' AND mcp_key='<mcpKey>';
#
# 🔴 AAD 绑定副作用：密文与 (tenantId, mcpKey) 绑定。改了其中任一项必须重新加密，
#    否则解密失败会表现为 30060（校验入口）/ 30052（运行时调用）。
# =============================================================================
set -euo pipefail

if [[ $# -lt 2 ]]; then
  sed -n '2,28p' "$0"
  exit 2
fi

TENANT_ID="$1"
MCP_KEY="$2"
BACKEND_DIR="$(cd "$(dirname "$0")/.." && pwd)"

cd "${BACKEND_DIR}"

# 编译测试类（离线工具位于 src/test）并解析 classpath
mvn -q -B test-compile
mvn -q -B dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test

java -cp "target/classes:target/test-classes:$(cat target/cp.txt)" \
  com.eyes.albedo.tools.McpCredentialEncryptTool --tenant "${TENANT_ID}" --mcp-key "${MCP_KEY}"
