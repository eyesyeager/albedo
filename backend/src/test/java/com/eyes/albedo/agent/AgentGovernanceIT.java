package com.eyes.albedo.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.eyes.albedo.agent.dto.AgentDTO;
import com.eyes.albedo.agent.dto.AgentRuntime;
import com.eyes.albedo.agent.entity.Agent;
import com.eyes.albedo.agent.service.AgentService;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.support.TempTenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Agent 治理测试：创建 → 发布 → 启停 → 默认项 → 复制（AC-AGT-001 ~ AC-AGT-003 / EX-010 / EX-011）。
 *
 * <p>同样在<b>临时租户</b>上运行，避免污染 gift / redbook 的验收基线。
 */
@SpringBootTest
class AgentGovernanceIT {

    private static final long OPERATOR_UID = 900000041L;
    private static final String MODEL = "hunyuan-a13b";

    @Autowired
    private AgentService agentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TempTenant tenant;

    @BeforeEach
    void setUp() {
        tenant = TempTenant.create(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        tenant.close();
    }

    @Test
    @DisplayName("EX-010：无可用 Agent → 新建会话解析抛 30030")
    void noAgentAvailable() {
        assertTrue(agentService.listRunnable().isEmpty());
        assertEquals(ErrorCode.AGENT_UNAVAILABLE,
                assertThrows(BusinessException.class,
                        () -> agentService.resolveForNewConversation(null)).getCode());
    }

    @Test
    @DisplayName("AC-AGT-001：未发布 / 未启用的 Agent 不出现在可用列表，也不能新建会话")
    void onlyPublishedAndEnabledAgentsAreRunnable() {
        Agent agent = agentService.create("helper", "助手", "说明", "", 0, OPERATOR_UID);

        // 仅创建（未发布、未启用）→ 不可用
        assertTrue(agentService.listRunnable().isEmpty());
        assertEquals(ErrorCode.AGENT_DISABLED,
                assertThrows(BusinessException.class,
                        () -> agentService.resolveForNewConversation(String.valueOf(agent.getId()))).getCode());

        // 未发布不允许启用
        assertEquals(ErrorCode.PUBLISH_VALIDATE_FAILED,
                assertThrows(BusinessException.class,
                        () -> agentService.changeStatus(agent.getId(), true, version(agent.getId()),
                                OPERATOR_UID)).getCode());

        agentService.publish(agent.getId(),
                agentService.defaultDraft("你是助手", "hunyuan", MODEL), version(agent.getId()), OPERATOR_UID);
        agentService.changeStatus(agent.getId(), true, version(agent.getId()), OPERATOR_UID);

        List<AgentDTO> runnable = agentService.listRunnable();
        assertEquals(1, runnable.size());
        assertEquals("helper", runnable.get(0).agentKey());
        assertEquals(1L, runnable.get(0).agentVersion());
    }

    @Test
    @DisplayName("AC-AGT-003：发布形成不可变快照，版本号递增，历史版本内容不变")
    void publishedVersionsAreImmutable() {
        Agent agent = agentService.create("helper", "助手", "说明", "", 0, OPERATOR_UID);
        agentService.publish(agent.getId(), agentService.defaultDraft("第一版提示", "hunyuan", MODEL),
                version(agent.getId()), OPERATOR_UID);
        long second = agentService.publish(agent.getId(),
                agentService.defaultDraft("第二版提示", "hunyuan", MODEL), version(agent.getId()), OPERATOR_UID);

        assertEquals(2L, second);
        // 历史版本内容未被改写（会话绑定旧版本时行为可复现，RISK-005）
        AgentRuntime v1 = agentService.runtime(agent.getId(), 1L);
        AgentRuntime v2 = agentService.runtime(agent.getId(), 2L);
        assertEquals("第一版提示", v1.systemPrompt());
        assertEquals("第二版提示", v2.systemPrompt());
        assertTrue(agentService.hasNewerVersion(agent.getId(), 1L),
                "绑定旧版本的会话应能感知到有新版本（仅提示，不切换）");
        assertTrue(!agentService.hasNewerVersion(agent.getId(), 2L));
    }

    @Test
    @DisplayName("EX-012：并发发布（版本令牌过期）→ 30020")
    void concurrentPublishConflict() {
        Agent agent = agentService.create("helper", "助手", "说明", "", 0, OPERATOR_UID);
        int stale = version(agent.getId());
        agentService.publish(agent.getId(), agentService.defaultDraft("提示", "hunyuan", MODEL),
                stale, OPERATOR_UID);

        assertEquals(ErrorCode.VERSION_CONFLICT,
                assertThrows(BusinessException.class,
                        () -> agentService.publish(agent.getId(),
                                agentService.defaultDraft("提示2", "hunyuan", MODEL),
                                stale, OPERATOR_UID)).getCode());
    }

    @Test
    @DisplayName("AC-AGT-002：每租户最多一个默认 Agent，切换后旧默认自动取消")
    void onlyOneDefaultAgent() {
        Agent first = publishedAgent("first", "第一个");
        Agent second = publishedAgent("second", "第二个");

        agentService.setDefault(first.getId(), version(first.getId()), OPERATOR_UID);
        agentService.setDefault(second.getId(), version(second.getId()), OPERATOR_UID);

        Integer defaults = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agents WHERE tenant_id = ? AND is_default = 1",
                Integer.class, tenant.tenantId());
        assertEquals(1, defaults, "每租户最多一个默认 Agent");

        // 默认 Agent 排在列表首位，且缺省新建会话使用它
        assertEquals("second", agentService.listRunnable().get(0).agentKey());
        assertEquals(second.getId(), agentService.resolveForNewConversation(null).getId());
    }

    @Test
    @DisplayName("EX-011：停用后不可新建会话（30031），历史数据仍保留")
    void disabledAgentBlocksNewConversation() {
        Agent agent = publishedAgent("helper", "助手");
        agentService.changeStatus(agent.getId(), false, version(agent.getId()), OPERATOR_UID);

        assertEquals(ErrorCode.AGENT_DISABLED,
                assertThrows(BusinessException.class,
                        () -> agentService.resolveForNewConversation(String.valueOf(agent.getId()))).getCode());
        assertEquals(ErrorCode.AGENT_DISABLED,
                assertThrows(BusinessException.class,
                        () -> agentService.requireRunnable(agent.getId())).getCode());
        assertTrue(agentService.listRunnable().isEmpty());
        // 数据未删除
        assertEquals(1, agentService.listManaged().size());
    }

    @Test
    @DisplayName("AC-TEN-003：agentKey 租户内唯一（重复创建 → 30021）")
    void agentKeyUniqueWithinTenant() {
        agentService.create("helper", "助手", "说明", "", 0, OPERATOR_UID);
        assertEquals(ErrorCode.PUBLISH_VALIDATE_FAILED,
                assertThrows(BusinessException.class,
                        () -> agentService.create("helper", "重复", "说明", "", 0, OPERATOR_UID)).getCode());
    }

    @Test
    @DisplayName("复制：生成新 key 的未发布副本，不复制默认标记与发布版本")
    void copyCreatesUnpublishedDraft() {
        Agent source = publishedAgent("source", "源助手");
        agentService.setDefault(source.getId(), version(source.getId()), OPERATOR_UID);

        Agent copy = agentService.copy(source.getId(), "source-copy", "源助手副本", OPERATOR_UID);

        assertEquals(0L, copy.getCurrentVersion(), "副本必须是未发布状态");
        assertEquals(Agent.STATUS_DISABLED, copy.getStatus());
        assertTrue(!copy.defaultAgent(), "🔴 不得复制默认标记");
    }

    @Test
    @DisplayName("跨租户 Agent ID → 10004（按不存在处理，不暴露存在性）")
    void crossTenantAgentNotFound() {
        // gift 的默认 Agent（种子数据）在临时租户上下文中必须不可见
        Long giftAgentId = jdbcTemplate.queryForObject(
                "SELECT id FROM agents WHERE tenant_id = 'gift' LIMIT 1", Long.class);
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND,
                assertThrows(BusinessException.class,
                        () -> agentService.resolveForNewConversation(String.valueOf(giftAgentId))).getCode());
    }

    private Agent publishedAgent(String key, String name) {
        Agent agent = agentService.create(key, name, "说明", "", 0, OPERATOR_UID);
        agentService.publish(agent.getId(), agentService.defaultDraft("提示", "hunyuan", MODEL),
                version(agent.getId()), OPERATOR_UID);
        agentService.changeStatus(agent.getId(), true, version(agent.getId()), OPERATOR_UID);
        return agent;
    }

    private int version(long agentId) {
        Integer value = jdbcTemplate.queryForObject("SELECT version FROM agents WHERE id = ?",
                Integer.class, agentId);
        return value == null ? 0 : value;
    }
}
