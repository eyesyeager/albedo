package com.eyes.albedo.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 离线加密工具的打包边界守卫（ADR-012 第 6 条）。
 *
 * <p>🔴 为什么必须自动化：ADR-012 明确否决了"应用内提供加密接口"（方案 C），
 * 理由是<b>在生产暴露一个可提交明文的端点等于新增攻击面</b>。
 * 这条约束的唯一物理保障就是"工具只存在于 {@code src/test}"——
 * 一旦有人为了方便把它挪进 {@code src/main}，它就会进入生产 JAR。
 * 本用例把该约束固化为可执行断言。
 */
class OfflineToolPackagingTest {

    private static final Path MAIN_ROOT = Paths.get("src", "main", "java");
    private static final Path TEST_ROOT = Paths.get("src", "test", "java");
    private static final String TOOL_CLASS = "McpCredentialEncryptTool";

    @Test
    @DisplayName("🔴 离线加密工具必须只存在于 src/test（绝不进生产 JAR）")
    void offlineToolStaysInTestSources() throws IOException {
        assertTrue(Files.exists(TEST_ROOT.resolve(
                        Paths.get("com", "eyes", "albedo", "tools", TOOL_CLASS + ".java"))),
                "离线加密工具必须位于 src/test/java/com/eyes/albedo/tools/");

        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(MAIN_ROOT)) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.contains(TOOL_CLASS)) {
                violations.add(file.getFileName().toString());
            }
        }
        assertTrue(violations.isEmpty(),
                "生产源码不得引用离线加密工具（会把它拉进生产 JAR）：" + violations);
    }

    @Test
    @DisplayName("🔴 生产源码不得提供「提交明文换密文」或解密回显的 HTTP 端点")
    void noPlaintextEndpointInProduction() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles(MAIN_ROOT)) {
            String content = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            boolean isController = content.contains("@RestController") || content.contains("@Controller");
            if (!isController) {
                continue;
            }
            String lower = content.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("credentialcipher") || lower.contains(".decrypt(")) {
                violations.add(file.getFileName().toString() + "（出现凭据加解密调用）");
            }
            if (lower.contains("plaintext")) {
                violations.add(file.getFileName().toString() + "（出现 plaintext 入参）");
            }
        }
        assertTrue(violations.isEmpty(),
                "🔴 禁止在 Controller 层出现凭据加密/解密调用：" + violations);
    }

    private List<Path> javaFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
    }

    private String stripComments(String raw) {
        String withoutBlock = raw.replaceAll("(?s)/\\*.*?\\*/", " ");
        return withoutBlock.replaceAll("(?m)^\\s*//.*$", " ");
    }
}
