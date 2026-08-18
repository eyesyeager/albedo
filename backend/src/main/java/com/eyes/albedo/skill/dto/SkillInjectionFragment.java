package com.eyes.albedo.skill.dto;

import java.util.List;

/**
 * 单个已绑定 Skill 版本的注入片段（变量已替换完成）。
 *
 * <p>🔴 {@code instruction} / {@code outputConstraint} 属<b>内部资产</b>（api-spec §7.5.2 第 4 条）：
 * 禁止出现在任何对外响应、SSE 事件、埋点、审计与日志中；
 * 审计 / 埋点只允许记 {@code skillVersionId} 与 {@link #instructionDigest}。
 *
 * @param skillId           Skill 主体 ID
 * @param skillKey          Skill 键（仅用于日志排障，不含正文）
 * @param skillVersionId    Skill 版本行 ID
 * @param version           精确版本号（= 绑定的 {@code ref_version}）
 * @param sortOrder         绑定的注入顺序
 * @param instruction       已替换变量的指令正文（🔴 内部资产）
 * @param outputConstraint  已替换变量的输出约束（🔴 内部资产；无则空串）
 * @param instructionDigest {@code sha256(instruction)} 前 16 hex（可入审计 / 埋点）
 */
public record SkillInjectionFragment(Long skillId,
                                     String skillKey,
                                     Long skillVersionId,
                                     Integer version,
                                     Integer sortOrder,
                                     String instruction,
                                     String outputConstraint,
                                     String instructionDigest) {

    public boolean hasOutputConstraint() {
        return outputConstraint != null && !outputConstraint.isBlank();
    }

    /**
     * 供 {@code chat/ContextAssembler}（M3 第三阶段）消费的注入结果。
     *
     * <p>顺序契约（api-spec §7.5.2，🔴 不可调整）：
     * <pre>
     *   ① agent_versions.system_prompt        —— 由 ContextAssembler 提供
     *   ② 全部 Skill 版本的 instruction        —— {@link #instructions()}
     *   ③ 全部 Skill 版本的 outputConstraint   —— {@link #outputConstraints()}（追加于末尾）
     * </pre>
     * 🔴 ②③ 是<b>两段独立追加</b>，不是逐 Skill 交错拼接：
     * 输出约束必须整体落在系统提示末尾，否则后续 Skill 的指令会覆盖前一个 Skill 的输出要求。
     *
     * @param fragments 按 {@code sort_order ASC, ref_id ASC} 排好序的片段
     */
    public record Injection(List<SkillInjectionFragment> fragments) {

        public Injection {
            fragments = fragments == null ? List.of() : List.copyOf(fragments);
        }

        public static Injection empty() {
            return new Injection(List.of());
        }

        public boolean isEmpty() {
            return fragments.isEmpty();
        }

        /** ② 指令正文，按注入顺序。 */
        public List<String> instructions() {
            return fragments.stream().map(SkillInjectionFragment::instruction).toList();
        }

        /** ③ 输出约束，按注入顺序（跳过空值）。 */
        public List<String> outputConstraints() {
            return fragments.stream()
                    .filter(SkillInjectionFragment::hasOutputConstraint)
                    .map(SkillInjectionFragment::outputConstraint)
                    .toList();
        }

        /** 审计 / 埋点可用的最小标识集（🔴 不含正文）。 */
        public List<String> auditRefs() {
            return fragments.stream()
                    .map(f -> f.skillVersionId() + "@" + f.instructionDigest())
                    .toList();
        }
    }
}
