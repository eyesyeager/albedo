package com.eyes.albedo.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.eyes.albedo.agent.entity.AgentVersion;
import com.eyes.albedo.audit.AuditDigest;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.platform.dto.TenantProfile;
import com.eyes.albedo.platform.service.TenantService;
import com.eyes.albedo.skill.dto.SkillInjectionFragment;
import com.eyes.albedo.skill.dto.SkillLoadResult;
import com.eyes.albedo.skill.dto.SkillRuntimeContext;
import com.eyes.albedo.skill.dto.SkillScriptResult;
import com.eyes.albedo.skill.dto.SkillVariableDecl;
import com.eyes.albedo.skill.entity.Skill;
import com.eyes.albedo.skill.entity.SkillResource;
import com.eyes.albedo.skill.entity.SkillVersion;
import com.eyes.albedo.skill.repository.SkillResourceRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Skill 运行时注入片段装配（api-spec §7.5.2，AC-SKL-002 / AC-CHAT-007）。
 *
 * <p><b>REQ-SKL-002</b> —— 本类现有<b>三个</b>消费入口（🔴 「按需加载」改造新增第二个，
 * 「skill_exec 脚本执行」改造新增第三个）：
 * <ul>
 *   <li>{@code chat/ContextAssembler} 调用 {@link #resolveInstructions}：
 *       每轮<b>无条件</b>注入的是<b>技能清单</b>（skillKey/name/description 摘要），
 *       不再是全文 instruction —— 体量小、无需预算即可安全全量下发</li>
 *   <li>{@code tool/SkillLoadHandler} 调用 {@link #loadOnDemand}：
 *       仅当模型判断本轮任务命中清单中某条技能并<b>主动调用</b> {@code skill_load} 工具时，
 *       才按<b>精确 skillKey</b> 展开该技能的完整 instruction + outputConstraint + 资源正文</li>
 *   <li>{@code tool/SkillExecHandler} 调用 {@link #loadExecutableScript}：仅当模型主动调用
 *       {@code skill_exec} 工具、且按 {@code skillKey+resourcePath} 精确匹配到一条
 *       {@code executable=1} 的 {@code skill_resources} 行时，才把脚本正文（已变量替换）
 *       交回给 {@code SkillExecHandler} 在白名单解释器内执行 —— 本类<b>只解析数据，不执行</b>，
 *       与「skill ✗→ chat/tool」边界纪律一致</li>
 * </ul>
 *
 * <p><b>注入顺序契约（api-spec §7.5.2 基线，🔴 本次改造调整为）</b>：
 * <pre>
 *   ① agent_versions.system_prompt                       —— ContextAssembler 提供
 *   ② 已绑定 Skill 的<b>清单条目</b>（sort_order ASC, ref_id ASC，每条含"如何 skill_load"的引导）
 *   然后才是历史摘要与最近消息窗口（M1 的 summary_then_window 策略）
 *   ③ 原「outputConstraint 整体追加于末尾」段落<b>不再无条件注入</b>：
 *      改为随 {@link #loadOnDemand} 的结果一并回灌模型，仅在该技能真正被加载时才生效
 * </pre>
 *
 * <p>🔴 <b>边界纪律</b>：
 * <ul>
 *   <li>{@code skill ✗→ chat/tool}（architecture.md §5.1.2 基线不变）：本类只产出<b>文本片段</b>，
 *       不感知 SSE、不参与工具授权、不引用 {@code tool} 包任何类型（含 {@code skill_load} 的
 *       {@code toolKey} 字面量，本类内部以字符串硬编码并注释交叉引用，防止反向依赖）。
 *       🔴 Skill 正文提到的工具能否调用，完全由 {@code tool} 模块的授权矩阵决定 ——
 *       <b>指令文本永远不能提权</b>；反向地，{@code tool} 包依赖本类是<b>允许</b>的方向
 *       （§5.1.2 箭头未禁止 {@code tool → skill}）</li>
 *   <li>本类<b>不拼接</b>最终 system 消息、<b>不做</b>跨 Skill 的总量判定：归属
 *       {@code chat/ContextAssembler}（architecture.md §5.1.3）。清单条目仍受
 *       {@code chat.system_prompt_max_chars} 预算约束；{@link #loadOnDemand} 的产出走
 *       {@code tool.result_max_bytes} / {@code tool.result_summary_max_chars}
 *       （见 {@code SkillLoadHandler} 与 {@code ToolResultTruncator}），不占用 system 预算</li>
 *   <li>🔴 <b>正文内部资产纪律的调整（本次改造的核心权衡，务必读完）</b>：
 *       {@link #resolveInstructions} 的产出（清单）不含正文，日志仍只记 digest；
 *       但 {@link #loadOnDemand} 的产出<b>就是</b>正文，且<b>按设计</b>会随工具调用结果
 *       流经通用 {@code tool} 摘要链路（{@code ToolSummaryScrubber} 生成的头尾预览会出现在
 *       SSE {@code tool} 帧与 {@code tool_calls.result_summary}，与其余所有工具结果一致）——
 *       这是"模型按需加载"这一设计目标<b>必然</b>的代价，不同于此前"绝不出现在任何响应"的
 *       静态注入模型。已接受的残余风险：只是<b>脱敏截断后的短预览</b>，不是全文落库/落日志；
 *       完整正文仍只流向模型（{@code AiMessage.tool} 回灌），且不会被 {@code log.debug} 打印</li>
 * </ul>
 *
 * <p>🔴 <b>零缓存</b>：一期 Skill 版本快照直读 MySQL（api-spec §7.1.2 末尾）；两个入口皆然。
 */
@Slf4j
@Service
public class SkillInjectionService {

    private final SkillVersionService skillVersionService;
    private final SkillValidator skillValidator;
    private final SkillVariableResolver variableResolver;
    private final TenantService tenantService;
    private final SkillResourceRepository skillResourceRepository;

    public SkillInjectionService(SkillVersionService skillVersionService,
                                SkillValidator skillValidator,
                                SkillVariableResolver variableResolver,
                                TenantService tenantService,
                                SkillResourceRepository skillResourceRepository) {
        this.skillVersionService = skillVersionService;
        this.skillValidator = skillValidator;
        this.variableResolver = variableResolver;
        this.tenantService = tenantService;
        this.skillResourceRepository = skillResourceRepository;
    }

    /**
     * 解析某 Agent 版本的 Skill 注入片段（供 {@code ContextAssembler} 注入 system 消息）。
     *
     * <p>内置变量 {@code locale} / {@code timezone} 的取值来自租户主数据：
     * 🔴 <b>只在正文确实引用它们时</b>才查一次租户表（避免为极少用到的内置变量在
     * 每次生成上白付一次 DB 往返，性能红线 §14.2）。
     *
     * @param agentVersion 会话绑定的 Agent 版本（🔴 快照，不是"当前最新版本"）
     * @throws BusinessException 30060 引用链或变量声明非法（在进入模型之前失败）
     */
    @Transactional(readOnly = true)
    public SkillInjectionFragment.Injection resolveInstructions(AgentVersion agentVersion) {
        return resolveInstructions(agentVersion,
                SkillRuntimeContext.ofTenant(
                        agentVersion == null ? "" : agentVersion.getTenantId()));
    }

    /**
     * 解析注入片段（显式给出运行时上下文；🔴 异步生成线程<b>必须</b>用这个重载，
     * 上下文由 Servlet 线程抓取后显式传入，见 architecture.md §9.5.2 第 3 条）。
     *
     * <p>🔴 <b>本次改造</b>：每条片段的 {@code instruction} 字段不再是 Skill 全文，
     * 而是一条<b>清单摘要</b>（{@link #renderManifestEntry}）；{@code outputConstraint}
     * 恒为空串 —— 真正的正文与输出约束改由 {@link #loadOnDemand} 按需给出。
     * 不做资源批量查询（{@code skill_resources} 只在 {@link #loadOnDemand} 里单版本查）。
     */
    @Transactional(readOnly = true)
    public SkillInjectionFragment.Injection resolveInstructions(AgentVersion agentVersion,
                                                               SkillRuntimeContext context) {
        List<SkillVersionService.ResolvedSkillBinding> bindings =
                skillVersionService.resolveBindings(agentVersion);
        if (bindings.isEmpty()) {
            return SkillInjectionFragment.Injection.empty();
        }

        List<SkillInjectionFragment> fragments = new ArrayList<>(bindings.size());
        for (SkillVersionService.ResolvedSkillBinding resolved : bindings) {
            String manifest = renderManifestEntry(resolved.skill());
            fragments.add(new SkillInjectionFragment(
                    resolved.skill().getId(),
                    resolved.skill().getSkillKey(),
                    resolved.version().getId(),
                    resolved.version().getVersion(),
                    resolved.binding().getSortOrder(),
                    manifest,
                    "",
                    AuditDigest.digest(manifest)));
        }

        // 🔴 清单本身不含正文，可安全记完整 digest 用于排障
        log.debug("Skill 清单已装配：agentVersionId={} fragments={}",
                agentVersion.getId(), fragments.size());
        return new SkillInjectionFragment.Injection(fragments);
    }

    /**
     * 按需加载单个 Skill 的完整正文（🔴 供 {@code tool/SkillLoadHandler} 调用，本类第二消费入口）。
     *
     * <p>与 {@link #resolveInstructions} 共用同一条绑定解析（{@link SkillVersionService#resolveBindings}），
     * 保证"清单里出现过的 skillKey"与"能被加载的 skillKey"永远一致，不存在清单展示了
     * 但加载不到的分裂态。找不到匹配 {@code skillKey} 时返回空（🔴 不是异常）：
     * 调用方（模型）传来的 skillKey 是<b>自由文本</b>，不在清单内是正常输入，
     * 不应打断整条工具调用链路（与 §7.6.3 的"清单外一律拒绝"是<b>不同层</b>的语义 ——
     * 那是工具授权维度，这里是<b>工具参数取值</b>维度）。
     *
     * @param agentVersion 会话绑定的 Agent 版本（🔴 快照）
     * @param skillKey     模型请求加载的技能键（对应清单里下发过的 skillKey）
     * @param context      运行时上下文（locale/timezone 内置变量来源）
     * @throws BusinessException 30060 该 Agent 版本的绑定链本身非法（与 resolveInstructions 同语义）
     */
    @Transactional(readOnly = true)
    public Optional<SkillLoadResult> loadOnDemand(AgentVersion agentVersion, String skillKey,
                                                  SkillRuntimeContext context) {
        if (skillKey == null || skillKey.isBlank()) {
            return Optional.empty();
        }
        List<SkillVersionService.ResolvedSkillBinding> bindings =
                skillVersionService.resolveBindings(agentVersion);
        SkillVersionService.ResolvedSkillBinding resolved = bindings.stream()
                .filter(b -> skillKey.equals(b.skill().getSkillKey()))
                .findFirst()
                .orElse(null);
        if (resolved == null) {
            log.warn("skill_load 请求了未绑定的 skillKey：agentVersionId={}", agentVersion.getId());
            return Optional.empty();
        }

        SkillRuntimeContext effective = enrichIfNeeded(List.of(resolved), context);
        SkillVersion version = resolved.version();
        List<SkillVariableDecl> declarations = skillValidator.parseDeclarations(version);
        Map<String, String> bindingValues = skillValidator.parseBindingValues(
                resolved.binding().getAgentVersionId(), resolved.binding().getVariableValues());
        Map<String, String> values = skillValidator.validateForInjection(
                version, declarations, bindingValues, effective);

        String instruction = variableResolver.substitute(version.getInstruction(), values);
        String outputConstraint = variableResolver.substitute(
                version.getOutputConstraint() == null ? "" : version.getOutputConstraint(), values);
        List<SkillResource> resources = skillResourceRepository
                .findBySkillVersionIdInOrderBySkillVersionIdAscResourceOrderAsc(
                        List.of(version.getId()));
        String renderedResources = renderSkillResources(resolved.skill().getSkillKey(), resources, values);

        // 🔴 只记标识与 digest（正文本身即将随工具结果回灌模型，此处日志纪律不变）
        log.debug("Skill 按需加载完成：skillVersionId={} digest={}",
                version.getId(), AuditDigest.digest(instruction));
        return Optional.of(new SkillLoadResult(resolved.skill().getSkillKey(),
                resolved.skill().getName(), instruction, outputConstraint, renderedResources));
    }

    /**
     * 按需解析某 Skill 版本下的单个<b>可执行</b>脚本资源（🔴 供 {@code tool/SkillExecHandler}
     * 调用，本类第三消费入口；与 {@link #loadOnDemand} 同源，仅多一层"必须是可执行资源"约束）。
     *
     * <p>找不到匹配 {@code skillKey}、或匹配到的资源 {@code executable=0} 时均返回空
     * （🔴 不是异常，语义与 {@link #loadOnDemand} 对"清单外 skillKey"的处理一致）：
     * 调用方传来的 {@code skillKey}/{@code resourcePath} 都是自由文本，"要执行的资源根本不允许
     * 被执行"是正常业务分支，不应打断工具调用链路。
     *
     * @param agentVersion 会话绑定的 Agent 版本（🔴 快照）
     * @param skillKey     模型请求执行脚本所属的技能键
     * @param resourcePath 模型请求执行的资源路径（须精确匹配 {@code skill_resources.resource_path}）
     * @param context      运行时上下文（locale/timezone 内置变量来源）
     * @throws BusinessException 30060 该 Agent 版本的绑定链本身非法（与 loadOnDemand 同语义）
     */
    @Transactional(readOnly = true)
    public Optional<SkillScriptResult> loadExecutableScript(AgentVersion agentVersion, String skillKey,
                                                             String resourcePath, SkillRuntimeContext context) {
        if (skillKey == null || skillKey.isBlank() || resourcePath == null || resourcePath.isBlank()) {
            return Optional.empty();
        }
        List<SkillVersionService.ResolvedSkillBinding> bindings =
                skillVersionService.resolveBindings(agentVersion);
        SkillVersionService.ResolvedSkillBinding resolved = bindings.stream()
                .filter(b -> skillKey.equals(b.skill().getSkillKey()))
                .findFirst()
                .orElse(null);
        if (resolved == null) {
            log.warn("skill_exec 请求了未绑定的 skillKey：agentVersionId={}", agentVersion.getId());
            return Optional.empty();
        }
        SkillVersion version = resolved.version();
        SkillResource resource = skillResourceRepository
                .findBySkillVersionIdAndResourcePath(version.getId(), resourcePath)
                .orElse(null);
        if (resource == null || !resource.isExecutable()) {
            // 🔴 资源不存在 / 存在但未标记 executable=1：一律拒绝，不区分"哪种情况"回给模型
            //    （避免让模型探测"该资源是否存在"，收窄信息泄露面）
            log.warn("skill_exec 请求了不存在或不可执行的资源：agentVersionId={} resourcePath={}",
                    agentVersion.getId(), resourcePath);
            return Optional.empty();
        }

        SkillRuntimeContext effective = enrichIfNeeded(List.of(resolved), context);
        List<SkillVariableDecl> declarations = skillValidator.parseDeclarations(version);
        Map<String, String> bindingValues = skillValidator.parseBindingValues(
                resolved.binding().getAgentVersionId(), resolved.binding().getVariableValues());
        Map<String, String> values = skillValidator.validateForInjection(
                version, declarations, bindingValues, effective);
        String content = variableResolver.substitute(resource.getContent(), values);

        // 🔴 只记标识与 digest（脚本正文即将回灌给 SkillExecHandler 执行，不落日志）
        log.debug("Skill 可执行脚本已解析：skillVersionId={} resourcePath={} digest={}",
                version.getId(), resourcePath, AuditDigest.digest(content));
        return Optional.of(new SkillScriptResult(resolved.skill().getSkillKey(), resourcePath,
                resource.getContentType(), content));
    }

    /**
     * 渲染单条技能的清单摘要（🔴 体量必须远小于全文，才能"无条件全量下发"）。
     *
     * <p>{@code skill_load} 是硬编码字符串常量，而非引用 {@code tool} 包的
     * {@code SkillLoadHandler.TOOL_KEY} —— 这是刻意的（见类注释「边界纪律」第一条）；
     * 两处必须保持一致，改名时需同步改这里的注释与 {@code SkillLoadHandler}。
     */
    private String renderManifestEntry(Skill skill) {
        return "【可用技能 skillKey=\"" + skill.getSkillKey() + "\"】" + skill.getName()
                + " —— " + skill.getDescription() + System.lineSeparator()
                + "若本轮用户任务与该技能匹配，请先调用工具 skill_load"
                + "（入参 {\"skillKey\":\"" + skill.getSkillKey() + "\"}）获取完整执行指引与资料后再作答；"
                + "不要在未加载的情况下凭空猜测该技能的细节。";
    }

    /**
     * 仅当有 Skill 引用 {@code locale} / {@code timezone} 时补齐租户主数据。
     */
    private SkillRuntimeContext enrichIfNeeded(
            List<SkillVersionService.ResolvedSkillBinding> bindings,
            SkillRuntimeContext context) {
        SkillRuntimeContext base = context == null
                ? SkillRuntimeContext.ofTenant("") : context;
        if (!base.locale().isBlank() && !base.timezone().isBlank()) {
            return base;
        }
        boolean needed = bindings.stream().anyMatch(resolved ->
                variableResolver.needsTenantProfile(resolved.version().getInstruction(),
                        resolved.version().getOutputConstraint()));
        if (!needed) {
            return base;
        }
        // 🔴 已裁决（architecture.md V1.3.1 §5.1.2 G11）：跨模块只允许 Service → Service，
        //    因此这里注入 platform/service/TenantService，🔴 禁止直连 TenantRepository
        //    （直连会绕过 TenantService 的"租户不存在/已删除"统一判定）。
        try {
            TenantProfile profile = tenantService.profileOf(base.tenantId());
            return base.withTenantProfile(profile.locale(), profile.timezone());
        } catch (BusinessException e) {
            // 租户主数据不可用：内置变量退化为空串，由 SkillValidator 按 required 语义判定
            // （🔴 不在这里静默塞入错误值，也不让整条链路以系统错误失败）
            log.warn("租户主数据不可用，locale/timezone 内置变量留空：tenantId={} code={}",
                    base.tenantId(), e.getCode());
            return base;
        }
    }

    /**
     * 把某 Skill 版本关联的资源文件（{@code skill_resources} 表）渲染为文本片段，
     * 作为 instruction 的补充素材。资源按 {@code resource_order} 升序拼接（RES-001 字节序）。
     *
     * @param skillKey  所属技能键（🔴 仅用于拼接 {@code skill_exec} 调用示例，不做其他用途）
     * @param resources 该版本关联的资源（已按 versionId + resource_order 排序）
     * @param values    变量值映射，资源正文同样经过变量替换（无变量时为恒等变换）
     */
    private String renderSkillResources(String skillKey, List<SkillResource> resources,
                                        Map<String, String> values) {
        if (resources.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== 附：Skill 资源文件（由 skill_resources 表注入，供本次执行参考）===");
        for (SkillResource r : resources) {
            sb.append(System.lineSeparator()).append(System.lineSeparator())
              .append("#### 资源 ").append(r.getResourcePath());
            if (r.isExecutable()) {
                // 🔴 告知模型该资源可被 skill_exec 执行，而不是让模型凭空猜测执行入口
                sb.append("（可通过工具 skill_exec 执行：入参 {\"skillKey\":\"").append(skillKey)
                  .append("\",\"resourcePath\":\"").append(r.getResourcePath())
                  .append("\"}，language=").append(r.getContentType()).append("）");
            }
            sb.append(System.lineSeparator())
              .append(variableResolver.substitute(r.getContent(), values));
        }
        return sb.toString();
    }
}
