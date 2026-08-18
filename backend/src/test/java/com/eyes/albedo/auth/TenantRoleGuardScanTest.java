package com.eyes.albedo.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;

/**
 * 🔴 <b>{@code @TenantRole} 程序化兜底守护扫描</b>
 * （api-spec §3 / §8.3 D4 · architecture.md §8.2.1 纪律 6 · AR-018，<b>安全等级</b>）。
 *
 * <p><b>🔴 为什么必须机械守护而不是靠评审</b>：
 * <pre>
 * TenantRoleAspect 由 EyesAuthConfig 装配，后者带
 * @ConditionalOnProperty(prefix="eyes-auth", name="enabled", havingValue="true")。
 * 👉 开关为 false 时（test profile 即如此，🔴 生产亦可能误配）切面**整个不注册**：
 *    只写了 @TenantRole 的端点会以 code=0 返回租户数据，
 *    🔴 **不抛异常、不打 ERROR、用例还会"通过"** —— 这是最难被发现的一类越权。
 * 因此把"每个 @TenantRole 端点都必须有程序化兜底"这件事本身钉在编译产物上：
 *   新增端点忘了兜底 → 本测试变红（而不是等生产事故）。
 * </pre>
 *
 * <p><b>扫描口径（两步，缺一不可）</b>：
 * <ol>
 *   <li><b>反射发现</b>：扫 {@code com.eyes.albedo} 下全部 {@code class} 文件，
 *       取<b>方法级或类级</b>标注 {@link TenantRole} 的 public 方法（即受注解保护的端点集合）；</li>
 *   <li><b>兜底证明</b>：读该类的源码，按<b>类内调用图</b>做闭包 ——
 *       方法体直接出现 {@code TenantRoleGuard} 调用，<b>或</b>调用了本类内某个（传递地）
 *       调用了它的方法，即视为已兜底。🔴 只认 {@link TenantRoleGuard}
 *       这一个入口（禁止各控制器各写一份内联判定，architecture.md §8.2.1 纪律 5）。</li>
 * </ol>
 * 🔴 反射能发现"有哪些端点"，但看不到方法体；源码扫描能看方法体，但拿不到注解继承关系 ——
 * 两者结合才既不漏端点、也不误判兜底。
 */
class TenantRoleGuardScanTest {

    private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");
    private static final String BASE_PACKAGE = "com.eyes.albedo";

    /** 🔴 唯一合法的兜底入口特征（api-spec §3 落地基线）。 */
    private static final List<String> GUARD_CALLS = List.of(
            "tenantRoleGuard.require", "TenantRoleGuard.require", "guard.require");

    @Test
    @DisplayName("🔴 AR-018：每个 @TenantRole 端点都必须调用 TenantRoleGuard 做程序化 fail-closed 兜底")
    void everyTenantRoleEndpointHasProgrammaticGuard() throws IOException {
        Map<Class<?>, List<Method>> protectedMethods = scanTenantRoleMethods();
        assertFalse(protectedMethods.isEmpty(),
                "🔴 未扫描到任何 @TenantRole 端点：扫描器失效比"
                        + "\"没有端点\"更危险（会让本守护测试永远绿）");

        List<String> violations = new ArrayList<>();
        int endpoints = 0;
        for (Map.Entry<Class<?>, List<Method>> entry : protectedMethods.entrySet()) {
            Class<?> type = entry.getKey();
            Set<String> guarded = guardedMethodNames(type);
            for (Method method : entry.getValue()) {
                endpoints++;
                if (!guarded.contains(method.getName())) {
                    violations.add(type.getSimpleName() + "#" + method.getName());
                }
            }
        }
        assertTrue(violations.isEmpty(), "🔴 以下 @TenantRole 端点缺少 TenantRoleGuard 程序化兜底"
                + "（eyes-auth.enabled=false 时会静默越权，等级：安全）：" + violations);
        // 口径留痕：当前受保护端点数（新增端点必须同步兜底，否则上面的断言直接红）
        assertTrue(endpoints >= 4,
                "🔴 受 @TenantRole 保护的端点数异常偏少（实得 " + endpoints
                        + "）：疑似注解被误删，请核对 api-spec §7.1.1 接口总表");
    }

    @Test
    @DisplayName("🔴 兜底判定的有效性自证：故意去掉兜底的反例必须被判为违规")
    void scannerRejectsMissingGuard() {
        // 🔴 用"人造反例"证明扫描器真的会红（否则一个永远绿的守护测试比没有更糟）：
        //    reference 源码里没有任何 TenantRoleGuard 调用 → 闭包为空 → 端点必然被判违规
        String source = """
                class FakeController {
                    @Permission(PermissionEnum.USER)
                    @TenantRole({TenantRoleEnum.TENANT_ADMIN})
                    public Result<String> leak() {
                        return Result.success("tenant data");
                    }
                }
                """;
        assertTrue(guardedMethodNames(source).isEmpty(),
                "🔴 纯注解（无程序化兜底）必须被判为未兜底");

        String fixed = source.replace("return Result.success(\"tenant data\");",
                "tenantRoleGuard.require(TenantRoleEnum.TENANT_ADMIN);"
                        + " return Result.success(\"tenant data\");");
        assertTrue(guardedMethodNames(fixed).contains("leak"),
                "🔴 补上 TenantRoleGuard 调用后必须被判为已兜底");
    }

    @Test
    @DisplayName("🔴 §8.2.1 纪律 5：兜底判定只有一份实现（控制器不得内联 ensureMembership）")
    void noInlineMembershipChecksInControllers() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String name = file.getFileName().toString();
            if (!name.endsWith("Controller.java")) {
                continue;
            }
            String content = read(file);
            if (content.contains("ensureMembership(")) {
                violations.add(name + " 内联调用了 ensureMembership（应统一走 TenantRoleGuard）");
            }
        }
        assertTrue(violations.isEmpty(),
                "🔴 租户内角色判定必须只有一份实现（TenantRoleGuard）：" + violations);
    }

    // ===================== 扫描实现 =====================

    /**
     * 反射发现受 {@link TenantRole} 保护的方法（方法级注解 + 类级注解都算）。
     */
    private Map<Class<?>, List<Method>> scanTenantRoleMethods() throws IOException {
        Map<Class<?>, List<Method>> result = new LinkedHashMap<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory readerFactory = new CachingMetadataReaderFactory(resolver);
        Resource[] resources = resolver.getResources(
                "classpath*:" + BASE_PACKAGE.replace('.', '/') + "/**/*.class");
        for (Resource resource : resources) {
            String className = readerFactory.getMetadataReader(resource)
                    .getClassMetadata().getClassName();
            Class<?> type;
            try {
                type = Class.forName(className, false, getClass().getClassLoader());
            } catch (Throwable ignored) {
                // 无法加载（如 Lombok 生成的内部类）不影响扫描目标（Controller 均可加载）
                continue;
            }
            boolean typeAnnotated = type.isAnnotationPresent(TenantRole.class);
            List<Method> methods = new ArrayList<>();
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic()) {
                    continue;
                }
                if (method.isAnnotationPresent(TenantRole.class) || typeAnnotated) {
                    methods.add(method);
                }
            }
            if (!methods.isEmpty()) {
                result.put(type, methods);
            }
        }
        return result;
    }

    /**
     * 该类中"（传递地）做了程序化兜底"的方法名集合。
     */
    private Set<String> guardedMethodNames(Class<?> type) {
        Path source = SOURCE_ROOT.resolve(
                Paths.get(type.getName().replace('.', '/').split("\\$")[0] + ".java"));
        if (!Files.exists(source)) {
            return Set.of();
        }
        try {
            return guardedMethodNames(read(source));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取源码：" + source, e);
        }
    }

    /**
     * 类内调用图闭包：直接调用 Guard 的方法 → 调用它们的方法 → …（迭代到不动点）。
     *
     * <p>🔴 为什么需要闭包：{@code McpAdminController} 的端点方法调用私有 {@code require(mcpId)}，
     * 后者才调用 {@code authorize()} → Guard。只看端点方法体会产生<b>假阳性</b>，
     * 而假阳性会促使后来者放宽规则 —— 那才是真正的风险（同
     * {@code TenantIsolationScanTest} 剔除注释的理由）。
     */
    private Set<String> guardedMethodNames(String source) {
        Map<String, String> bodies = methodBodies(source);
        Set<String> guarded = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : bodies.entrySet()) {
            if (GUARD_CALLS.stream().anyMatch(call -> entry.getValue().contains(call))) {
                guarded.add(entry.getKey());
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, String> entry : bodies.entrySet()) {
                if (guarded.contains(entry.getKey())) {
                    continue;
                }
                for (String guardedName : new HashSet<>(guarded)) {
                    if (entry.getValue().contains(guardedName + "(")) {
                        guarded.add(entry.getKey());
                        changed = true;
                        break;
                    }
                }
            }
        }
        return guarded;
    }

    /**
     * 粗粒度提取「方法名 → 方法体」（按大括号配平；重载合并为一体，对本判定足够）。
     */
    private Map<String, String> methodBodies(String source) {
        Map<String, String> bodies = new HashMap<>();
        Matcher matcher = Pattern.compile(
                        "(?m)^\\s*(?:public|private|protected)\\s+[^;=(){}]*?\\b(\\w+)\\s*\\([^;{}]*\\)"
                                + "(?:\\s*throws\\s+[\\w., ]+)?\\s*\\{")
                .matcher(source);
        while (matcher.find()) {
            String name = matcher.group(1);
            int start = source.indexOf('{', matcher.end() - 1);
            if (start < 0) {
                continue;
            }
            int depth = 0;
            int i = start;
            while (i < source.length()) {
                char c = source.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
                i++;
            }
            String body = source.substring(start, Math.min(i + 1, source.length()));
            bodies.merge(name, body, (a, b) -> a + "\n" + b);
        }
        return bodies;
    }

    private List<Path> javaFiles() throws IOException {
        try (Stream<Path> stream = Files.walk(SOURCE_ROOT)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
    }

    /**
     * 读取源码并<b>剔除注释</b>（本项目 Javadoc 大量引用被禁写法作为反面示例，
     * 连注释一起扫会产生假阳性）。
     */
    private String read(Path path) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        String withoutBlockComments = raw.replaceAll("(?s)/\\*.*?\\*/", " ");
        return withoutBlockComments.replaceAll("(?m)^\\s*//.*$", " ");
    }
}
