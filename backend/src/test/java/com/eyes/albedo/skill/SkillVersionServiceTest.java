package com.eyes.albedo.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.eyes.albedo.agent.entity.AgentCapabilityBinding;
import com.eyes.albedo.agent.entity.AgentVersion;
import com.eyes.albedo.agent.repository.AgentCapabilityBindingRepository;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.skill.dto.SkillViolation;
import com.eyes.albedo.skill.entity.Skill;
import com.eyes.albedo.skill.entity.SkillVersion;
import com.eyes.albedo.skill.repository.SkillRepository;
import com.eyes.albedo.skill.repository.SkillVersionRepository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Skill 版本解析单测（api-spec §7.5.1 / AC-SKL-002）。
 *
 * <p>核心断言：🔴 <b>只按 {@code ref_version} 精确解析</b>，绝不出现"取最新版本"的调用
 * —— 后者会让新增 Skill 版本静默改变旧会话行为（AC-SKL-002 / RISK-005）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkillVersionServiceTest {

    @Mock
    private AgentCapabilityBindingRepository bindingRepository;
    @Mock
    private SkillRepository skillRepository;
    @Mock
    private SkillVersionRepository skillVersionRepository;
    @Mock
    private SkillValidator skillValidator;

    @InjectMocks
    private SkillVersionService service;

    @Test
    @DisplayName("🔴 AC-SKL-002：按 ref_version 精确解析，且绝不调用\"取最新版本\"的查询")
    void resolvesExactVersionOnly() {
        AgentVersion agentVersion = agentVersion(100L);
        AgentCapabilityBinding binding = binding(11L, 2, 0);
        when(bindingRepository.findByAgentVersionIdAndCapabilityTypeOrderBySortOrderAscRefIdAsc(
                eq(100L), eq(AgentCapabilityBinding.TYPE_SKILL))).thenReturn(List.of(binding));
        when(skillRepository.findOneById(11L)).thenReturn(Optional.of(skill(11L, "greeter", 7)));
        when(skillVersionRepository.findBySkillIdAndVersion(11L, 2))
                .thenReturn(Optional.of(skillVersion(501L, 11L, 2)));

        List<SkillVersionService.ResolvedSkillBinding> resolved =
                service.resolveBindings(agentVersion);

        assertEquals(1, resolved.size());
        assertEquals(2, resolved.get(0).version().getVersion(),
                "必须解析到绑定的精确版本 2，而不是主体 currentVersion=7");
        // 🔴 "取最新" 的两条通道都必须没有被调用
        verify(skillVersionRepository, never()).findBySkillIdOrderByVersionDesc(any());
        verify(skillVersionRepository, never()).findBySkillIdAndStatus(any(), any());
    }

    @Test
    @DisplayName("🔴 跨租户 skill_id 引用 → 30060 rule=crossTenantReference")
    void crossTenantReferenceRejected() {
        AgentVersion agentVersion = agentVersion(100L);
        when(bindingRepository.findByAgentVersionIdAndCapabilityTypeOrderBySortOrderAscRefIdAsc(
                eq(100L), eq(AgentCapabilityBinding.TYPE_SKILL)))
                .thenReturn(List.of(binding(999L, 1, 0)));
        // discriminator 会追加 tenant_id → 别租户的 skillId 表现为查不到
        when(skillRepository.findOneById(999L)).thenReturn(Optional.empty());
        when(skillValidator.reject(anyList())).thenAnswer(invocation -> {
            List<SkillViolation> violations = invocation.getArgument(0);
            return new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID, "配置校验失败",
                    java.util.Map.of("violations", violations));
        });

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.resolveBindings(agentVersion));

        assertEquals(ErrorCode.RUNTIME_CONFIG_INVALID, ex.getCode());
        assertTrue(String.valueOf(ex.getPayload()).contains(SkillViolation.RULE_CROSS_TENANT),
                String.valueOf(ex.getPayload()));
    }

    @Test
    @DisplayName("🔴 ref_version ≤ 0（\"最新\"语义）→ 30060 rule=outOfRange")
    void latestSemanticsRejected() {
        AgentVersion agentVersion = agentVersion(100L);
        when(bindingRepository.findByAgentVersionIdAndCapabilityTypeOrderBySortOrderAscRefIdAsc(
                eq(100L), eq(AgentCapabilityBinding.TYPE_SKILL)))
                .thenReturn(List.of(binding(11L, 0, 0)));
        when(skillRepository.findOneById(11L)).thenReturn(Optional.of(skill(11L, "greeter", 3)));
        when(skillValidator.reject(anyList())).thenAnswer(invocation ->
                new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID, "配置校验失败",
                        java.util.Map.of("violations", invocation.getArgument(0))));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.resolveBindings(agentVersion));
        assertTrue(String.valueOf(ex.getPayload()).contains(SkillViolation.RULE_RANGE));
    }

    @Test
    @DisplayName("绑定引用的版本行不存在 → 30060 rule=refNotFound")
    void missingVersionRowRejected() {
        AgentVersion agentVersion = agentVersion(100L);
        when(bindingRepository.findByAgentVersionIdAndCapabilityTypeOrderBySortOrderAscRefIdAsc(
                eq(100L), eq(AgentCapabilityBinding.TYPE_SKILL)))
                .thenReturn(List.of(binding(11L, 9, 0)));
        when(skillRepository.findOneById(11L)).thenReturn(Optional.of(skill(11L, "greeter", 3)));
        when(skillVersionRepository.findBySkillIdAndVersion(11L, 9)).thenReturn(Optional.empty());
        when(skillValidator.reject(anyList())).thenAnswer(invocation ->
                new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID, "配置校验失败",
                        java.util.Map.of("violations", invocation.getArgument(0))));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.resolveBindings(agentVersion));
        assertTrue(String.valueOf(ex.getPayload()).contains(SkillViolation.RULE_REF_NOT_FOUND));
    }

    @Test
    @DisplayName("🔴 §7.5.2-2：Skill 主体被停用 / 版本已归档，仍须可注入（快照不可变）")
    void disabledSkillStillInjectable() {
        AgentVersion agentVersion = agentVersion(100L);
        when(bindingRepository.findByAgentVersionIdAndCapabilityTypeOrderBySortOrderAscRefIdAsc(
                eq(100L), eq(AgentCapabilityBinding.TYPE_SKILL)))
                .thenReturn(List.of(binding(11L, 1, 0)));
        Skill disabled = skill(11L, "greeter", 1);
        disabled.setStatus(Skill.STATUS_DISABLED);
        SkillVersion archived = skillVersion(501L, 11L, 1);
        archived.setStatus(SkillVersion.STATUS_ARCHIVED);
        when(skillRepository.findOneById(11L)).thenReturn(Optional.of(disabled));
        when(skillVersionRepository.findBySkillIdAndVersion(11L, 1))
                .thenReturn(Optional.of(archived));

        assertEquals(1, service.resolveBindings(agentVersion).size(),
                "停用/归档不得改写历史会话行为（AC-SKL-002）");
    }

    @Test
    @DisplayName("无 Skill 绑定 → 空结果（不查任何 Skill 表）")
    void noBindingsShortCircuits() {
        AgentVersion agentVersion = agentVersion(100L);
        when(bindingRepository.findByAgentVersionIdAndCapabilityTypeOrderBySortOrderAscRefIdAsc(
                eq(100L), eq(AgentCapabilityBinding.TYPE_SKILL))).thenReturn(List.of());

        assertTrue(service.resolveBindings(agentVersion).isEmpty());
        verify(skillRepository, never()).findOneById(any());
    }

    // ===================== 辅助 =====================

    private AgentVersion agentVersion(long id) {
        AgentVersion version = new AgentVersion();
        version.setId(id);
        version.setAgentId(1L);
        version.setVersion(1L);
        return version;
    }

    private AgentCapabilityBinding binding(long refId, int refVersion, int sortOrder) {
        AgentCapabilityBinding binding = new AgentCapabilityBinding();
        binding.setAgentVersionId(100L);
        binding.setCapabilityType(AgentCapabilityBinding.TYPE_SKILL);
        binding.setRefId(refId);
        binding.setRefVersion(refVersion);
        binding.setSortOrder(sortOrder);
        return binding;
    }

    private Skill skill(long id, String key, int currentVersion) {
        Skill skill = new Skill();
        skill.setId(id);
        skill.setSkillKey(key);
        skill.setName(key);
        skill.setStatus(Skill.STATUS_ENABLED);
        skill.setCurrentVersion(currentVersion);
        return skill;
    }

    private SkillVersion skillVersion(long id, long skillId, int version) {
        SkillVersion skillVersion = new SkillVersion();
        skillVersion.setId(id);
        skillVersion.setSkillId(skillId);
        skillVersion.setVersion(version);
        skillVersion.setInstruction("指令 v" + version);
        skillVersion.setVariablesSchema("[]");
        skillVersion.setOutputConstraint("");
        skillVersion.setStatus(SkillVersion.STATUS_PUBLISHED);
        return skillVersion;
    }
}
