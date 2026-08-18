package com.eyes.albedo.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 本地 Tool 实现体静态注册表（api-spec §7.7.1 / architecture.md §13.5.5）。
 *
 * <p>🔴 <b>注册表有行但平台无实现 → {@code 30060}</b>，且必须在<b>构造工具清单阶段</b>失败
 * （不能等模型请求调用才暴露）。这条约束存在的原因：
 * {@code local_tools} 由平台管理员<b>直接写库</b>（DEC-010），
 * 写一行"平台并没有实现"的工具是完全可能的手误；
 * 若不提前失败，模型会拿到一个永远调不通的工具，表现为"AI 老是说要帮我退款但什么都没发生"。
 *
 * <p>🔴 <b>一期内置清单（已裁决：api-spec §7.7.1 + ADR-015 ①，随「按需加载 Skill」/「Skill 脚本
 * 执行」两次改造扩充）</b>：
 * <ul>
 *   <li>{@code datetime_now}（当前时间，可选 IANA 时区，缺省 UTC）与
 *       {@code calculator}（十进制四则运算，自写递归下降解析器）——
 *       两者均 {@code riskLevel=low}、{@code idempotent=1}、{@code timeout_seconds=5}、
 *       <b>纯函数无外部副作用</b></li>
 *   <li>{@code skill_load}（按需展开 Skill 全文，{@code riskLevel=low}、只读 DB，见
 *       {@code SkillLoadHandler}）</li>
 *   <li>{@code skill_exec}（🔴 执行 Skill 版本快照锁定的预置脚本，{@code riskLevel=high}、
 *       {@code idempotent=0}、逐次强制用户确认，见 {@code SkillExecHandler}）——
 *       一期<b>唯一</b>有真实副作用的内置工具，代码来源被限定为平台/租户预置数据
 *       而非模型/用户自由输入</li>
 * </ul>
 * 🔴 清单之外的任何 {@code local_tools} 行都会因"平台无实现体"被 {@code 30060} 拒绝（fail-closed），
 * 这是<b>正确行为</b>、不是缺陷。
 * 🔴 新增内置工具必须：先回写 api-spec §7.7.1 清单 → 由 @架构师 复核"是否有外部副作用 /
 * 是否可能长阻塞"（ADR-015 ③）→ 方可实现；后端<b>不得自行发明</b>有真实副作用的工具
 * （退款 / 导出 / 工单），{@code skill_exec} 是本纪律下<b>唯一</b>的显式例外（已评审）。
 *
 * <p>测试通过 {@code @TestConfiguration} 注入 {@link LocalToolHandler} Bean 来验证执行链路。
 */
@Slf4j
@Component
public class LocalToolRegistry {

    private final Map<String, LocalToolHandler> handlers = new LinkedHashMap<>();

    /**
     * 由 Spring 注入全部内置实现体（一期为 {@code datetime_now} + {@code calculator}）。
     *
     * @throws IllegalStateException 同一 {@code toolKey} 注册了多个实现（歧义必须启动即失败）
     */
    public LocalToolRegistry(List<LocalToolHandler> handlerBeans) {
        for (LocalToolHandler handler : handlerBeans) {
            String key = handler.toolKey();
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("本地 Tool 实现体缺少 toolKey："
                        + handler.getClass().getName());
            }
            LocalToolHandler existing = handlers.put(key, handler);
            if (existing != null) {
                // 🔴 歧义不可运行时裁决：两个实现谁生效将取决于 Bean 顺序，属不可预测行为
                throw new IllegalStateException("本地 Tool 实现体重复注册：toolKey=" + key);
            }
        }
        log.info("本地 Tool 实现体注册完成：count={} keys={}", handlers.size(), handlers.keySet());
    }

    /**
     * 查找实现体。
     */
    public Optional<LocalToolHandler> find(String toolKey) {
        return Optional.ofNullable(handlers.get(toolKey));
    }

    /**
     * 是否存在实现体。
     */
    public boolean contains(String toolKey) {
        return handlers.containsKey(toolKey);
    }

    /**
     * 已注册的 {@code toolKey}（只读）。
     */
    public Set<String> registeredKeys() {
        return Set.copyOf(handlers.keySet());
    }

    /**
     * 取实现体，缺失 → {@code 30060}。
     *
     * @throws BusinessException 30060 注册表有行但平台无对应实现
     */
    public LocalToolHandler require(String toolKey) {
        return find(toolKey).orElseThrow(() -> {
            log.warn("本地 Tool 无对应实现体，拒绝进入清单：toolKey={}", toolKey);
            // 🔴 消息只说"未实现"，不暴露已注册清单（避免泄露平台能力面）
            return new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID,
                    "该工具在平台侧没有可用实现");
        });
    }
}
