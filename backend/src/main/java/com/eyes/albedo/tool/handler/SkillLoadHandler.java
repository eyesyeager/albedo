package com.eyes.albedo.tool.handler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.eyes.albedo.agent.entity.AgentVersion;
import com.eyes.albedo.agent.repository.AgentVersionRepository;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.skill.SkillInjectionService;
import com.eyes.albedo.skill.dto.SkillLoadResult;
import com.eyes.albedo.skill.dto.SkillRuntimeContext;
import com.eyes.albedo.tool.LocalToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 内置本地 Tool：Skill 按需加载（🔴 本次「模型按需加载 Skill」改造新增，
 * ADR-015 由「仅内置两个」修订为「仅内置三个」，见 architecture.md ADR-015 补注）。
 *
 * <p><b>背景</b>：此前 {@code SkillInjectionService} 把已绑定 Skill 的全文
 * instruction/outputConstraint/资源文件<b>无条件</b>拼进每一轮 system prompt，模型没有
 * 选择权。本工具把"是否展开某个 Skill"的决定权交还给模型：system prompt 只保留一份
 * 轻量清单（{@code SkillInjectionService.resolveInstructions}），模型判断任务命中某条
 * 清单项时才主动调用本工具按 {@code skillKey} 拉取完整正文。
 *
 * <p>🔴 <b>与 ADR-015 现有两个内置工具的关键差异（已评审并接受）</b>：
 * <ul>
 *   <li>{@code datetime_now} / {@code calculator} <b>不读数据库</b>；本工具<b>必须</b>读
 *       {@code skill_versions} / {@code skill_resources}（否则无法"展开"）。
 *       这不是对 ADR-008 第 8 条"不新增线程池"的违反 —— 仍在当前线程内跑完
 *       {@link com.eyes.albedo.tool.LocalToolExecutor} 的 {@code FutureTask.run()}；
 *       只是把"纯函数、绝对不阻塞"的残余风险从 0 放宽到"一次本地 MySQL 只读查询的
 *       正常耗时"，与本平台其余所有走 {@code @Transactional(readOnly=true)} 的读路径
 *       同一量级，非新增风险类别</li>
 *   <li>{@code tool → skill} 依赖方向<b>被允许</b>（architecture.md §5.1.2 箭头只禁止
 *       {@code skill → tool}，反向未禁止），因此本类直接注入
 *       {@link SkillInjectionService}，把变量替换 / 资源渲染逻辑留在 skill 包内单一实现，
 *       本类只做"取 AgentVersion → 调用 → 序列化"</li>
 *   <li>🔴 <b>正文内部资产纪律的调整</b>：本工具的返回值就是 Skill 正文，会随通用
 *       {@code tool} 摘要链路产生脱敏后的短预览（SSE {@code tool} 帧 / {@code tool_calls}），
 *       这是"按需加载"设计目标的必然代价，已在 {@code SkillInjectionService} 类注释详细权衡</li>
 * </ul>
 *
 * <p>🔴 <b>纯只读、无副作用、幂等</b>：多次以同一 {@code skillKey} 调用结果一致
 * （不含变量替换所依赖的 locale/timezone 之外的任何时变量），riskLevel=low、
 * idempotent=1，不需要用户确认。
 */
@Slf4j
@Component
public class SkillLoadHandler implements LocalToolHandler {

    /** 🔴 必须与 {@code local_tools.tool_key} 完全一致。 */
    public static final String TOOL_KEY = "skill_load";

    private static final String ARG_SKILL_KEY = "skillKey";

    private static final String FIELD_SKILL_KEY = "skillKey";
    private static final String FIELD_NAME = "name";
    private static final String FIELD_INSTRUCTION = "instruction";
    private static final String FIELD_OUTPUT_CONSTRAINT = "outputConstraint";
    private static final String FIELD_RESOURCES = "resources";
    private static final String FIELD_ERROR = "error";
    private static final String FIELD_MESSAGE = "message";

    private final ObjectMapper objectMapper;
    private final AgentVersionRepository agentVersionRepository;
    private final SkillInjectionService skillInjectionService;

    public SkillLoadHandler(ObjectMapper objectMapper,
                           AgentVersionRepository agentVersionRepository,
                           SkillInjectionService skillInjectionService) {
        this.objectMapper = objectMapper;
        this.agentVersionRepository = agentVersionRepository;
        this.skillInjectionService = skillInjectionService;
    }

    @Override
    public String toolKey() {
        return TOOL_KEY;
    }

    @Override
    public String execute(LocalToolInvocation invocation) {
        String skillKey = readSkillKey(invocation.argumentsJson());
        AgentVersion agentVersion = agentVersionRepository.findOneById(invocation.agentVersionId())
                .orElseThrow(() -> {
                    // 🔴 不应该发生（本次生成本身就是靠这个 agentVersion 构造出清单/工具的），
                    //    出现即视为平台内部状态不一致，按 30057 上报而非静默返回"未绑定"
                    log.error("skill_load 找不到本次生成绑定的 Agent 版本：agentVersionId={}",
                            invocation.agentVersionId());
                    return new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED, "工具执行失败");
                });

        SkillRuntimeContext context = SkillRuntimeContext.ofTenant(invocation.tenantId());
        Optional<SkillLoadResult> loaded =
                skillInjectionService.loadOnDemand(agentVersion, skillKey, context);

        Map<String, Object> body = new LinkedHashMap<>();
        if (loaded.isEmpty()) {
            // 🔴 不是异常：模型传入了清单外/不存在的 skillKey，属正常业务分支，
            //    让模型据此在无该技能的前提下继续作答（与「非法配置」的 30060 不同层）
            log.warn("skill_load 请求了未绑定/不存在的技能");
            body.put(FIELD_ERROR, "skill_not_bound");
            body.put(FIELD_MESSAGE, "该 skillKey 未绑定到当前 Agent 或不存在，"
                    + "请勿凭空猜测该技能的细节，可基于清单中其他技能或直接作答");
        } else {
            SkillLoadResult result = loaded.get();
            body.put(FIELD_SKILL_KEY, result.skillKey());
            body.put(FIELD_NAME, result.name());
            body.put(FIELD_INSTRUCTION, result.instruction());
            if (result.hasOutputConstraint()) {
                body.put(FIELD_OUTPUT_CONSTRAINT, result.outputConstraint());
            }
            if (result.hasResources()) {
                body.put(FIELD_RESOURCES, result.resourcesText());
            }
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.error("skill_load 结果序列化失败", e);
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED, "工具执行失败");
        }
    }

    private String readSkillKey(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            throw new BusinessException(ErrorCode.TOOL_ARGS_INVALID, "缺少 skillKey 参数");
        }
        try {
            JsonNode root = objectMapper.readTree(argumentsJson);
            JsonNode node = root.isObject() ? root.get(ARG_SKILL_KEY) : null;
            if (node == null || node.isNull() || node.asText().isBlank()) {
                throw new BusinessException(ErrorCode.TOOL_ARGS_INVALID, "缺少 skillKey 参数");
            }
            return node.asText().trim();
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.TOOL_ARGS_INVALID, "入参不是合法 JSON 对象");
        }
    }
}
