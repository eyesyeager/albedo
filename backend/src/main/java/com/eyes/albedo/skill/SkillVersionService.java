package com.eyes.albedo.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.eyes.albedo.agent.entity.AgentCapabilityBinding;
import com.eyes.albedo.agent.entity.AgentVersion;
import com.eyes.albedo.agent.repository.AgentCapabilityBindingRepository;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.skill.dto.SkillViolation;
import com.eyes.albedo.skill.entity.Skill;
import com.eyes.albedo.skill.entity.SkillVersion;
import com.eyes.albedo.skill.repository.SkillRepository;
import com.eyes.albedo.skill.repository.SkillVersionRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 按 Agent 版本绑定解析 Skill 版本（api-spec §7.5.1 / §7.5.2，AC-SKL-002）。
 *
 * <p><b>REQ-SKL-002</b>
 *
 * <p>🔴 <b>精确版本引用（本类存在的唯一理由）</b>：
 * {@code agent_capability_bindings.ref_version} 是<b>精确版本号</b>，
 * 解析必须走 {@code uk_tenant_skill_version(tenant_id, skill_id, version)}。
 * <pre>
 * ❌ 禁止：skills.current_version / findBySkillIdOrderByVersionDesc().get(0) —— "取最新"语义
 *    危害：DBA 为某 Skill 发布新版本后，<b>所有历史会话的行为会静默漂移</b>，
 *          "版本快照"名存实亡，直接违反 AC-SKL-002 与 RISK-005。
 * ✅ 正确：findBySkillIdAndVersion(refId, refVersion)
 * </pre>
 *
 * <p>🔴 <b>快照 vs 停用（api-spec §7.5.2 第 2 条）</b>：
 * Skill 主体 {@code status} 变为 {@code disabled} <b>不影响</b>已绑定历史版本的注入 —— 快照不可变。
 * 但 Skill 正文里提及的<b>工具</b>是否可用，一律由 §7.4.4 / §7.7.2 的授权矩阵决定，
 * 🔴 指令文本永远不能提权（"历史静态指令不恢复外部工具权限"）。
 * 因此本类<b>不</b>按 {@code Skill.status} 过滤，也<b>不</b>按 {@code SkillVersion.status} 过滤
 * （{@code archived} 的历史版本仍须可注入，否则归档动作会改写旧会话行为）。
 *
 * <p>🔴 一期<b>不缓存</b>（api-spec §7.1.2 末尾 / architecture.md §12.1.1）：
 * 直读 MySQL 保证 DBA 改库即生效（AC-CFG-004）；且 Skill 正文是内部资产，禁入进程内共享缓存。
 */
@Slf4j
@Service
public class SkillVersionService {

    private final AgentCapabilityBindingRepository bindingRepository;
    private final SkillRepository skillRepository;
    private final SkillVersionRepository skillVersionRepository;
    private final SkillValidator skillValidator;

    public SkillVersionService(AgentCapabilityBindingRepository bindingRepository,
                              SkillRepository skillRepository,
                              SkillVersionRepository skillVersionRepository,
                              SkillValidator skillValidator) {
        this.bindingRepository = bindingRepository;
        this.skillRepository = skillRepository;
        this.skillVersionRepository = skillVersionRepository;
        this.skillValidator = skillValidator;
    }

    /**
     * 一条已解析的 Skill 绑定（绑定行 + 主体 + 精确版本）。
     *
     * @param binding 绑定行（含 {@code variableValues} 与 {@code sortOrder}）
     * @param skill   Skill 主体（同租户）
     * @param version 精确版本行（不可变快照）
     */
    public record ResolvedSkillBinding(AgentCapabilityBinding binding, Skill skill,
                                       SkillVersion version) {
    }

    /**
     * 取某 Agent 版本绑定的全部 Skill 绑定行（按注入顺序）。
     *
     * <p>🔴 一次查询取全（走 {@code idx_tenant_version_sort}），禁止 N+1（architecture.md §9.5.3）。
     */
    @Transactional(readOnly = true)
    public List<AgentCapabilityBinding> skillBindings(AgentVersion agentVersion) {
        if (agentVersion == null || agentVersion.getId() == null) {
            return List.of();
        }
        return bindingRepository
                .findByAgentVersionIdAndCapabilityTypeOrderBySortOrderAscRefIdAsc(
                        agentVersion.getId(), AgentCapabilityBinding.TYPE_SKILL);
    }

    /**
     * 解析某 Agent 版本绑定的全部 Skill 版本（按注入顺序）。
     *
     * <p>失败语义（🔴 全部 {@code 30060}，在进入模型之前失败，AC-CFG-004）：
     * <ul>
     *   <li>绑定的 {@code skill_id} 不在当前租户 → {@code crossTenantReference}
     *       （🔴 跨租户引用；因 discriminator 会追加 {@code tenant_id}，表现为"查不到"，
     *       与"不存在"对外<b>同样不泄露存在性</b>）</li>
     *   <li>{@code ref_version} ≤ 0 → {@code outOfRange}（"最新"语义被明令禁止，必须是精确版本）</li>
     *   <li>精确版本行不存在 → {@code refNotFound}</li>
     * </ul>
     *
     * @throws BusinessException 30060 引用链非法（带全部 {@code violations}）
     */
    @Transactional(readOnly = true)
    public List<ResolvedSkillBinding> resolveBindings(AgentVersion agentVersion) {
        List<AgentCapabilityBinding> bindings = skillBindings(agentVersion);
        if (bindings.isEmpty()) {
            return List.of();
        }
        List<ResolvedSkillBinding> resolved = new ArrayList<>(bindings.size());
        List<SkillViolation> violations = new ArrayList<>();

        for (AgentCapabilityBinding binding : bindings) {
            Long skillId = binding.getRefId();
            // 🔴 派生查询（会被追加 tenant_id）；禁止 findById（主键直载不追加租户条件）
            Optional<Skill> skill = skillId == null
                    ? Optional.empty() : skillRepository.findOneById(skillId);
            if (skill.isEmpty()) {
                violations.add(SkillViolation.of(SkillViolation.OBJECT_AGENT_VERSION,
                        agentVersion.getId(), "refId", SkillViolation.RULE_CROSS_TENANT,
                        "绑定的 Skill 不在当前租户内"));
                continue;
            }
            Integer refVersion = binding.getRefVersion();
            if (refVersion == null || refVersion <= 0) {
                violations.add(SkillViolation.of(SkillViolation.OBJECT_AGENT_VERSION,
                        agentVersion.getId(), "refVersion", SkillViolation.RULE_RANGE,
                        "Skill 绑定必须是精确版本引用（refVersion ≥ 1）"));
                continue;
            }
            Optional<SkillVersion> version =
                    skillVersionRepository.findBySkillIdAndVersion(skillId, refVersion);
            if (version.isEmpty()) {
                violations.add(SkillViolation.of(SkillViolation.OBJECT_AGENT_VERSION,
                        agentVersion.getId(), "refVersion", SkillViolation.RULE_REF_NOT_FOUND,
                        "绑定引用的 Skill 版本不存在"));
                continue;
            }
            resolved.add(new ResolvedSkillBinding(binding, skill.get(), version.get()));
        }

        if (!violations.isEmpty()) {
            throw skillValidator.reject(violations);
        }
        return resolved;
    }
}
