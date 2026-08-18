package com.eyes.albedo.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import com.eyes.albedo.mcp.CredentialCipher;

/**
 * MCP 凭据<b>离线</b>加密工具（ADR-012 第 6 条 / REQ-MCP-004 / AC-MCP-007）。
 *
 * <p>🔴 <b>为什么位于 {@code src/test}</b>：Maven 不把测试类打进 jar，
 * {@code spring-boot-maven-plugin} 的可执行 jar 只包含 {@code target/classes}，
 * 因此本类<b>绝不进入生产 JAR</b>，也不注册任何 Bean、不暴露任何 HTTP 端点。
 * （ADR-012 方案 C —— "应用内提供加密接口" —— 已被否决：在生产暴露一个可提交明文的端点
 * 等于新增攻击面，明文会经过 HTTP、Servlet 日志与异常链路。）
 * 该约束由 {@code OfflineToolPackagingTest} 自动化守护。
 *
 * <p>🔴 <b>只加密，不提供解密子命令</b>：不给"验证一下明文对不对"的回显后门。
 *
 * <p><b>用法</b>（明文<b>只从 stdin</b> 读，避免进入 shell history / ps 输出）：
 * <pre>
 *   cd backend
 *   mvn -q test-compile
 *   printf '%s\n%s\n' "&lt;明文凭据&gt;" "&lt;app.crypto.secret&gt;" \
 *     | java -cp target/classes:target/test-classes:$(cat target/cp.txt) \
 *            com.eyes.albedo.tools.McpCredentialEncryptTool --tenant gift --mcp-key crm
 *   # 或使用封装脚本：backend/scripts/encrypt-mcp-credential.sh gift crm
 * </pre>
 *
 * <p><b>输出</b>（🔴 仅三项，绝不含明文；无临时文件、无日志）：
 * <pre>
 *   credential_cipher=v1:xxxx:yyyy
 *   credential_last4=9f2c
 *   credential_key_version=1
 * </pre>
 *
 * <p>DBA 写库（🔴 SQL 中不含明文，故 binlog 与 SQL 审计日志天然安全）：
 * <pre>
 *   UPDATE mcp_servers SET credential_cipher=?, credential_last4=?, credential_key_version=1,
 *          credential_updated_at=UTC_TIMESTAMP(3)
 *    WHERE tenant_id=? AND mcp_key=?;
 * </pre>
 * 之后调用 {@code POST /api/v1/admin/mcp/{id}/test} 确认生效（M3 第二阶段提供）。
 *
 * <p>🔴 AAD 绑定副作用：{@code tenant_id} 或 {@code mcp_key} 变更后<b>必须重新加密写入</b>，
 * 否则解密失败表现为 {@code 30060}（校验入口）/ {@code 30052}（运行时调用）。
 */
public final class McpCredentialEncryptTool {

    private McpCredentialEncryptTool() {
    }

    public static void main(String[] args) throws IOException {
        String tenantId = argValue(args, "--tenant");
        String mcpKey = argValue(args, "--mcp-key");
        String secret = argValue(args, "--secret");

        if (isBlank(tenantId) || isBlank(mcpKey)) {
            printUsage();
            System.exit(2);
            return;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String plaintext = reader.readLine();
            if (isBlank(secret)) {
                secret = reader.readLine();
            }
            if (isBlank(plaintext)) {
                // 🔴 异常信息不含任何输入片段
                System.err.println("错误：未从 stdin 读到凭据明文");
                System.exit(2);
                return;
            }
            if (isBlank(secret)) {
                System.err.println("错误：未提供 app.crypto.secret（--secret 或 stdin 第二行）");
                System.exit(2);
                return;
            }

            CredentialCipher cipher = new CredentialCipher(secret);
            String encrypted = cipher.encrypt(plaintext, tenantId.trim(), mcpKey.trim());
            String last4 = CredentialCipher.last4(plaintext);

            // 🔴 只输出密文 / last4 / keyVersion —— 不回显明文、不回显 secret、不写任何文件
            System.out.println("credential_cipher=" + encrypted);
            System.out.println("credential_last4=" + last4);
            System.out.println("credential_key_version=" + CredentialCipher.CURRENT_KEY_VERSION);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("错误：" + e.getMessage());
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.err.println("用法：McpCredentialEncryptTool --tenant <tenantId> --mcp-key <mcpKey> [--secret <secret>]");
        System.err.println("明文只从 stdin 第一行读取；未给 --secret 时从 stdin 第二行读取。");
        System.err.println("🔴 本工具只加密，不提供解密；输出不含明文。");
    }

    private static String argValue(String[] args, String name) {
        if (args == null) {
            return null;
        }
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
