package com.eyes.albedo.configcheck;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eyes.albedo.agent.entity.Agent;
import com.eyes.albedo.agent.entity.AgentCapabilityBinding;
import com.eyes.albedo.agent.entity.AgentVersion;
import com.eyes.albedo.agent.repository.AgentCapabilityBindingRepository;
import com.eyes.albedo.agent.repository.AgentRepository;
import com.eyes.albedo.agent.repository.AgentVersionRepository;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.common.SystemPromptBudget;
import com.eyes.albedo.configcheck.dto.ConfigValidationReportDTO;
import com.eyes.albedo.configcheck.dto.ConfigViolationDTO;
import com.eyes.albedo.mcp.CidrMatcher;
import com.eyes.albedo.mcp.CredentialCipher;
import com.eyes.albedo.mcp.entity.McpServer;
import com.eyes.albedo.mcp.entity.McpTool;
import com.eyes.albedo.mcp.repository.McpServerRepository;
import com.eyes.albedo.mcp.repository.McpToolRepository;
import com.eyes.albedo.site.dto.SiteConfigContent;
import com.eyes.albedo.site.dto.ViolationDTO;
import com.eyes.albedo.site.entity.SiteConfigVersion;
import com.eyes.albedo.site.repository.SiteConfigVersionRepository;
import com.eyes.albedo.site.service.SiteConfigValidator;
import com.eyes.albedo.skill.SkillValidator;
import com.eyes.albedo.skill.SkillVariableResolver;
import com.eyes.albedo.skill.dto.SkillRuntimeContext;
import com.eyes.albedo.skill.dto.SkillVariableDecl;
import com.eyes.albedo.skill.entity.Skill;
import com.eyes.albedo.skill.entity.SkillVersion;
import com.eyes.albedo.skill.repository.SkillRepository;
import com.eyes.albedo.skill.repository.SkillVersionRepository;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.sysconfig.ConfigService;
import com.eyes.albedo.tool.ToolFunctionNames;
import com.eyes.albedo.tool.entity.LocalTool;
import com.eyes.albedo.tool.entity.TenantToolGrant;
import com.eyes.albedo.tool.repository.LocalToolRepository;
import com.eyes.albedo.tool.repository.TenantToolGrantRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运行时配置校验器（ADR-013 三件套之① + ③，api-spec §7.3.1 / AC-CFG-003 / AC-CFG-004）。
 *
 * <p><b>为什么一期必须有它</b>：租户级配置由 DBA 直接写库（DEC-010），没有"保存时校验"这个应用层入口。
 * 若不提供独立校验，非法配置只会在<b>终端用户对话时</b>暴露（用户成为第一个发现配置错误的人），
 * 违反 AC-CFG-003。本类同时承担两个角色：
 * <ol>
 *   <li><b>独立校验入口</b>（DBA 改库后自查，{@code POST /api/v1/admin/config/validate} 与
 *       {@code backend/scripts/validate-config.sh}）</li>
 *   <li><b>运行时兜底</b>（AC-CFG-004）：非法配置必须在<b>进入模型或工具执行之前</b>
 *       以 {@code 30060} 明确失败并带 {@code data.violations[]}，
 *       🔴 禁止 NPE / 未分类 500 / 字符串码 / 白屏</li>
 * </ol>
 *
 * <p>🔴 <b>跨租户引用检测</b>：租户表查询由 Hibernate discriminator 自动加 {@code tenant_id}，
 * 因此"引用了别租户的 skillId"表现为<b>查不到</b>；本类把这种情况判为
 * {@code crossTenantReference}（而不是 {@code refNotFound}）的依据是——
 * 引用方与被引用方处在同一租户上下文中查询，查不到即意味着不同租户或不存在，
 * 二者对外都<b>不泄露存在性</b>（api-spec §7.3.1 要求非当前租户对象一律 {@code 10004}）。
 *
 * <p>🔴 {@code violations[].message} 禁含：密钥/凭据明文或片段、内部 IP/域名/端口、堆栈、
 * 其他租户资源存在性。{@code endpoint} 非法只回"不在允许范围内"，不回显解析出的 IP。
 *
 * <p>⚠️ <b>已知边界（🔴 M3 第四阶段更新）</b>：
 * <ul>
 *   <li>✅ <b>G10 已收尾</b>：{@code objectType=agentVersion} 且 {@code includeReferences=true} 时，
 *       本入口<b>已按 api-spec §7.3.1 G10 表递归覆盖三类绑定</b>
 *       （{@code skill}/{@code mcpTool}/{@code localTool}），深度固定 2 层、{@code visited} 去重、
 *       环 → {@code 30060 rule=circularReference}、批量查询 ≤4 次，
 *       并一并覆盖<b>只在聚合层面可见</b>的两项：system 提示预算与模型函数名长度/碰撞。
 *       🔴 {@code warnings[] rule=referencesNotFullyChecked} 现在<b>仅在
 *       {@code includeReferences=false} 时出现</b>（"确实没查引用"仍必须提示，
 *       否则调用方无法区分"没查"与"查了没问题"）</li>
 *   <li>SSRF 只做<b>不发起网络</b>的静态校验（协议 / 端口 / IP 字面量命中 CIDR）；
 *       DNS 解析后逐 IP 校验由 {@code mcp/SsrfGuard} 承担（ADR-009），
 *       已在连接测试 / 工具发现 / 每次调用前三处生效</li>
 *   <li>凭据只校验<b>格式与密钥版本</b>（ADR-012 限定"解密只发生在运行时调用前"），
 *       故 AAD 不匹配（密文跨租户搬运）只能在运行时暴露</li>
 *   <li>{@code local_tools} 的"注册表有行但平台无实现体 → 30060"由
 *       {@code tool/LocalToolRegistry} 在<b>清单构造阶段</b>覆盖；🔴 本入口不重复校验 ——
 *       实现体是否存在取决于<b>运行中的 JVM</b>，而不是库内数据，
 *       在校验入口断言它会得出"换个部署版本结论就变"的不稳定结果</li>
 * </ul>
 */
@Slf4j
@Service
public class RuntimeConfigValidator {

    // ===== objectType 枚举（api-spec §7.3.1） =====
    public static final String TYPE_AGENT = "agent";
    public static final String TYPE_AGENT_VERSION = "agentVersion";
    public static final String TYPE_SKILL = "skill";
    public static final String TYPE_SKILL_VERSION = "skillVersion";
    public static final String TYPE_MCP = "mcp";
    public static final String TYPE_LOCAL_TOOL = "localTool";
    public static final String TYPE_TOOL_GRANT = "toolGrant";
    public static final String TYPE_SITE_CONFIG = "siteConfig";

    public static final Set<String> SUPPORTED_TYPES = Set.of(
            TYPE_AGENT, TYPE_AGENT_VERSION, TYPE_SKILL, TYPE_SKILL_VERSION,
            TYPE_MCP, TYPE_LOCAL_TOOL, TYPE_TOOL_GRANT, TYPE_SITE_CONFIG);

    /**
     * {@code {{variable}}} 占位符：{@code name} 匹配 {@code ^[a-zA-Z][a-zA-Z0-9_]{0,63}$}。
     *
     * <p>不匹配该规则的 <code>{{…}}</code> 原样保留（不解释、不报错），避免与 Markdown / 代码块冲突
     * （api-spec §7.5.3 第 1 条）。
     */
    private static final Pattern VARIABLE_PLACEHOLDER =
            Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9_]{0,63})}}");

    /** 平台内置只读变量白名单：🔴 保留名，Skill 声明同名变量 → 30060（避免覆盖平台语义）。 */
    private static final Set<String> RESERVED_VARIABLES =
            Set.of("tenantId", "locale", "timezone", "nowIso");

    /** {@code tenant_tool_grants.config} 中的可执行片段特征（🔴 命中 → 30060）。 */
    private static final Pattern EXECUTABLE_CONFIG = Pattern.compile(
            "(<\\s*script)|(javascript\\s*:)|(\\bfunction\\s*\\()|(=>)|(\\beval\\s*\\()"
                    + "|(\\$\\{)|(#\\{)|(\\bnew\\s+ProcessBuilder\\b)|(Runtime\\s*\\.\\s*getRuntime)",
            Pattern.CASE_INSENSITIVE);

    /** Skill key / Agent key 命名规则。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]{1,63}$");

    private final SkillRepository skillRepository;
    private final SkillVersionRepository skillVersionRepository;
    private final McpServerRepository mcpServerRepository;
    private final McpToolRepository mcpToolRepository;
    private final LocalToolRepository localToolRepository;
    private final TenantToolGrantRepository tenantToolGrantRepository;
    private final AgentRepository agentRepository;
    private final AgentVersionRepository agentVersionRepository;
    private final AgentCapabilityBindingRepository bindingRepository;
    private final SiteConfigVersionRepository siteConfigVersionRepository;
    private final SiteConfigValidator siteConfigValidator;
    private final CredentialCipher credentialCipher;
    private final BusinessConfig businessConfig;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;
    private final SkillValidator skillValidator;
    private final SkillVariableResolver variableResolver;

    public RuntimeConfigValidator(SkillRepository skillRepository,
                                  SkillVersionRepository skillVersionRepository,
                                  McpServerRepository mcpServerRepository,
                                  McpToolRepository mcpToolRepository,
                                  LocalToolRepository localToolRepository,
                                  TenantToolGrantRepository tenantToolGrantRepository,
                                  AgentRepository agentRepository,
                                  AgentVersionRepository agentVersionRepository,
                                  AgentCapabilityBindingRepository bindingRepository,
                                  SiteConfigVersionRepository siteConfigVersionRepository,
                                  SiteConfigValidator siteConfigValidator,
                                  CredentialCipher credentialCipher,
                                  BusinessConfig businessConfig,
                                  ConfigService configService,
                                  ObjectMapper objectMapper,
                                  SkillValidator skillValidator,
                                  SkillVariableResolver variableResolver) {
        this.skillRepository = skillRepository;
        this.skillVersionRepository = skillVersionRepository;
        this.mcpServerRepository = mcpServerRepository;
        this.mcpToolRepository = mcpToolRepository;
        this.localToolRepository = localToolRepository;
        this.tenantToolGrantRepository = tenantToolGrantRepository;
        this.agentRepository = agentRepository;
        this.agentVersionRepository = agentVersionRepository;
        this.bindingRepository = bindingRepository;
        this.siteConfigVersionRepository = siteConfigVersionRepository;
        this.siteConfigValidator = siteConfigValidator;
        this.credentialCipher = credentialCipher;
        this.businessConfig = businessConfig;
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.skillValidator = skillValidator;
        this.variableResolver = variableResolver;
    }

    /**
     * 校验单对象及其引用链。
     *
     * @param objectType        见 {@link #SUPPORTED_TYPES}
     * @param objectId          对象 ID（string，ADR-004）
     * @param includeReferences 是否沿引用链递归
     * @throws BusinessException 10001 objectType 非法；10004 对象不存在或跨租户（不泄露存在性）
     */
    @Transactional(readOnly = true)
    public ConfigValidationReportDTO validate(String objectType, String objectId,
                                              boolean includeReferences) {
        return validateWithQueryCount(objectType, objectId, includeReferences).report();
    }

    /**
     * 校验结果 + 递归批量查询次数（🔴 <b>供测试断言 G10 的「≤4 次查询、禁 N+1」</b>）。
     *
     * <p>🔴 为什么把计数从旁路返回而不是塞进 {@link ConfigValidationReportDTO}：
     * 该 DTO 的字段形状由 api-spec §7.3.1 固定，加字段会破坏契约
     * （前端与 @测试 都按固定形状断言）。而"查询次数"这条纪律必须<b>可机械验证</b> ——
     * 否则 N+1 只能靠人肉 review，而 review 恰恰是最容易放过它的地方。
     */
    @Transactional(readOnly = true)
    public ValidationOutcome validateWithQueryCount(String objectType, String objectId,
                                                    boolean includeReferences) {
        String type = objectType == null ? "" : objectType.trim();
        if (!SUPPORTED_TYPES.contains(type)) {
            throw BusinessException.validation("objectType 非法");
        }
        Ctx ctx = new Ctx();
        long id = parseId(objectId);

        switch (type) {
            case TYPE_SKILL -> validateSkill(ctx, requireSkill(id), includeReferences);
            case TYPE_SKILL_VERSION -> validateSkillVersion(ctx, requireSkillVersion(id));
            case TYPE_MCP -> validateMcp(ctx, requireMcp(id), includeReferences);
            case TYPE_LOCAL_TOOL -> validateLocalTool(ctx, requireLocalTool(id));
            case TYPE_TOOL_GRANT -> validateToolGrant(ctx, requireToolGrant(id));
            case TYPE_AGENT -> validateAgent(ctx, requireAgent(id), includeReferences);
            case TYPE_AGENT_VERSION ->
                    validateAgentVersion(ctx, requireAgentVersion(id), includeReferences);
            case TYPE_SITE_CONFIG -> validateSiteConfig(ctx, requireSiteConfigVersion(id));
            default -> throw BusinessException.validation("objectType 非法");
        }

        ConfigValidationReportDTO report = new ConfigValidationReportDTO(type, String.valueOf(id),
                ctx.violations.isEmpty(), ctx.checked, List.copyOf(ctx.violations),
                List.copyOf(ctx.warnings));
        return new ValidationOutcome(report, ctx.referenceQueries);
    }

    /**
     * 校验结果 + 递归查询次数。
     *
     * @param report           对外契约形状的报告
     * @param referenceQueries 递归层发起的批量查询次数（🔴 G10：≤4）
     */
    public record ValidationOutcome(ConfigValidationReportDTO report, int referenceQueries) {
    }

    // ===================== Skill =====================

    private void validateSkill(Ctx ctx, Skill skill, boolean includeReferences) {
        ctx.checked++;
        String type = TYPE_SKILL;
        if (skill.getSkillKey() == null || !KEY_PATTERN.matcher(skill.getSkillKey()).matches()) {
            ctx.violate(type, skill.getId(), "skillKey", ConfigViolationDTO.RULE_FORMAT,
                    "键名不符合 ^[a-z][a-z0-9_-]{1,63}$");
        }
        if (isBlank(skill.getName())) {
            ctx.violate(type, skill.getId(), "name", ConfigViolationDTO.RULE_REQUIRED, "必填项不能为空");
        }
        if (!Set.of(Skill.STATUS_ENABLED, Skill.STATUS_DISABLED).contains(skill.getStatus())) {
            ctx.violate(type, skill.getId(), "status", ConfigViolationDTO.RULE_ENUM,
                    "状态取值必须是 enabled 或 disabled");
        }
        int current = skill.getCurrentVersion() == null ? 0 : skill.getCurrentVersion();
        if (current > 0) {
            Optional<SkillVersion> published =
                    skillVersionRepository.findBySkillIdAndVersion(skill.getId(), current);
            if (published.isEmpty()) {
                // 指针指向不存在的版本：运行时注入会拿不到指令，必须提前暴露
                ctx.violate(type, skill.getId(), "currentVersion",
                        ConfigViolationDTO.RULE_POINTER_BROKEN,
                        "当前版本指针指向的版本不存在");
            } else {
                if (!SkillVersion.STATUS_PUBLISHED.equals(published.get().getStatus())) {
                    ctx.violate(type, skill.getId(), "currentVersion",
                            ConfigViolationDTO.RULE_REF_UNAVAILABLE,
                            "当前版本指针指向的版本不是已发布状态");
                }
                if (includeReferences) {
                    validateSkillVersion(ctx, published.get());
                }
            }
        } else if (Skill.STATUS_ENABLED.equals(skill.getStatus())) {
            // 启用但无可用版本：绑定它的 Agent 版本在运行时会注入失败
            ctx.violate(type, skill.getId(), "currentVersion",
                    ConfigViolationDTO.RULE_POINTER_BROKEN,
                    "已启用但没有可用的已发布版本");
        }
    }

    private void validateSkillVersion(Ctx ctx, SkillVersion version) {
        ctx.checked++;
        String type = TYPE_SKILL_VERSION;

        // 🔴 同租户归属：派生查询会被追加 tenant_id 条件，查不到即跨租户或不存在
        if (version.getSkillId() == null
                || skillRepository.findOneById(version.getSkillId()).isEmpty()) {
            ctx.violate(type, version.getId(), "skillId", ConfigViolationDTO.RULE_CROSS_TENANT,
                    "引用的 Skill 不在当前租户内");
        }
        if (!Set.of(SkillVersion.STATUS_DRAFT, SkillVersion.STATUS_PUBLISHED,
                SkillVersion.STATUS_ARCHIVED).contains(version.getStatus())) {
            ctx.violate(type, version.getId(), "status", ConfigViolationDTO.RULE_ENUM,
                    "状态取值必须是 draft / published / archived");
        }

        String instruction = version.getInstruction();
        int instructionMax = businessConfig.requireInt(ConfigKeys.GROUP_SKILL,
                ConfigKeys.SKILL_INSTRUCTION_MAX_CHARS);
        if (isBlank(instruction)) {
            ctx.violate(type, version.getId(), "instruction", ConfigViolationDTO.RULE_REQUIRED,
                    "指令正文不能为空");
            instruction = "";
        } else if (instruction.codePointCount(0, instruction.length()) > instructionMax) {
            ctx.violate(type, version.getId(), "instruction", ConfigViolationDTO.RULE_LENGTH,
                    "指令正文长度超过上限 " + instructionMax);
        }

        String outputConstraint = version.getOutputConstraint();
        if (outputConstraint != null
                && outputConstraint.codePointCount(0, outputConstraint.length())
                > SkillVersion.OUTPUT_CONSTRAINT_MAX_CHARS) {
            ctx.violate(type, version.getId(), "outputConstraint", ConfigViolationDTO.RULE_LENGTH,
                    "输出约束长度超过上限 " + SkillVersion.OUTPUT_CONSTRAINT_MAX_CHARS);
        }

        validateVariables(ctx, version, instruction);
    }

    /**
     * {@code {{variable}}} 声明与使用一致性（api-spec §7.5.3）。
     *
     * <p>规则：未声明即使用 → {@code undeclaredVariable}（阻断）；
     * 声明但未使用 → {@code unusedVariable}（仅 warning，不阻断）；
     * 占用平台保留名 → {@code reservedVariable}（阻断）。
     *
     * <p>⚠️ {@code required=true} 但"三级取值均为空" 的判定需要
     * {@code agent_capability_bindings.variable_values}，该表本阶段尚不存在，故暂不校验
     * （已在类注释登记为第二阶段补齐项）。
     */
    private void validateVariables(Ctx ctx, SkillVersion version, String instruction) {
        String type = TYPE_SKILL_VERSION;
        List<VariableDecl> declared = parseVariables(ctx, version);
        Set<String> declaredNames = new LinkedHashSet<>();
        int maxVariables = businessConfig.requireInt(ConfigKeys.GROUP_SKILL,
                ConfigKeys.SKILL_MAX_VARIABLES);
        if (declared.size() > maxVariables) {
            ctx.violate(type, version.getId(), "variablesSchema", ConfigViolationDTO.RULE_RANGE,
                    "声明变量数超过上限 " + maxVariables);
        }
        for (VariableDecl decl : declared) {
            if (decl.name() == null || decl.name().isBlank()) {
                ctx.violate(type, version.getId(), "variablesSchema",
                        ConfigViolationDTO.RULE_REQUIRED, "变量声明缺少 name");
                continue;
            }
            if (RESERVED_VARIABLES.contains(decl.name())) {
                ctx.violate(type, version.getId(), "variablesSchema",
                        ConfigViolationDTO.RULE_RESERVED_VARIABLE,
                        "变量名占用平台保留名：" + decl.name());
            }
            declaredNames.add(decl.name());
        }

        Set<String> used = new LinkedHashSet<>();
        Matcher matcher = VARIABLE_PLACEHOLDER.matcher(instruction == null ? "" : instruction);
        while (matcher.find()) {
            used.add(matcher.group(1));
        }
        for (String name : used) {
            if (!declaredNames.contains(name) && !RESERVED_VARIABLES.contains(name)) {
                ctx.violate(type, version.getId(), "instruction",
                        ConfigViolationDTO.RULE_UNDECLARED_VARIABLE,
                        "引用了未声明变量：{{" + name + "}}");
            }
        }
        for (String name : declaredNames) {
            if (!used.contains(name)) {
                ctx.warn(type, version.getId(), "variablesSchema",
                        ConfigViolationDTO.RULE_UNUSED_VARIABLE,
                        "已声明但未在指令中使用：" + name);
            }
        }
    }

    private List<VariableDecl> parseVariables(Ctx ctx, SkillVersion version) {
        String raw = version.getVariablesSchema();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<VariableDecl>>() {
            });
        } catch (Exception e) {
            ctx.violate(TYPE_SKILL_VERSION, version.getId(), "variablesSchema",
                    ConfigViolationDTO.RULE_FORMAT, "变量声明必须是 JSON 数组");
            return List.of();
        }
    }

    // ===================== MCP =====================

    private void validateMcp(Ctx ctx, McpServer server, boolean includeReferences) {
        ctx.checked++;
        String type = TYPE_MCP;

        // ① 传输：stdio 一律拒绝（不向租户开放，api-spec §7.6.1）
        if (McpServer.TRANSPORT_STDIO.equals(server.getTransport())) {
            ctx.violate(type, server.getId(), "transport",
                    ConfigViolationDTO.RULE_TRANSPORT_FORBIDDEN, "该传输方式不被支持");
        } else if (!Set.of(McpServer.TRANSPORT_STREAMABLE_HTTP, McpServer.TRANSPORT_SSE)
                .contains(server.getTransport())) {
            ctx.violate(type, server.getId(), "transport", ConfigViolationDTO.RULE_ENUM,
                    "传输方式取值必须是 streamable_http 或 sse");
        }

        // ② endpoint：HTTPS + 端口 + IP 字面量 CIDR（🔴 不做 DNS 解析、不回显解析结果）
        validateEndpoint(ctx, server);

        // ③ 凭据：格式 + 密钥版本（🔴 不解密、不回显任何片段）
        validateCredential(ctx, server);

        // ④ 超时：1 ~ tool.max_timeout_seconds
        int maxTimeout = businessConfig.requireInt(ConfigKeys.GROUP_TOOL,
                ConfigKeys.TOOL_MAX_TIMEOUT_SECONDS);
        Integer timeout = server.getTimeoutSeconds();
        if (timeout == null || timeout < 1 || timeout > maxTimeout) {
            ctx.violate(type, server.getId(), "timeoutSeconds", ConfigViolationDTO.RULE_RANGE,
                    "超时秒数必须在 1~" + maxTimeout + " 之间");
        }

        if (!Set.of(McpServer.STATUS_ENABLED, McpServer.STATUS_DISABLED).contains(server.getStatus())) {
            ctx.violate(type, server.getId(), "status", ConfigViolationDTO.RULE_ENUM,
                    "状态取值必须是 enabled 或 disabled");
        }

        if (includeReferences) {
            List<McpTool> tools = mcpToolRepository.findByMcpId(server.getId());
            int maxTools = businessConfig.requireInt(ConfigKeys.GROUP_MCP,
                    ConfigKeys.MCP_MAX_TOOLS_PER_SERVER);
            if (tools.size() > maxTools) {
                ctx.violate(type, server.getId(), "tools", ConfigViolationDTO.RULE_RANGE,
                        "工具数超过单服务上限 " + maxTools);
            }
            for (McpTool tool : tools) {
                validateMcpTool(ctx, tool, server);
            }
        }
    }

    /**
     * endpoint 静态校验。
     *
     * <p>🔴 只回"不在允许范围内"，<b>不回显</b>解析出的 IP / 端口 / 内网信息（api-spec §7.3.1）。
     * ⚠️ 完整 SSRF（DNS 解析全部 A/AAAA 后逐 IP 校验 + 每次调用前重校验 + 不跟随重定向）
     * 由第二阶段 {@code SsrfGuard} 承担（ADR-009）。
     */
    private void validateEndpoint(Ctx ctx, McpServer server) {
        String type = TYPE_MCP;
        String endpoint = server.getEndpoint();
        if (isBlank(endpoint)) {
            ctx.violate(type, server.getId(), "endpoint", ConfigViolationDTO.RULE_REQUIRED,
                    "服务地址不能为空");
            return;
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(endpoint.trim());
        } catch (IllegalArgumentException e) {
            ctx.violate(type, server.getId(), "endpoint", ConfigViolationDTO.RULE_FORMAT,
                    "服务地址格式非法");
            return;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            ctx.violate(type, server.getId(), "endpoint", ConfigViolationDTO.RULE_FORMAT,
                    "服务地址格式非法");
            return;
        }

        boolean requireHttps = businessConfig.requireBoolean(ConfigKeys.GROUP_MCP,
                ConfigKeys.MCP_REQUIRE_HTTPS);
        if (requireHttps && !"https".equalsIgnoreCase(uri.getScheme())) {
            ctx.violate(type, server.getId(), "endpoint", ConfigViolationDTO.RULE_SSRF_REJECTED,
                    "服务地址不在允许范围内");
            return;
        }

        List<String> blocked = cidrList(ConfigKeys.MCP_BLOCKED_IP_CIDRS);
        List<String> allowed = cidrList(ConfigKeys.MCP_ALLOWED_INTERNAL_CIDRS);
        boolean whitelisted = CidrMatcher.literalIpMatchesAny(host, allowed);
        if (!whitelisted && CidrMatcher.literalIpMatchesAny(host, blocked)) {
            ctx.violate(type, server.getId(), "endpoint", ConfigViolationDTO.RULE_SSRF_REJECTED,
                    "服务地址不在允许范围内");
            return;
        }
        // 端口：仅允许 443（或白名单场景下的显式端口）
        int port = uri.getPort();
        if (port != -1 && port != 443 && !whitelisted) {
            ctx.violate(type, server.getId(), "endpoint", ConfigViolationDTO.RULE_SSRF_REJECTED,
                    "服务地址不在允许范围内");
        }
    }

    private void validateCredential(Ctx ctx, McpServer server) {
        String type = TYPE_MCP;
        String authType = server.getAuthType();
        if (!Set.of(McpServer.AUTH_TYPE_NONE, McpServer.AUTH_TYPE_BEARER,
                McpServer.AUTH_TYPE_HEADER).contains(authType)) {
            ctx.violate(type, server.getId(), "authType", ConfigViolationDTO.RULE_ENUM,
                    "鉴权方式取值必须是 none / bearer / header");
            return;
        }
        if (McpServer.AUTH_TYPE_NONE.equals(authType)) {
            return;
        }
        if (isBlank(server.getCredentialCipher())) {
            ctx.violate(type, server.getId(), "credentialCipher",
                    ConfigViolationDTO.RULE_CREDENTIAL_MISSING, "该鉴权方式需要配置凭据");
            return;
        }
        // 🔴 只校验格式与密钥版本；不解密、不回显任何片段
        if (!credentialCipher.isValidFormat(server.getCredentialCipher())) {
            ctx.violate(type, server.getId(), "credentialCipher",
                    ConfigViolationDTO.RULE_INVALID_CIPHER, "凭据密文格式非法或密钥版本不受支持");
        }
        Integer keyVersion = server.getCredentialKeyVersion();
        if (keyVersion == null || keyVersion != CredentialCipher.CURRENT_KEY_VERSION) {
            ctx.violate(type, server.getId(), "credentialKeyVersion",
                    ConfigViolationDTO.RULE_INVALID_CIPHER, "凭据密文格式非法或密钥版本不受支持");
        }
    }

    private void validateMcpTool(Ctx ctx, McpTool tool, McpServer server) {
        ctx.checked++;
        String type = "mcpTool";
        String expectedPrefix = server.getMcpKey() + ":";
        if (tool.getToolKey() == null || !tool.getToolKey().startsWith(expectedPrefix)) {
            ctx.violate(type, tool.getId(), "toolKey", ConfigViolationDTO.RULE_FORMAT,
                    "工具键必须形如 {mcpKey}:{toolName}");
        }
        validateJsonSchema(ctx, type, tool.getId(), "inputSchema", tool.getInputSchema(), false);
        // 🔴 已授权但所属服务被停用：运行时会拒绝（30050），提前以 warning 暴露给 DBA
        if (tool.grantedAndEnabled() && !McpServer.STATUS_ENABLED.equals(server.getStatus())) {
            ctx.warn(type, tool.getId(), "granted", ConfigViolationDTO.RULE_REF_UNAVAILABLE,
                    "工具已授权但所属 MCP 服务未启用，运行时不会进入可调用清单");
        }
    }

    // ===================== 本地 Tool 与授权 =====================

    private void validateLocalTool(Ctx ctx, LocalTool tool) {
        ctx.checked++;
        String type = TYPE_LOCAL_TOOL;
        if (tool.getToolKey() == null
                || !Pattern.matches(LocalTool.TOOL_KEY_PATTERN, tool.getToolKey())) {
            ctx.violate(type, tool.getId(), "toolKey", ConfigViolationDTO.RULE_FORMAT,
                    "键名不符合 ^[a-z][a-z0-9_]{1,63}$");
        }
        if (isBlank(tool.getName())) {
            ctx.violate(type, tool.getId(), "name", ConfigViolationDTO.RULE_REQUIRED, "必填项不能为空");
        }
        // 🔴 input_schema 必须是 draft 2020-12 且根类型 object（api-spec §7.7.1）
        validateJsonSchema(ctx, type, tool.getId(), "inputSchema", tool.getInputSchema(), true);

        int maxTimeout = businessConfig.requireInt(ConfigKeys.GROUP_TOOL,
                ConfigKeys.TOOL_MAX_TIMEOUT_SECONDS);
        Integer timeout = tool.getTimeoutSeconds();
        if (timeout == null || timeout < 1 || timeout > maxTimeout) {
            ctx.violate(type, tool.getId(), "timeoutSeconds", ConfigViolationDTO.RULE_RANGE,
                    "超时秒数必须在 1~" + maxTimeout + " 之间");
        }
        if (!Set.of(LocalTool.STATUS_ENABLED, LocalTool.STATUS_DISABLED).contains(tool.getStatus())) {
            ctx.violate(type, tool.getId(), "status", ConfigViolationDTO.RULE_ENUM,
                    "状态取值必须是 enabled 或 disabled");
        }
    }

    private void validateToolGrant(Ctx ctx, TenantToolGrant grant) {
        ctx.checked++;
        String type = TYPE_TOOL_GRANT;
        Optional<LocalTool> tool = localToolRepository.findByToolKey(grant.getToolKey());
        if (tool.isEmpty()) {
            ctx.violate(type, grant.getId(), "toolKey", ConfigViolationDTO.RULE_REF_NOT_FOUND,
                    "授权指向的平台 Tool 不存在");
        } else {
            ctx.checked++;
            if (grant.grantedAndEnabled()
                    && !LocalTool.STATUS_ENABLED.equals(tool.get().getStatus())) {
                ctx.warn(type, grant.getId(), "toolKey", ConfigViolationDTO.RULE_REF_UNAVAILABLE,
                        "已授权但平台 Tool 未启用，运行时不会进入可调用清单");
            }
        }
        if (!Set.of(TenantToolGrant.STATUS_ENABLED, TenantToolGrant.STATUS_DISABLED)
                .contains(grant.getStatus())) {
            ctx.violate(type, grant.getId(), "status", ConfigViolationDTO.RULE_ENUM,
                    "状态取值必须是 enabled 或 disabled");
        }
        validateGrantConfig(ctx, grant);
    }

    /**
     * {@code config} 必须是<b>非代码配置</b>：🔴 出现脚本 / 表达式 / 可执行片段 → {@code 30060}。
     *
     * <p>为什么要查：一期由 DBA 直接写库，{@code config} 是唯一"租户可控且会进入执行链"的自由文本；
     * 若允许表达式，等于打开了"租户上传可执行代码"的后门（PRD 明确列为非范围项）。
     */
    private void validateGrantConfig(Ctx ctx, TenantToolGrant grant) {
        String raw = grant.getConfig();
        if (raw == null || raw.isBlank()) {
            return;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(raw);
        } catch (Exception e) {
            ctx.violate(TYPE_TOOL_GRANT, grant.getId(), "config", ConfigViolationDTO.RULE_FORMAT,
                    "配置必须是合法 JSON");
            return;
        }
        if (EXECUTABLE_CONFIG.matcher(node.toString()).find()) {
            ctx.violate(TYPE_TOOL_GRANT, grant.getId(), "config",
                    ConfigViolationDTO.RULE_EXECUTABLE_CONFIG,
                    "配置中不允许出现脚本或表达式片段");
        }
    }

    // ===================== Agent =====================

    private void validateAgent(Ctx ctx, Agent agent, boolean includeReferences) {
        ctx.checked++;
        String type = TYPE_AGENT;
        if (agent.getAgentKey() == null || !KEY_PATTERN.matcher(agent.getAgentKey()).matches()) {
            ctx.violate(type, agent.getId(), "agentKey", ConfigViolationDTO.RULE_FORMAT,
                    "键名不符合 ^[a-z][a-z0-9_-]{1,63}$");
        }
        long current = agent.getCurrentVersion() == null ? 0L : agent.getCurrentVersion();
        if (current > 0) {
            Optional<AgentVersion> version =
                    agentVersionRepository.findByAgentIdAndVersion(agent.getId(), current);
            if (version.isEmpty()) {
                ctx.violate(type, agent.getId(), "currentVersion",
                        ConfigViolationDTO.RULE_POINTER_BROKEN,
                        "当前版本指针指向的版本不存在");
            } else if (includeReferences) {
                validateAgentVersion(ctx, version.get(), true);
            }
        } else if (Agent.STATUS_ENABLED.equals(agent.getStatus())) {
            ctx.warn(type, agent.getId(), "currentVersion",
                    ConfigViolationDTO.RULE_POINTER_BROKEN,
                    "已启用但没有已发布版本，终端用户不可见");
        }
    }

    /**
     * Agent 版本自身校验 + <b>绑定引用链递归</b>（🔴 api-spec §7.3.1 G10 收尾，M3 签署条件之一）。
     *
     * <p><b>🔴 递归深度固定 2 层（禁止实现通用图遍历）</b>：
     * <pre>
     * 第 1 层：agentVersion → agent_capability_bindings（一次取全，走 idx_tenant_version_sort）
     * 第 2 层：按 capability_type 展开
     *          skill      → skills + skill_versions（精确版本存在 / published / 同租户 / 变量完整）
     *          mcpTool    → mcp_tools + mcp_servers（granted/status/endpoint SSRF/密文格式）
     *          localTool  → tenant_tool_grants + local_tools（授权四条件 / 实现体 / 超时区间 / config 非代码）
     * 🔴 **不存在第 3 层**（数据模型中无更深引用，architecture.md §13.5.10）；
     *    出现"需要第 3 层"的需求必须先回写 api-spec §7.3.1，🔴 严禁在此偷偷加深。
     * </pre>
     *
     * <p><b>🔴 为什么这一层不可省（G10 不放宽、不延期）</b>：{@code includeReferences=true} 是
     * AC-CFG-003「可独立调用校验」的<b>唯一</b>判据。若聚合入口不递归，DBA 就无法在
     * <b>用户对话之前</b>发现绑定层的配置错误 —— 而这正是 ADR-013 方案 A 被否决的原因。
     * 同时它也是 ① 的 system 提示预算与 ⑥ 的函数名碰撞这两类
     * <b>「只有聚合起来才能发现」</b>问题的唯一提前暴露点。
     *
     * <p><b>🔴 {@code referencesNotFullyChecked} 的精确消失规则</b>（api-spec §7.3.1）：
     * <b>当且仅当</b> {@code includeReferences=true} 且三类绑定全部完成递归时才不再出现；
     * 🔴 {@code includeReferences=false} 时<b>必须继续出现</b> —— 确实没查引用，
     * 不得因"功能已补齐"就不再提示，否则调用方无法区分"没查"与"查了没问题"。
     */
    private void validateAgentVersion(Ctx ctx, AgentVersion version, boolean includeReferences) {
        if (!ctx.enter(TYPE_AGENT_VERSION, version.getId())) {
            return;
        }
        try {
            validateAgentVersionSelf(ctx, version);
            if (!includeReferences) {
                // 🔴 未查引用 → 必须明示，禁止让 valid=true 被误读为"绑定层也已校验"
                ctx.warn(TYPE_AGENT_VERSION, version.getId(), "capabilityBindings",
                        ConfigViolationDTO.RULE_REFERENCES_NOT_FULLY_CHECKED,
                        "includeReferences=false，本次未沿能力绑定引用链递归校验");
                return;
            }
            validateCapabilityBindings(ctx, version);
        } finally {
            ctx.exit(TYPE_AGENT_VERSION, version.getId());
        }
    }

    /**
     * Agent 版本<b>自身</b>字段校验（不含引用链）。
     */
    private void validateAgentVersionSelf(Ctx ctx, AgentVersion version) {
        ctx.checked++;
        String type = TYPE_AGENT_VERSION;
        if (agentRepository.findByIdAndDeletedAtIsNull(version.getAgentId()).isEmpty()) {
            ctx.violate(type, version.getId(), "agentId", ConfigViolationDTO.RULE_CROSS_TENANT,
                    "引用的 Agent 不在当前租户内");
        }
        if (!Set.of(AgentVersion.TOOL_POLICY_DISABLED, "auto", "confirm")
                .contains(version.getToolPolicy())) {
            ctx.violate(type, version.getId(), "toolPolicy", ConfigViolationDTO.RULE_ENUM,
                    "工具策略取值必须是 disabled / auto / confirm");
        }
        if (isBlank(version.getSystemPrompt())) {
            // 🔴 只报字段名，绝不回显 systemPrompt 正文（内部资产）
            ctx.violate(type, version.getId(), "systemPrompt", ConfigViolationDTO.RULE_REQUIRED,
                    "必填项不能为空");
        }
        if (version.getMaxOutputTokens() == null || version.getMaxOutputTokens() <= 0) {
            ctx.violate(type, version.getId(), "maxOutputTokens", ConfigViolationDTO.RULE_RANGE,
                    "输出上限必须为正整数");
        }
        if (!Set.of(AgentVersion.STATUS_PUBLISHED, AgentVersion.STATUS_ARCHIVED)
                .contains(version.getStatus())) {
            ctx.violate(type, version.getId(), "status", ConfigViolationDTO.RULE_ENUM,
                    "状态取值必须是 published 或 archived");
        }
        if (version.getCapabilitySnapshot() != null && !version.getCapabilitySnapshot().isBlank()) {
            try {
                objectMapper.readTree(version.getCapabilitySnapshot());
            } catch (Exception e) {
                ctx.violate(type, version.getId(), "capabilitySnapshot",
                        ConfigViolationDTO.RULE_FORMAT, "能力快照必须是合法 JSON");
            }
        }
    }

    /**
     * 第 1 层 + 第 2 层递归（🔴 查询收敛：绑定表 1 次 + 三类被引用对象各 1 次 = <b>≤4 次</b>）。
     *
     * <p>🔴 <b>禁止 N+1</b>（同 §9.5.3 纪律）：本接口无 UI、QPS 极低，但<b>不允许</b>以此为由
     * 写逐个绑定单查 —— 一旦这里写成 N+1，就会被当作"可以这么写"的先例复制到运行时热路径。
     */
    private void validateCapabilityBindings(Ctx ctx, AgentVersion version) {
        // ===== 第 1 层：绑定表一次取全（查询 1/4）=====
        ctx.countQuery();
        List<AgentCapabilityBinding> bindings = bindingRepository
                .findByAgentVersionIdOrderBySortOrderAscRefIdAsc(version.getId());

        List<AgentCapabilityBinding> skillBindings = new ArrayList<>();
        List<Long> mcpToolIds = new ArrayList<>();
        List<Long> localGrantIds = new ArrayList<>();
        for (AgentCapabilityBinding binding : bindings) {
            ctx.checked++;
            String capabilityType = binding.getCapabilityType();
            if (AgentCapabilityBinding.TYPE_SKILL.equals(capabilityType)) {
                skillBindings.add(binding);
            } else if (AgentCapabilityBinding.TYPE_MCP_TOOL.equals(capabilityType)) {
                mcpToolIds.add(binding.getRefId());
            } else if (AgentCapabilityBinding.TYPE_LOCAL_TOOL.equals(capabilityType)) {
                localGrantIds.add(binding.getRefId());
            } else {
                ctx.violate("agentCapabilityBinding", binding.getId(), "capabilityType",
                        ConfigViolationDTO.RULE_ENUM,
                        "能力类型取值必须是 skill / mcpTool / localTool");
            }
        }

        // ===== 第 2 层：三类被引用对象各一次批量查（查询 2~4/4）=====
        List<String> functionNames = new ArrayList<>();
        List<SkillVersion> injectedVersions = validateSkillBindings(ctx, skillBindings);
        validateMcpToolBindings(ctx, mcpToolIds, functionNames);
        validateLocalToolBindings(ctx, localGrantIds, functionNames);

        // ===== 🔴 只在聚合层面可见的两项校验（api-spec §7.3.1 G10「新增校验项」）=====
        validateSystemPromptBudget(ctx, version, skillBindings, injectedVersions);
        validateFunctionNames(ctx, version, functionNames);
    }

    /**
     * 第 2 层 · {@code skill} 绑定（🔴 <b>精确版本引用</b>，禁止"最新"语义，AC-SKL-002）。
     *
     * @return 按绑定顺序解析出的 Skill 版本（供 system 预算累加；🔴 顺序 = 注入顺序）
     */
    private List<SkillVersion> validateSkillBindings(Ctx ctx,
                                                     List<AgentCapabilityBinding> skillBindings) {
        if (skillBindings.isEmpty()) {
            return List.of();
        }
        List<Long> skillIds = skillBindings.stream().map(AgentCapabilityBinding::getRefId).toList();
        List<Integer> versions = skillBindings.stream()
                .map(binding -> binding.getRefVersion() == null ? 0 : binding.getRefVersion())
                .toList();
        // 查询 2/4：skills + skill_versions 一次 JOIN 取回
        ctx.countQuery();
        List<Object[]> rows = skillVersionRepository.findBoundSkillVersions(skillIds, versions);

        java.util.Map<Long, Skill> skills = new java.util.LinkedHashMap<>();
        java.util.Map<String, SkillVersion> versionsBySkillAndVersion = new java.util.LinkedHashMap<>();
        for (Object[] row : rows) {
            Skill skill = (Skill) row[0];
            skills.putIfAbsent(skill.getId(), skill);
            SkillVersion skillVersion = (SkillVersion) row[1];
            if (skillVersion != null) {
                versionsBySkillAndVersion.putIfAbsent(
                        skill.getId() + ":" + skillVersion.getVersion(), skillVersion);
            }
        }

        List<SkillVersion> resolved = new ArrayList<>(skillBindings.size());
        for (AgentCapabilityBinding binding : skillBindings) {
            Long skillId = binding.getRefId();
            Integer refVersion = binding.getRefVersion();
            Skill skill = skills.get(skillId);
            if (skill == null) {
                // 🔴 租户表 discriminator 已加 tenant_id → 查不到即跨租户或不存在，两者都不泄露存在性
                ctx.violate("agentCapabilityBinding", binding.getId(), "refId",
                        ConfigViolationDTO.RULE_CROSS_TENANT,
                        "绑定引用的 Skill 不在当前租户内");
                continue;
            }
            if (!ctx.enter(TYPE_SKILL, skill.getId())) {
                continue;
            }
            try {
                ctx.checked++;
                if (!Skill.STATUS_ENABLED.equals(skill.getStatus())) {
                    // 🔴 停用的 Skill 不影响已绑定的历史版本注入（快照不可变，§7.5.2 硬约束 2），
                    //    因此只 warn 不阻断 —— 报成 violation 会让合法的历史 Agent 版本被判非法
                    ctx.warn(TYPE_SKILL, skill.getId(), "status",
                            ConfigViolationDTO.RULE_REF_UNAVAILABLE,
                            "Skill 已停用；已绑定的历史版本仍按快照注入（不影响本版本运行）");
                }
                SkillVersion skillVersion = versionsBySkillAndVersion
                        .get(skill.getId() + ":" + refVersion);
                if (skillVersion == null) {
                    ctx.violate("agentCapabilityBinding", binding.getId(), "refVersion",
                            ConfigViolationDTO.RULE_REF_NOT_FOUND,
                            "绑定引用的 Skill 版本不存在");
                    continue;
                }
                if (!SkillVersion.STATUS_PUBLISHED.equals(skillVersion.getStatus())) {
                    // 🔴 运行时会以 30060 拒绝注入（草稿/归档版本不可消费），必须阻断
                    ctx.violate(TYPE_SKILL_VERSION, skillVersion.getId(), "status",
                            ConfigViolationDTO.RULE_REF_UNAVAILABLE,
                            "绑定引用的 Skill 版本不是已发布状态");
                }
                // 🔴 第 2 层的叶子校验：不再向下递归（无第 3 层）
                if (ctx.enter(TYPE_SKILL_VERSION, skillVersion.getId())) {
                    try {
                        validateSkillVersion(ctx, skillVersion);
                    } finally {
                        ctx.exit(TYPE_SKILL_VERSION, skillVersion.getId());
                    }
                }
                resolved.add(skillVersion);
            } finally {
                ctx.exit(TYPE_SKILL, skill.getId());
            }
        }
        return resolved;
    }

    /**
     * 第 2 层 · {@code mcpTool} 绑定（🔴 {@code ref_version} 是发现批次号，<b>不参与解析</b>）。
     */
    private void validateMcpToolBindings(Ctx ctx, List<Long> mcpToolIds,
                                         List<String> functionNames) {
        if (mcpToolIds.isEmpty()) {
            return;
        }
        // 查询 3/4：mcp_tools + mcp_servers 一次 JOIN 取回
        ctx.countQuery();
        List<Object[]> rows = mcpToolRepository.findBoundToolsWithServer(mcpToolIds);
        java.util.Map<Long, Object[]> byToolId = new java.util.LinkedHashMap<>();
        for (Object[] row : rows) {
            byToolId.put(((McpTool) row[0]).getId(), row);
        }

        for (Long toolId : mcpToolIds) {
            Object[] row = byToolId.get(toolId);
            if (row == null) {
                ctx.violate("mcpTool", toolId, "refId", ConfigViolationDTO.RULE_CROSS_TENANT,
                        "绑定引用的 MCP 工具不在当前租户内");
                continue;
            }
            McpTool tool = (McpTool) row[0];
            McpServer server = (McpServer) row[1];
            if (server == null) {
                ctx.violate("mcpTool", tool.getId(), "mcpId",
                        ConfigViolationDTO.RULE_REF_NOT_FOUND, "工具所属 MCP 服务不存在");
                continue;
            }
            if (!ctx.enter("mcpTool", tool.getId())) {
                continue;
            }
            try {
                validateMcpTool(ctx, tool, server);
                if (!tool.grantedAndEnabled()) {
                    // 🔴 未授权/停用的工具被绑定 → 运行时以 30050 拒绝（fail-closed），提前阻断
                    ctx.violate("mcpTool", tool.getId(), "granted",
                            ConfigViolationDTO.RULE_REF_UNAVAILABLE,
                            "绑定引用的 MCP 工具未授权或已停用，运行时会被拒绝");
                }
                if (ctx.enter(TYPE_MCP, server.getId())) {
                    try {
                        // 🔴 includeReferences=false：MCP 自身校验不再向下展开其全部工具（避免第 3 层）
                        validateMcp(ctx, server, false);
                    } finally {
                        ctx.exit(TYPE_MCP, server.getId());
                    }
                }
                if (tool.grantedAndEnabled() && McpServer.STATUS_ENABLED.equals(server.getStatus())) {
                    functionNames.add(tool.getToolKey());
                }
            } finally {
                ctx.exit("mcpTool", tool.getId());
            }
        }
    }

    /**
     * 第 2 层 · {@code localTool} 绑定（🔴 {@code ref_id} 指向 {@code tenant_tool_grants.id}，
     * architecture.md §13.5.10 G1 裁决）。
     *
     * <p>🔴 运维纪律副作用：{@code tenant_tool_grants} 行被 {@code DELETE} 后重建会得到<b>新 id</b>，
     * 既有绑定悬挂 → 该工具不进清单（fail-closed）。本校验把悬挂绑定报成 {@code refNotFound}，
     * 让 DBA 立刻看到，而不是"工具莫名消失"。
     */
    private void validateLocalToolBindings(Ctx ctx, List<Long> localGrantIds,
                                           List<String> functionNames) {
        if (localGrantIds.isEmpty()) {
            return;
        }
        // 查询 4/4：tenant_tool_grants + local_tools 一次 JOIN 取回
        ctx.countQuery();
        List<Object[]> rows = tenantToolGrantRepository.findBoundGrantsWithLocalTool(localGrantIds);
        java.util.Map<Long, Object[]> byGrantId = new java.util.LinkedHashMap<>();
        for (Object[] row : rows) {
            byGrantId.put(((TenantToolGrant) row[0]).getId(), row);
        }

        int maxTimeout = businessConfig.requireInt(ConfigKeys.GROUP_TOOL,
                ConfigKeys.TOOL_MAX_TIMEOUT_SECONDS);
        for (Long grantId : localGrantIds) {
            Object[] row = byGrantId.get(grantId);
            if (row == null) {
                ctx.violate(TYPE_TOOL_GRANT, grantId, "refId",
                        ConfigViolationDTO.RULE_REF_NOT_FOUND,
                        "绑定引用的本地 Tool 授权不存在（授权行被删除重建会产生新 ID）");
                continue;
            }
            TenantToolGrant grant = (TenantToolGrant) row[0];
            LocalTool tool = (LocalTool) row[1];
            if (!ctx.enter(TYPE_TOOL_GRANT, grant.getId())) {
                continue;
            }
            try {
                ctx.checked++;
                if (!grant.grantedAndEnabled()) {
                    ctx.violate(TYPE_TOOL_GRANT, grant.getId(), "granted",
                            ConfigViolationDTO.RULE_REF_UNAVAILABLE,
                            "绑定引用的本地 Tool 未授权或已停用，运行时会被拒绝");
                }
                validateGrantConfig(ctx, grant);
                if (tool == null) {
                    ctx.violate(TYPE_TOOL_GRANT, grant.getId(), "toolKey",
                            ConfigViolationDTO.RULE_REF_NOT_FOUND, "授权指向的平台 Tool 不存在");
                    continue;
                }
                if (ctx.enter(TYPE_LOCAL_TOOL, tool.getId())) {
                    try {
                        validateLocalTool(ctx, tool);
                    } finally {
                        ctx.exit(TYPE_LOCAL_TOOL, tool.getId());
                    }
                }
                if (!LocalTool.STATUS_ENABLED.equals(tool.getStatus())) {
                    ctx.violate(TYPE_LOCAL_TOOL, tool.getId(), "status",
                            ConfigViolationDTO.RULE_REF_UNAVAILABLE,
                            "平台 Tool 未启用，运行时不会进入可调用清单");
                }
                Integer timeout = tool.getTimeoutSeconds();
                if (timeout != null && (timeout < 1 || timeout > maxTimeout)) {
                    ctx.violate(TYPE_LOCAL_TOOL, tool.getId(), "timeoutSeconds",
                            ConfigViolationDTO.RULE_RANGE,
                            "超时秒数必须在 1~" + maxTimeout + " 之间");
                }
                if (grant.grantedAndEnabled() && LocalTool.STATUS_ENABLED.equals(tool.getStatus())) {
                    functionNames.add(tool.getToolKey());
                }
            } finally {
                ctx.exit(TYPE_TOOL_GRANT, grant.getId());
            }
        }
    }

    /**
     * 🔴 <b>聚合层专属校验之一</b>：system 提示总长预算（api-spec §7.5.2 ① / §7.3.1 G10）。
     *
     * <p>🔴 <b>为什么只有这里能发现</b>：单个 Skill 已受 {@code skill.instruction_max_chars} 约束，
     * "多 Skill 叠加后总长无上限"才是真正的 fail-open 缺口 —— 单对象校验永远发现不了。
     * 若缺这一项，DBA 只能等线上第一个用户触发 {@code 30060}。
     *
     * <p>🔴 <b>与运行时同一实现</b>（{@link SystemPromptBudget}）+ <b>同一顺序</b>
     * （systemPrompt → 全部 instruction → 全部 outputConstraint）+ <b>变量替换后</b>长度，
     * 保证"校验入口结论 == 线上结论"。🔴 本方法<b>不发起任何新查询</b>：
     * 用的是第 2 层已取回的行 + 纯计算的变量替换。
     */
    private void validateSystemPromptBudget(Ctx ctx, AgentVersion version,
                                            List<AgentCapabilityBinding> skillBindings,
                                            List<SkillVersion> injectedVersions) {
        if (injectedVersions.isEmpty()) {
            return;
        }
        SystemPromptBudget budget = new SystemPromptBudget(businessConfig.requireInt(
                ConfigKeys.GROUP_CHAT, ConfigKeys.CHAT_SYSTEM_PROMPT_MAX_CHARS));
        List<String> instructions = new ArrayList<>(injectedVersions.size());
        List<String> constraints = new ArrayList<>(injectedVersions.size());
        SkillRuntimeContext runtimeContext = SkillRuntimeContext.ofTenant(version.getTenantId());
        for (int i = 0; i < injectedVersions.size(); i++) {
            SkillVersion skillVersion = injectedVersions.get(i);
            AgentCapabilityBinding binding = i < skillBindings.size() ? skillBindings.get(i) : null;
            java.util.Map<String, String> values = substitutionValues(skillVersion, binding,
                    runtimeContext);
            instructions.add(variableResolver.substitute(skillVersion.getInstruction(), values));
            constraints.add(variableResolver.substitute(
                    skillVersion.getOutputConstraint() == null
                            ? "" : skillVersion.getOutputConstraint(), values));
        }

        List<String> sections = new ArrayList<>();
        sections.add(version.getSystemPrompt());
        sections.addAll(instructions);
        sections.addAll(constraints);
        boolean first = true;
        for (String section : sections) {
            if (section == null || section.isBlank()) {
                continue;
            }
            if (!first && !budget.acceptSeparator()) {
                break;
            }
            first = false;
            if (!budget.accept(section)) {
                break;
            }
        }
        if (budget.exceeded()) {
            // 🔴 message 只回长度上限，禁回正文片段（systemPrompt / Skill 指令均为内部资产）
            ctx.violate(TYPE_AGENT_VERSION, version.getId(), "systemPrompt",
                    ConfigViolationDTO.RULE_SYSTEM_PROMPT_BUDGET_EXCEEDED,
                    "已超出系统提示长度上限（上限 " + budget.max() + " 字符）");
        }
    }

    /**
     * 变量取值（🔴 纯计算，不查库；内置 {@code locale}/{@code timezone} 在本入口留空 ——
     * 见方法内说明）。
     */
    private java.util.Map<String, String> substitutionValues(SkillVersion skillVersion,
                                                             AgentCapabilityBinding binding,
                                                             SkillRuntimeContext runtimeContext) {
        List<SkillVariableDecl> declarations = skillValidator.parseDeclarations(skillVersion);
        java.util.Map<String, String> bindingValues = binding == null
                ? java.util.Map.of()
                : skillValidator.parseBindingValues(binding.getAgentVersionId(),
                        binding.getVariableValues());
        // 🔴 为什么这里不补 locale/timezone（会多一次租户表查询）：它们的取值长度是**个位数**
        //    （如 zh-CN / Asia/Shanghai），对 10 万码点级预算的影响可忽略；
        //    而为一个预算估算白付一次 DB 往返、并突破 G10 的 4 次查询预算，代价不成比例。
        return variableResolver.resolveValues(declarations, bindingValues, runtimeContext);
    }

    /**
     * 🔴 <b>聚合层专属校验之二</b>：模型函数名长度与碰撞（api-spec §7.6.5 / §7.3.1 G10）。
     *
     * <p>🔴 <b>为什么只有这里能发现</b>：碰撞是<b>清单内两个 toolKey 之间</b>的关系，
     * 单个工具自身怎么查都查不出来。
     *
     * <p>🔴 <b>口径与运行时严格一致</b>：只对"会真正进入模型可见清单"的工具判定
     * （已授权 + 启用 + 所属服务启用；{@code tool_policy=disabled} 时清单为空故整体跳过）。
     * 若对未授权工具也报错，就会出现"校验入口判非法、线上却完全正常"的<b>假警报</b> ——
     * 假警报会训练 DBA 忽略校验结果，比不报更糟。
     */
    private void validateFunctionNames(Ctx ctx, AgentVersion version, List<String> toolKeys) {
        if (toolKeys.isEmpty() || AgentVersion.TOOL_POLICY_DISABLED.equals(version.getToolPolicy())) {
            return;
        }
        java.util.Map<String, String> seen = new java.util.LinkedHashMap<>();
        for (String toolKey : toolKeys) {
            String functionName = ToolFunctionNames.normalize(toolKey);
            if (ToolFunctionNames.tooLong(functionName)) {
                ctx.violate("mcpTool", null, "toolKey",
                        ConfigViolationDTO.RULE_FUNCTION_NAME_TOO_LONG,
                        "工具函数名超出长度上限（" + ToolFunctionNames.MAX_LENGTH + " 字符）");
                continue;
            }
            String previous = seen.putIfAbsent(functionName, toolKey);
            if (previous != null && !previous.equals(toolKey)) {
                // 🔴 只回"冲突"，不回显另一方的完整 toolKey（最小披露）
                ctx.violate("mcpTool", null, "toolKey",
                        ConfigViolationDTO.RULE_FUNCTION_NAME_COLLISION,
                        "工具函数名冲突，请调整 mcpKey 或工具名");
            }
        }
    }

    // ===================== 站点配置 =====================

    private void validateSiteConfig(Ctx ctx, SiteConfigVersion version) {
        ctx.checked++;
        try {
            SiteConfigContent content =
                    objectMapper.readValue(version.getContent(), SiteConfigContent.class);
            List<ViolationDTO> violations = siteConfigValidator.validate(content);
            for (ViolationDTO violation : violations) {
                ctx.violate(TYPE_SITE_CONFIG, version.getId(), violation.field(),
                        violation.rule(), violation.detail());
            }
        } catch (Exception e) {
            ctx.violate(TYPE_SITE_CONFIG, version.getId(), "content",
                    ConfigViolationDTO.RULE_FORMAT, "站点配置内容不是合法 JSON");
        }
    }

    // ===================== 通用工具 =====================

    /**
     * JSON Schema 校验（ADR-014：{@code com.networknt:json-schema-validator}，draft 2020-12）。
     *
     * <p>🔴 一期<b>不缓存</b>编译后的 Schema 对象：工具调用频率低，编译成本相对网络往返可忽略；
     * 且避免引入新的进程内共享缓存及其准入例外与失效语义（ADR-014 第 3 条）。
     *
     * @param requireObjectRoot 是否强制根类型为 {@code object}（本地 Tool 必须；MCP 上游宽松处理）
     */
    private void validateJsonSchema(Ctx ctx, String type, Long objectId, String field,
                                    String raw, boolean requireObjectRoot) {
        if (raw == null || raw.isBlank()) {
            if (requireObjectRoot) {
                ctx.violate(type, objectId, field, ConfigViolationDTO.RULE_REQUIRED,
                        "入参 Schema 不能为空");
            }
            return;
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(raw);
        } catch (Exception e) {
            ctx.violate(type, objectId, field, ConfigViolationDTO.RULE_FORMAT,
                    "入参 Schema 必须是合法 JSON");
            return;
        }
        if (!node.isObject()) {
            ctx.violate(type, objectId, field, ConfigViolationDTO.RULE_FORMAT,
                    "入参 Schema 根节点必须是对象");
            return;
        }
        JsonNode typeNode = node.get("type");
        if (requireObjectRoot && (typeNode == null || !"object".equals(typeNode.asText()))) {
            ctx.violate(type, objectId, field, ConfigViolationDTO.RULE_FORMAT,
                    "入参 Schema 的根类型必须是 object");
        }
        try {
            // 编译不通过说明 Schema 自身非法 → 构造工具清单阶段就应失败（ADR-014 第 4 条）
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(node);
        } catch (Exception e) {
            ctx.violate(type, objectId, field, ConfigViolationDTO.RULE_FORMAT,
                    "入参 Schema 无法编译（需符合 JSON Schema draft 2020-12）");
        }
    }

    private List<String> cidrList(String key) {
        return configService.getJson(ConfigKeys.GROUP_MCP, key,
                new TypeReference<List<String>>() {
                }, List.of());
    }

    // ===== 对象加载：不存在或跨租户一律 10004（🔴 不泄露存在性，AC-TEN-004） =====
    //
    // 🔴 实测教训（本轮发现，已回报 @架构师）：租户表<b>不能</b>用 findById 做租户内查找。
    //    findById 走主键直载（EntityManager.find），Hibernate 6 在该路径上不会追加
    //    tenant_id 条件，能读到其他租户的行；只有派生查询 / JPQL 才会被追加。
    //    因此统一改用 findOneById(...)（派生查询），ConfigValidateIT 的跨租户用例守护该行为。

    private Skill requireSkill(long id) {
        return skillRepository.findOneById(id).orElseThrow(BusinessException::notFound);
    }

    private SkillVersion requireSkillVersion(long id) {
        return skillVersionRepository.findOneById(id).orElseThrow(BusinessException::notFound);
    }

    private McpServer requireMcp(long id) {
        return mcpServerRepository.findOneById(id).orElseThrow(BusinessException::notFound);
    }

    private McpTool requireMcpTool(long id) {
        return mcpToolRepository.findOneById(id).orElseThrow(BusinessException::notFound);
    }

    private TenantToolGrant requireToolGrant(long id) {
        return tenantToolGrantRepository.findOneById(id).orElseThrow(BusinessException::notFound);
    }

    /**
     * 平台表对象（{@code local_tools}）：无租户维度，权限由调用方以"平台管理员"校验，
     * 因此这里用 {@code findById} 是<b>正确</b>的（不存在跨租户概念）。
     */
    private LocalTool requireLocalTool(long id) {
        return localToolRepository.findById(id).orElseThrow(BusinessException::notFound);
    }

    private Agent requireAgent(long id) {
        return agentRepository.findByIdAndDeletedAtIsNull(id).orElseThrow(BusinessException::notFound);
    }

    private AgentVersion requireAgentVersion(long id) {
        return agentVersionRepository.findOneById(id).orElseThrow(BusinessException::notFound);
    }

    private SiteConfigVersion requireSiteConfigVersion(long id) {
        return siteConfigVersionRepository.findOneById(id).orElseThrow(BusinessException::notFound);
    }

    private long parseId(String objectId) {
        return com.eyes.albedo.common.Ids.parse(objectId);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 变量声明（api-spec §7.5.1 的 {@code variables_schema} 元素）。 */
    record VariableDecl(String name, Boolean required, String description, String defaultValue) {
    }

    /**
     * 校验累加器。
     *
     * <p><b>🔴 {@code visited} / {@code path} 的分工（api-spec §7.3.1 G10「循环引用防护」）</b>：
     * <pre>
     * visited = 本次校验**已访问过**的对象集合 → 再次遇到则**跳过且不重复计入** checkedObjects
     *           （菱形引用：两个绑定指向同一个 Skill，只应算一个对象）
     * path    = **当前递归栈**上的对象集合 → 再次进入即**自引用/成环**
     *           → 🔴 30060 rule=circularReference（fail-closed）
     *
     * 🔴 为什么两者必须分开：只有 visited 会把"菱形引用"误判成环（把正常配置判非法）；
     *    只有 path 会让菱形引用被重复计数（checkedObjects 虚高，且同一违规报两遍）。
     * 🔴 为什么不靠"栈深度兜底"：栈溢出是 StackOverflowError（不是业务异常），
     *    会变成未分类 500 / 线程崩溃，且完全定位不到是哪条引用成环。
     * </pre>
     *
     * <p>🔴 {@code referenceQueries} 只服务于「递归批量查询次数 ≤4」这条纪律的<b>可断言性</b>，
     * 不进对外 DTO（{@link ConfigValidationReportDTO} 形状由契约固定）。
     */
    static final class Ctx {
        private final List<ConfigViolationDTO> violations = new ArrayList<>();
        private final List<ConfigViolationDTO> warnings = new ArrayList<>();
        private final Set<String> visited = new LinkedHashSet<>();
        private final Set<String> path = new LinkedHashSet<>();
        private int checked;
        /** 递归层发起的<b>批量</b>查询次数（🔴 G10 要求 ≤4，且禁止 N+1）。 */
        private int referenceQueries;

        void violate(String type, Long id, String field, String rule, String message) {
            violations.add(ConfigViolationDTO.of(type, id, field, rule, message));
        }

        void warn(String type, Long id, String field, String rule, String message) {
            warnings.add(ConfigViolationDTO.of(type, id, field, rule, message));
        }

        /**
         * 进入一个对象。
         *
         * @return {@code true} = 可以校验（首次访问）；{@code false} = 已访问过，跳过且不重复计数
         * @throws BusinessException 30060 {@code rule=circularReference}（该对象已在当前递归栈上）
         */
        boolean enter(String type, Long id) {
            String node = type + ':' + id;
            if (path.contains(node)) {
                // 🔴 自引用 / 环：fail-closed，立即失败并精确指出成环对象
                throw new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID, "配置引用链存在循环引用",
                        java.util.Map.of("objectType", type,
                                "objectId", String.valueOf(id),
                                "valid", false,
                                "checkedObjects", checked,
                                "violations", List.of(java.util.Map.of(
                                        "objectType", type,
                                        "objectId", String.valueOf(id),
                                        "field", "refId",
                                        "rule", ConfigViolationDTO.RULE_CIRCULAR_REFERENCE,
                                        "message", "配置引用链存在循环引用")),
                                "warnings", List.of()));
            }
            if (!visited.add(node)) {
                return false;
            }
            path.add(node);
            return true;
        }

        /** 退出一个对象（🔴 必须与 {@link #enter} 成对，否则菱形引用会被误判成环）。 */
        void exit(String type, Long id) {
            path.remove(type + ':' + id);
        }

        void countQuery() {
            referenceQueries++;
        }
    }

    /** 供 {@code scripts/validate-config.sh} 与集成测试打印的可读摘要（🔴 不含任何敏感值）。 */
    public String summarize(ConfigValidationReportDTO report) {
        StringBuilder sb = new StringBuilder();
        sb.append(report.objectType()).append('#').append(report.objectId())
                .append(report.valid() ? " 校验通过" : " 校验失败")
                .append("（已检查 ").append(report.checkedObjects()).append(" 个对象）");
        for (ConfigViolationDTO v : report.violations()) {
            sb.append(System.lineSeparator()).append("  [violation] ")
                    .append(v.objectType()).append('#').append(v.objectId())
                    .append(' ').append(v.field()).append(' ').append(v.rule())
                    .append(": ").append(v.message());
        }
        for (ConfigViolationDTO w : report.warnings()) {
            sb.append(System.lineSeparator()).append("  [warning]   ")
                    .append(w.objectType()).append('#').append(w.objectId())
                    .append(' ').append(w.field()).append(' ').append(w.rule())
                    .append(": ").append(w.message());
        }
        return sb.toString();
    }

    /** 保留给 M3 第二阶段：按 mcpTool ID 校验单个工具（当前仅内部引用链使用）。 */
    ConfigValidationReportDTO validateMcpToolById(long id) {
        Ctx ctx = new Ctx();
        McpTool tool = requireMcpTool(id);
        McpServer server = mcpServerRepository.findOneById(tool.getMcpId())
                .orElseThrow(BusinessException::notFound);
        validateMcpTool(ctx, tool, server);
        return new ConfigValidationReportDTO("mcpTool", String.valueOf(id),
                ctx.violations.isEmpty(), ctx.checked, List.copyOf(ctx.violations),
                List.copyOf(ctx.warnings));
    }

    /** 语言无关的小写工具（避免 Locale 相关的大小写陷阱）。 */
    static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
