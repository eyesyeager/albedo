package com.eyes.albedo.audit;

import java.time.Instant;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计写入器（🔴 唯一写入通道，architecture.md §11.1 / ADR-010）。
 *
 * <p><b>两个入口的语义差异（🔴 不可混用，选错会造成死锁或漏审计）</b>：
 * <table border="1">
 *   <caption>事务边界</caption>
 *   <tr><th>入口</th><th>传播行为</th><th>适用场景</th><th>失败后果</th></tr>
 *   <tr>
 *     <td>{@link #write(AuditEvent)}</td>
 *     <td>{@code REQUIRED}（<b>加入调用方事务</b>）</td>
 *     <td><b>非流式</b>安全/管理操作：缓存失效、MCP 连接测试、工具发现、凭据变更生效、
 *         排障票据签发、跨租户探测</td>
 *     <td>🔴 <b>整体失败</b>：异常上抛 → 业务动作随同一事务回滚 → 对外 {@code 50003}（EX-024）。
 *         即"不得执行后伪报未审计"</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #writeInNewTransaction(AuditEvent)}</td>
 *     <td>{@code REQUIRES_NEW}（<b>独立短事务，立即提交</b>）</td>
 *     <td><b>流式过程中</b>的安全事件：工具授权被拒、运行时 SSRF 拒绝、
 *         高风险确认/拒绝/确认超时</td>
 *     <td>🔴 该次<b>工具调用</b>失败（{@code denied}/{@code failed}，{@code errorCode=50003}），
 *         <b>绝不中断整条 SSE 流</b> —— 流内以 {@code tool}(终态) → 必要时 {@code error} →
 *         {@code done} 收敛</td>
 *   </tr>
 * </table>
 *
 * <p>🔴 为什么流式内必须独立短事务（ADR-010，反面代价已在文档中登记）：
 * <ol>
 *   <li>一次生成可达 300s 并跨多轮工具调用，与生成共享长事务会耗尽连接池；</li>
 *   <li><b>致命自锁</b>：confirm 接口要 {@code SELECT … FOR UPDATE} 同一 {@code tool_calls} 行，
 *       若生成线程在等待确认时仍持有该行锁，双方互等 → 高风险确认功能直接死锁；</li>
 *   <li>SSE 分片已发出，回滚无法"撤回"用户已看到的内容（EX-015 的反向教训）；</li>
 *   <li>审计必须不可篡改，放进可能回滚的长事务与"只写不改"冲突。</li>
 * </ol>
 *
 * <p>🔴 短事务边界纪律：<b>禁止</b>在事务内做任何网络调用或 SSE 写出。
 * 工具执行必须切成三段：① 短事务提交 {@code running} ② <b>事务外</b>执行 ③ 短事务写终态 + 审计。
 *
 * <p>本类<b>不依赖任何业务包</b>（§5.1.2 的 {@code audit ✗→ 任何业务包}）：只接收结构化入参。
 * 请求上下文（requestId / actor / ip / userAgent）由 {@link AuditService} 负责补齐。
 */
@Slf4j
@Component
public class AuditWriter {

    private final AuditLogRepository repository;

    public AuditWriter(AuditLogRepository repository) {
        this.repository = repository;
    }

    /**
     * 非流式路径：<b>加入调用方事务</b>，审计失败 → 业务一并回滚（EX-024）。
     *
     * <p>🔴 调用方必须处在事务中（{@code @Transactional}），否则本方法会各自独立提交，
     * 失去"审计与业务原子"的保证。
     *
     * @param context 请求上下文（requestId / actor / ip / userAgent）
     * @return 已落库事件的 {@code eventId}（32 位小写 hex，对外原样返回，🔴 禁止截断）
     * @throws AuditWriteException 入参非法（含 scope=tenant 漏填 tenantId）
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public String write(AuditEvent event, AuditContext context) {
        return doWrite(event, context);
    }

    /**
     * 流式路径：<b>独立短事务</b>（{@code REQUIRES_NEW}），提交后立即释放连接与行锁。
     *
     * <p>🔴 语义差异见类注释：本方法失败只允许使"该次工具调用"失败，
     * <b>调用方必须捕获 {@link AuditWriteException} 并继续把 SSE 流收敛完</b>，绝不向上冒泡中断流。
     *
     * @return 已落库事件的 {@code eventId}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String writeInNewTransaction(AuditEvent event, AuditContext context) {
        return doWrite(event, context);
    }

    private String doWrite(AuditEvent event, AuditContext context) {
        validate(event);
        AuditContext ctx = context == null ? AuditContext.system() : context;

        AuditLog log = new AuditLog();
        log.setEventId(newEventId());
        log.setScope(event.scope().value());
        // 🔴 平台事件强制 NULL，租户事件强制非空（上面 validate 已保证）
        log.setTenantId(event.scope() == AuditScope.TENANT ? event.tenantId().trim() : null);
        log.setRequestId(ctx.requestId());
        log.setActorType(ctx.actorType());
        log.setActorId(ctx.actorId());
        log.setAction(event.action());
        log.setObjectType(nullToEmpty(event.objectType()));
        log.setObjectId(AuditSanitizer.objectId(event.objectId()));
        // 🔴 防御性：调用方即使传了明文，也只会以摘要形态落库
        log.setBeforeDigest(AuditSanitizer.digestColumn(event.beforeDigest()));
        log.setAfterDigest(AuditSanitizer.digestColumn(event.afterDigest()));
        log.setResult(event.result());
        log.setReason(AuditSanitizer.reason(event.reason()));
        log.setErrorCode(event.errorCode());
        log.setIp(AuditSanitizer.ip(ctx.ip()));
        log.setUserAgent(AuditSanitizer.userAgent(ctx.userAgent()));
        log.setOccurredAt(Instant.now());

        try {
            AuditLog saved = repository.save(log);
            return saved.getEventId();
        } catch (RuntimeException e) {
            // 🔴 不吞：由两个入口各自的语义决定后果（整体失败 / 该次工具调用失败）
            throw new AuditWriteException("审计写入失败：action=" + event.action(), e);
        }
    }

    /**
     * 入参强制校验（🔴 AR-013 的核心防线）。
     *
     * <p>为什么把"漏填 tenantId"当作异常而不是"补个默认值"：{@code audit_logs} 是平台表，
     * 漏填不会报错、只会静默产出一条<b>查不到的租户事件</b>，
     * 直到 AC-AUD-001（租户维度审计完整率 100%）验收时才暴露 —— 那时数据已无法追补。
     */
    private void validate(AuditEvent event) {
        if (event == null) {
            throw new AuditWriteException("审计事件不能为空");
        }
        if (event.scope() == null) {
            throw new AuditWriteException("审计事件必须显式指定 scope（platform / tenant）");
        }
        if (!AuditActions.isRegistered(event.action())) {
            throw new AuditWriteException(
                    "审计 action 未在 api-spec §7.14 登记：" + event.action());
        }
        if (!AuditResults.isRegistered(event.result())) {
            throw new AuditWriteException("审计 result 非法：" + event.result());
        }
        if (event.scope() == AuditScope.TENANT
                && (event.tenantId() == null || event.tenantId().isBlank())) {
            throw new AuditWriteException(
                    "scope=tenant 的审计事件必须显式携带 tenantId（audit_logs 是平台表，"
                            + "不受 discriminator 保护）：action=" + event.action());
        }
        if (event.scope() == AuditScope.PLATFORM
                && event.tenantId() != null && !event.tenantId().isBlank()) {
            // 平台事件混入 tenant_id 会污染租户维度统计（§13.5.1：平台级表不得混入非空 tenant_id）
            throw new AuditWriteException(
                    "scope=platform 的审计事件不得携带 tenantId：action=" + event.action());
        }
    }

    /**
     * 32 位小写 UUID hex（无连字符），正则 {@code ^[0-9a-f]{32}$}。
     *
     * <p>🔴 对外 {@code auditEventId} 原样返回、禁止截断（api-spec §7.14 不变量第 5 条）。
     */
    static String newEventId() {
        return UUID.randomUUID().toString().replace("-", "").toLowerCase(java.util.Locale.ROOT);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
