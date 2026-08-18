package com.eyes.albedo.skill;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eyes.albedo.skill.dto.SkillRuntimeContext;
import com.eyes.albedo.skill.dto.SkillVariableDecl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * <code>{{variable}}</code> 声明解析与替换（api-spec §7.5.3，唯一实现）。
 *
 * <p><b>REQ-SKL-001 / REQ-SKL-002 · AC-SKL-001 / AC-SKL-002 / AC-CHAT-007</b>
 *
 * <p><b>规则（逐条对应 api-spec §7.5.3）</b>：
 * <ol>
 *   <li>占位符 <code>{{name}}</code>，{@code name} 匹配 {@link #VARIABLE_NAME_REGEX}；
 *       🔴 不匹配的 <code>{{…}}</code> <b>原样保留</b>（不解释、不报错），避免与 Markdown /
 *       代码块（如 <code>{{ 1 + 1 }}</code>、<code>{{}}</code>）冲突</li>
 *   <li>🔴 取值优先级（api-spec V1.1.2 §7.5.3 第 2 条，G4 裁决）：
 *       <b>⓿ 平台内置只读变量（{@link #RESERVED_VARIABLES}）构成独立命名空间，不参与优先级比较，
 *       且不可被绑定覆盖</b>；其余变量为 ① 绑定的 {@code variable_values[name]}
 *       → ② 声明的 {@code defaultValue}</li>
 *   <li>未声明即使用 → {@code 30060} {@code undeclaredVariable}（🔴 <b>进入模型之前</b>失败）</li>
 *   <li>{@code required=true} 但三级取值均为空 → {@code 30060} {@code missingVariableValue}</li>
 *   <li>{@code required=false} 且无值 → 替换为空串（确定性行为，不失败）</li>
 *   <li>声明但未使用 → 通过，仅 {@code unusedVariable} 警告（由校验入口给出）</li>
 *   <li>🔴 变量值一律按<b>纯文本</b>注入，禁止二次解析</li>
 * </ol>
 *
 * <p>🔴 <b>防注入的实现要点（第 7 条，最容易写错的地方）</b>：
 * <pre>
 * ❌ 错误写法：instruction.replace("{{" + name + "}}", value)  —— 循环替换
 *    危害：若变量 A 的值里含 "{{B}}"，下一轮循环会把它当占位符展开
 *          → 租户可通过变量取值"套娃"注入任意其它变量（甚至内置变量）的内容。
 * ✅ 本类写法：单次正则扫描 + {@link Matcher#appendReplacement}
 *          + {@link Matcher#quoteReplacement}（同时屏蔽 $1、\ 等替换语义），
 *    保证"扫描指针只前进不回退"，替换进去的文本<b>永远不会被再次扫描</b>。
 * </pre>
 *
 * <p>🔴 本类<b>无任何缓存</b>：一期 Skill 版本直读 MySQL（api-spec §7.1.2 末尾），
 * 变量替换是纯计算，缓存只会带来"改库不生效"。
 */
@Slf4j
@Component
public class SkillVariableResolver {

    /** 变量名规则（api-spec §7.5.3 第 1 条）。 */
    public static final String VARIABLE_NAME_REGEX = "[a-zA-Z][a-zA-Z0-9_]{0,63}";

    /**
     * 占位符正则。
     *
     * <p>🔴 只匹配"合法变量名"的占位符；其余 <code>{{…}}</code> 不参与匹配，因此天然被原样保留。
     */
    public static final Pattern VARIABLE_PLACEHOLDER =
            Pattern.compile("\\{\\{(" + VARIABLE_NAME_REGEX + ")}}");

    /** 变量名整体匹配（用于声明校验）。 */
    public static final Pattern VARIABLE_NAME = Pattern.compile("^" + VARIABLE_NAME_REGEX + "$");

    // ===== 平台内置只读变量（🔴 保留名：Skill 声明同名变量 → 30060） =====
    public static final String BUILTIN_TENANT_ID = "tenantId";
    public static final String BUILTIN_LOCALE = "locale";
    public static final String BUILTIN_TIMEZONE = "timezone";
    public static final String BUILTIN_NOW_ISO = "nowIso";

    /**
     * 平台内置只读变量白名单（api-spec V1.1.2 §7.5.3 第 2 条 ⓿，G4 已裁决）。
     *
     * <p>🔴 <b>保留名 + 独立命名空间</b>：
     * ① Skill 的 {@code variables_schema} 声明同名变量 → {@code 30060} {@code reservedVariable}；
     * ② 绑定的 {@code variable_values} 出现同名键 → 🔴 <b>忽略该键 + WARN，绝不覆盖</b>（不报错）。
     */
    public static final Set<String> RESERVED_VARIABLES = Set.of(
            BUILTIN_TENANT_ID, BUILTIN_LOCALE, BUILTIN_TIMEZONE, BUILTIN_NOW_ISO);

    /** {@code nowIso} 的格式：ISO-8601 UTC，毫秒精度（与 api-spec §1.1 时间格式一致）。 */
    private static final DateTimeFormatter NOW_ISO_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(java.time.ZoneOffset.UTC);

    /**
     * 提取正文中<b>被识别为占位符</b>的变量名（保持出现顺序）。
     *
     * <p>不匹配变量名规则的 <code>{{…}}</code> 不会出现在结果中 —— 它们是普通文本。
     */
    public Set<String> usedVariables(String instruction) {
        Set<String> used = new LinkedHashSet<>();
        if (instruction == null || instruction.isEmpty()) {
            return used;
        }
        Matcher matcher = VARIABLE_PLACEHOLDER.matcher(instruction);
        while (matcher.find()) {
            used.add(matcher.group(1));
        }
        return used;
    }

    /**
     * 计算每个变量的最终取值（🔴 <b>不做</b>替换，只做取值与缺失判定）。
     *
     * <p>返回的 Map 中 value 为 {@code null} 表示"三级取值均无"——
     * 调用方按 {@code required} 决定是 {@code 30060} 还是替换为空串。
     *
     * @param declarations  已声明变量（顺序无关）
     * @param bindingValues 绑定的 {@code variable_values}（可为空）
     * @param context       内置变量取值来源
     */
    public Map<String, String> resolveValues(List<SkillVariableDecl> declarations,
                                             Map<String, String> bindingValues,
                                             SkillRuntimeContext context) {
        Map<String, String> resolved = new LinkedHashMap<>();
        Map<String, String> builtins = builtinValues(context);
        Map<String, String> bound = bindingValues == null ? Map.of() : bindingValues;

        for (SkillVariableDecl decl : declarations) {
            if (decl == null || decl.name() == null) {
                continue;
            }
            resolved.put(decl.name(), firstNonBlank(
                    bound.get(decl.name()),          // ① 绑定取值
                    decl.defaultValue()));           // ② 声明默认值
        }
        // ⓿ 内置只读变量：独立命名空间，🔴 恒由平台注入且**不可被绑定覆盖**
        for (Map.Entry<String, String> builtin : builtins.entrySet()) {
            String overridden = bound.get(builtin.getKey());
            if (overridden != null && !overridden.isBlank()) {
                // 🔴 已裁决（api-spec V1.1.2 §7.5.3 G4）：同名键**一律忽略 + WARN，不报错**。
                //    为什么不能覆盖：tenantId 是租户身份语义，允许写库者改掉它
                //    等于"提示词层面的身份伪造"，与 §1.1「客户端传入 tenantId 一律忽略」（EX-003）
                //    是同一条防线；locale/timezone/nowIso 被覆盖会产出确定性错误的时间与语言语义。
                //    为什么不报错（fail-safe）：一个多余的 KV 不应让整条生成链路失败。
                log.warn("忽略绑定中与平台内置只读变量同名的键：name={}（内置变量不可被覆盖，api-spec §7.5.3 ⓿）",
                        builtin.getKey());
            }
            resolved.put(builtin.getKey(), builtin.getValue());
        }
        return resolved;
    }

    /**
     * 执行替换（🔴 <b>单次扫描</b>，值不会被二次解析）。
     *
     * <p>未出现在 {@code values} 中的占位符<b>原样保留</b>：本方法不做合法性判定，
     * "未声明变量"与"缺必填值"由 {@code SkillValidator} 在替换<b>之前</b>判定并抛 {@code 30060}
     * （api-spec §7.5.3 第 3/4 条要求"在进入模型之前失败"）。
     *
     * @param template 指令正文或输出约束
     * @param values   变量名 → 取值；{@code null} 值按空串注入（第 5 条）
     */
    public String substitute(String template, Map<String, String> values) {
        if (template == null || template.isEmpty()) {
            return template == null ? "" : template;
        }
        Map<String, String> safeValues = values == null ? Map.of() : values;
        Matcher matcher = VARIABLE_PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder(template.length());
        while (matcher.find()) {
            String name = matcher.group(1);
            if (!safeValues.containsKey(name)) {
                // 未提供取值（理论上已被 SkillValidator 拦下）：原样保留，绝不注入 "null"
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String value = safeValues.get(name);
            // 🔴 quoteReplacement：屏蔽 $1 / \ 的替换语义，值只当字面量
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * 内置只读变量取值。
     */
    public Map<String, String> builtinValues(SkillRuntimeContext context) {
        SkillRuntimeContext ctx = context == null
                ? SkillRuntimeContext.ofTenant("") : context;
        Map<String, String> builtins = new LinkedHashMap<>();
        builtins.put(BUILTIN_TENANT_ID, ctx.tenantId());
        builtins.put(BUILTIN_LOCALE, ctx.locale());
        builtins.put(BUILTIN_TIMEZONE, ctx.timezone());
        builtins.put(BUILTIN_NOW_ISO, formatNow(ctx.now()));
        return builtins;
    }

    /**
     * 正文是否引用了需要租户主数据的内置变量（{@code locale} / {@code timezone}）。
     *
     * <p>用途：调用方据此决定"是否值得为内置变量多查一次租户表"——
     * 绝大多数 Skill 不会用到，热路径因此可省掉一次 DB 往返（性能红线 §14.2）。
     */
    public boolean needsTenantProfile(String... templates) {
        if (templates == null) {
            return false;
        }
        for (String template : templates) {
            Set<String> used = usedVariables(template);
            if (used.contains(BUILTIN_LOCALE) || used.contains(BUILTIN_TIMEZONE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 声明中重复出现的变量名（DBA 写库无唯一约束，重复声明会让 {@code required} 语义不确定）。
     */
    public List<String> duplicatedNames(List<SkillVariableDecl> declarations) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> duplicated = new ArrayList<>();
        for (SkillVariableDecl decl : declarations) {
            if (decl == null || decl.name() == null) {
                continue;
            }
            if (!seen.add(decl.name())) {
                duplicated.add(decl.name());
            }
        }
        return duplicated;
    }

    private String formatNow(Instant now) {
        return NOW_ISO_FORMAT.format(now);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }
}
