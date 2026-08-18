package com.eyes.albedo.tenant;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 租户隔离静态扫描（AR-003 的自动化防线）。
 *
 * <p>为什么需要静态扫描而不是只靠评审：Hibernate discriminator 只保护 JPQL / Criteria / 派生查询。
 * 一旦有人写了 {@code nativeQuery = true} 或 {@code JdbcTemplate}，租户条件就<b>静默消失</b>，
 * 而这类退化在功能测试里通常「看起来是对的」（单租户测试数据下结果相同），
 * 只有在生产多租户环境才暴露为跨租户泄露。同理，手拼 Redis key 会绕过
 * {@link TenantCacheKeys} 的租户前缀，造成跨租户缓存命中（RISK-004）。
 *
 * <p>规则：
 * <ol>
 *   <li>禁止 {@code nativeQuery = true}；确有必要须经 @架构师 审批并在此登记白名单，
 *       且 SQL 必须显式包含 {@code tenant_id}</li>
 *   <li>禁止使用 {@code JdbcTemplate} / {@code EntityManager.createNativeQuery}</li>
 *   <li>禁止在 {@link TenantCacheKeys} 之外出现 {@code "albedo:"} 字面量（手拼缓存键）</li>
 * </ol>
 */
class TenantIsolationScanTest {

    private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");

    /** 允许出现缓存键前缀字面量的文件（唯一入口本身）。 */
    private static final Set<String> CACHE_KEY_WHITELIST = Set.of("TenantCacheKeys.java");

    /**
     * 允许使用原生 SQL 的文件（🔴 需 @架构师 审批 + 在 architecture.md 登记）。
     *
     * <p>🔴 当前唯一一项：{@code UserDailyQuotaUsageRepository}（architecture.md §13.5.12
     * 已登记为<b>受控例外</b>）—— 结算需要 {@code INSERT … ON DUPLICATE KEY UPDATE}
     * （JPA 无等价 API），而"先查再写"需要行锁 / 乐观锁重试，会把 SSE 流内的短事务拉长（AR-011）。
     *
     * <p>🔴 <b>白名单不等于豁免</b>：下方 {@link #forbidNativeQuery()} 仍会断言该文件的 SQL
     * <b>文本包含 {@code tenant_id}</b>（Hibernate 不会为原生 SQL 追加 discriminator，
     * 因此租户维度必须显式写在 INSERT 列表与冲突唯一键里）。
     */
    private static final Set<String> NATIVE_QUERY_WHITELIST =
            Set.of("UserDailyQuotaUsageRepository.java");

    @Test
    @DisplayName("禁止原生 SQL：Hibernate 不会为其追加 tenant_id 条件")
    void forbidNativeQuery() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String content = read(file);
            String name = file.getFileName().toString();
            if (NATIVE_QUERY_WHITELIST.contains(name)) {
                // 🔴 白名单文件仍必须手写租户条件（architecture.md §13.5.12 的明文要求）
                if (content.contains("nativeQuery") && !content.contains("tenant_id")) {
                    violations.add(name + " 使用原生 SQL 但未包含 tenant_id 条件");
                }
                continue;
            }
            if (content.replace(" ", "").contains("nativeQuery=true")) {
                violations.add(name + " 使用了 nativeQuery=true（租户条件会静默丢失）");
            }
            if (content.contains("createNativeQuery")) {
                violations.add(name + " 使用了 EntityManager.createNativeQuery");
            }
            if (content.contains("JdbcTemplate")) {
                violations.add(name + " 使用了 JdbcTemplate（绕过 Hibernate 租户过滤）");
            }
        }
        assertTrue(violations.isEmpty(), "存在绕过租户隔离的数据访问：" + violations);
    }

    @Test
    @DisplayName("禁止手拼 Redis key：必须经 TenantCacheKeys 生成（键内含租户维度）")
    void forbidManualCacheKeys() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String name = file.getFileName().toString();
            if (CACHE_KEY_WHITELIST.contains(name)) {
                continue;
            }
            String content = read(file);
            if (content.contains("\"albedo:")) {
                violations.add(name + " 出现 \"albedo:\" 字面量（疑似手拼缓存键）");
            }
            if (content.contains("@Cacheable")) {
                violations.add(name + " 使用 @Cacheable（默认键无租户维度）");
            }
        }
        assertTrue(violations.isEmpty(), "存在缺少租户维度的缓存键：" + violations);
    }

    @Test
    @DisplayName("租户业务实体必须继承 BaseTenantEntity（平台表显式登记）")
    void tenantEntitiesExtendBase() throws IOException {
        // 架构 §6.4 的 platform scope 清单：这些表的 tenant_id 是主数据、需跨租户查询，或本身无租户维度
        Set<String> platformEntities = Set.of(
                "SysConfig.java", "Tenant.java", "TenantDomain.java",
                // M2-min 新增平台表（§6.4 / §13.5.1 / §13.5.5 已逐表登记）
                "AuditLog.java",   // 平台事件 tenant_id 为 NULL，且需跨租户审计查询
                "LocalTool.java"   // 平台 Tool 注册表，租户只读（risk_level 不可被租户下调）
        );
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String content = read(file);
            String name = file.getFileName().toString();
            boolean isEntity = content.contains("@Entity");
            if (!isEntity || platformEntities.contains(name)) {
                continue;
            }
            if (!content.contains("extends BaseTenantEntity")) {
                violations.add(name + " 是业务实体但未继承 BaseTenantEntity");
            }
        }
        assertTrue(violations.isEmpty(), "存在缺少租户隔离维度的实体：" + violations);
    }

    /**
     * AR-013 的静态防线：{@code audit_logs} 是平台表，不受 discriminator 保护。
     *
     * <p>🔴 若审计写入不强制 {@code scope + tenantId}，漏填不会报错、只会静默产出一条
     * <b>租户维度查不到</b>的事件，直到 AC-AUD-001（租户维度审计完整率 100%）验收时才暴露，
     * 而那时数据已无法追补。因此把"强制校验存在"这件事本身也纳入扫描。
     */
    @Test
    @DisplayName("AR-013：审计写入必须强制 scope + tenantId，且租户维度查询显式带 tenant_id")
    void auditWritesCarryTenantDimension() throws IOException {
        Path writer = SOURCE_ROOT.resolve(
                Paths.get("com", "eyes", "albedo", "audit", "AuditWriter.java"));
        assertTrue(Files.exists(writer), "AuditWriter 必须存在（唯一审计写入通道）");
        String writerContent = read(writer);
        assertTrue(writerContent.contains("AuditScope.TENANT")
                        && writerContent.contains("isBlank()"),
                "🔴 AuditWriter 必须校验 scope=tenant 时 tenantId 非空");
        assertTrue(writerContent.contains("AuditActions.isRegistered"),
                "🔴 AuditWriter 必须校验 action 已登记");

        Path repository = SOURCE_ROOT.resolve(
                Paths.get("com", "eyes", "albedo", "audit", "AuditLogRepository.java"));
        String repositoryContent = read(repository);
        assertTrue(repositoryContent.contains("a.tenantId = :tenantId"),
                "🔴 审计的租户维度查询必须手写 tenant_id 条件（平台表无 discriminator）");
        assertTrue(!repositoryContent.contains("JpaRepository"),
                "🔴 AuditLogRepository 不得继承 JpaRepository（会带来 delete/saveAll 入口）");
    }

    @Test
    @DisplayName("禁止自建认证：无 password 字段、无自签 JWT、无 login/logout 接口")
    void forbidSelfBuiltAuth() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : javaFiles()) {
            String content = read(file);
            String lower = content.toLowerCase(Locale.ROOT);
            String name = file.getFileName().toString();
            if (lower.contains("passwordencoder") || lower.contains("bcrypt")) {
                violations.add(name + " 出现密码哈希实现");
            }
            if (content.contains("io.jsonwebtoken") || content.contains("Jwts.builder")) {
                violations.add(name + " 出现自签 JWT");
            }
            if (content.contains("/api/v1/auth/login") || content.contains("\"/logout\"")
                    || content.contains("/api/v1/auth/callback")) {
                violations.add(name + " 出现自建认证接口");
            }
        }
        assertTrue(violations.isEmpty(), "存在自建认证残留：" + violations);
    }

    private List<Path> javaFiles() throws IOException {
        try (Stream<Path> stream = Files.walk(SOURCE_ROOT)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
    }

    /**
     * 读取源码并<b>剔除注释</b>。
     *
     * <p>必须剔除：本项目大量 Javadoc 会引用被禁止的写法作为"反面示例"
     * （例如说明"禁止 nativeQuery=true"），若连注释一起扫描会产生假阳性，
     * 而假阳性会促使后来者放宽规则——那才是真正的风险。
     */
    private String read(Path path) throws IOException {
        String raw = Files.readString(path, StandardCharsets.UTF_8);
        String withoutBlockComments = raw.replaceAll("(?s)/\\*.*?\\*/", " ");
        return withoutBlockComments.replaceAll("(?m)^\\s*//.*$", " ");
    }
}
