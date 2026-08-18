package com.eyes.albedo.tool.handler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.eyes.albedo.agent.entity.AgentVersion;
import com.eyes.albedo.agent.repository.AgentVersionRepository;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.skill.SkillInjectionService;
import com.eyes.albedo.skill.dto.SkillRuntimeContext;
import com.eyes.albedo.skill.dto.SkillScriptResult;
import com.eyes.albedo.tool.LocalToolHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 内置本地 Tool：Skill 脚本执行（🔴 「按 skill 版本快照执行脚本」改造新增，
 * ADR-015 由「仅内置三个」修订为「仅内置四个」，见 architecture.md ADR-015 补注）。
 *
 * <p><b>背景</b>：{@code skill_load} 只把 Skill 正文/资源当<b>文本</b>回灌模型，模型"看得到"
 * SKILL.md 里引用的脚本却"跑不起来"。本工具补上执行入口，但<b>刻意收窄</b>到"只能执行
 * 平台/租户预置、随 skill 版本快照锁定的脚本"，与"执行模型/用户运行时传入的任意代码"
 * 是完全不同的威胁模型：
 * <ul>
 *   <li>入参只是一个<b>引用</b>（{@code skillKey + resourcePath}），代码正文永远由后端从
 *       {@code skill_resources} 表按<b>该 Agent 版本已绑定的精确 skill 版本</b>取出
 *       （复用 {@code SkillInjectionService.loadExecutableScript} 与 {@code skill_load}
 *       同源的绑定解析，见该方法类注释）</li>
 *   <li>即便模型伪造 {@code resourcePath}，后端也只会执行 DB 里真实存在且显式标记
 *       {@code executable=1} 的资源行；未标记的资源（绝大多数）永远不可执行</li>
 *   <li>语言仅限白名单解释器（{@link #INTERPRETERS}，当前 python3 / bash），
 *       非白名单 {@code content_type} 一律拒绝，不做通用"任意解释器"扩展</li>
 * </ul>
 *
 * <p>🔴 <b>与 {@code datetime_now}/{@code calculator}/{@code skill_load} 的关键差异
 * （已评审并接受，风险等级从 low 上调到 high）</b>：
 * <ul>
 *   <li>本工具<b>有真实副作用</b>（子进程可读写自己的临时工作目录、若未配置沙箱还可能触网）——
 *       因此登记 {@code risk_level=high}，{@link com.eyes.albedo.tool.ToolRiskPolicy}
 *       会强制<b>逐次用户确认</b>，任何 {@code tool_policy} 都不能降级</li>
 *   <li>登记 {@code idempotent=0}：脚本执行结果未知时禁止自动重试（{@code 30056}），
 *       与"发起退款"类工具同一纪律，而不是 {@code calculator} 的纯函数纪律</li>
 *   <li>🔴 <b>自我限界的"不阻塞"纪律</b>：{@link com.eyes.albedo.tool.LocalToolExecutor}
 *       的超时判定是"计时后如实上报"，<b>不会强制打断</b>实现体（ADR-008 第 8 条补注）。
 *       为了不让一次失控脚本永久占住 {@code aiStreamExecutor} 线程，本类<b>自行</b>在
 *       {@code execTimeoutSeconds}（{@code local_tools.input_schema} 的 {@code x-limits}）
 *       到点后 {@code Process#destroyForcibly()} 强杀子进程 —— 子进程本身是 OS 进程、
 *       不是 JVM 线程，起停子进程不违反"不新增线程池"（ADR-008 第 8 条）；
 *       读取输出走当前线程内的轮询排空（{@link #drain}），<b>不新建 {@code Thread}</b></li>
 *   <li>⚠️ <b>沙箱是可选的运维配置，不是代码强制</b>：{@code tenant_tool_grants.config} 可选
 *       携带 {@code sandboxCommand}（如 Docker 隔离命令数组），未配置时子进程<b>裸跑</b>
 *       在应用宿主（仅隔离在独立临时工作目录，不限网络/资源），只建议对<b>受信任来源</b>
 *       （平台/租户预置的 skill 脚本，而非任意代码）使用；生产环境务必配置容器化沙箱</li>
 * </ul>
 */
@Slf4j
@Component
public class SkillExecHandler implements LocalToolHandler {

    /** 🔴 必须与 {@code local_tools.tool_key} 完全一致。 */
    public static final String TOOL_KEY = "skill_exec";

    private static final String ARG_SKILL_KEY = "skillKey";
    private static final String ARG_RESOURCE_PATH = "resourcePath";
    private static final String ARG_ARGS = "args";

    private static final String FIELD_SKILL_KEY = "skillKey";
    private static final String FIELD_RESOURCE_PATH = "resourcePath";
    private static final String FIELD_EXIT_CODE = "exitCode";
    private static final String FIELD_OUTPUT = "output";
    private static final String FIELD_TIMED_OUT = "timedOut";
    private static final String FIELD_OUTPUT_TRUNCATED = "outputTruncated";
    private static final String FIELD_ERROR = "error";
    private static final String FIELD_MESSAGE = "message";

    /** {@code input_schema} 中的注解式限额（🔴 阈值唯一来源，local_tools 缺失即 30060，不在 Java 兜底）。 */
    private static final String SCHEMA_LIMITS = "x-limits";
    private static final String LIMIT_EXEC_TIMEOUT_SECONDS = "execTimeoutSeconds";
    private static final String LIMIT_MAX_OUTPUT_BYTES = "maxOutputBytes";
    private static final String LIMIT_MAX_ARGS = "maxArgs";
    private static final String LIMIT_MAX_ARG_LENGTH = "maxArgLength";

    /** {@code tenant_tool_grants.config} 中可选携带的沙箱命令数组；{@code {workdir}} 占位符会被替换为临时工作目录。 */
    private static final String CONFIG_SANDBOX_COMMAND = "sandboxCommand";
    private static final String WORKDIR_PLACEHOLDER = "{workdir}";

    /** 🔴 语言白名单：{@code skill_resources.content_type} → 解释器命令；非白名单一律拒绝执行。 */
    private static final Map<String, String[]> INTERPRETERS = Map.of(
            "python", new String[] {"python3"},
            "shell", new String[] {"bash"});

    private static final long POLL_INTERVAL_MILLIS = 20L;

    private final ObjectMapper objectMapper;
    private final AgentVersionRepository agentVersionRepository;
    private final SkillInjectionService skillInjectionService;

    public SkillExecHandler(ObjectMapper objectMapper,
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
        Limits limits = Limits.from(invocation.inputSchemaJson(), objectMapper);
        ExecArgs args = readArgs(invocation.argumentsJson(), limits);

        AgentVersion agentVersion = agentVersionRepository.findOneById(invocation.agentVersionId())
                .orElseThrow(() -> {
                    // 🔴 不应该发生（本次生成本身就是靠这个 agentVersion 构造出清单/工具的）
                    log.error("skill_exec 找不到本次生成绑定的 Agent 版本：agentVersionId={}",
                            invocation.agentVersionId());
                    return new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED, "工具执行失败");
                });

        SkillRuntimeContext context = SkillRuntimeContext.ofTenant(invocation.tenantId());
        Optional<SkillScriptResult> script = skillInjectionService
                .loadExecutableScript(agentVersion, args.skillKey(), args.resourcePath(), context);

        Map<String, Object> body = new LinkedHashMap<>();
        if (script.isEmpty()) {
            // 🔴 不是异常：清单外/不存在/未标记可执行的资源都走这条正常业务分支
            body.put(FIELD_ERROR, "skill_script_not_found");
            body.put(FIELD_MESSAGE, "该 skillKey/resourcePath 未绑定可执行脚本资源，"
                    + "请勿凭空猜测执行结果，可基于其他信息或直接作答");
        } else {
            String[] interpreter = INTERPRETERS.get(script.get().language());
            if (interpreter == null) {
                body.put(FIELD_ERROR, "unsupported_language");
                body.put(FIELD_MESSAGE, "该资源的语言不在受支持的执行白名单内（当前仅支持 python/shell）");
            } else {
                ExecResult result = runScript(interpreter, script.get(), args.args(),
                        invocation.configJson(), limits);
                body.put(FIELD_SKILL_KEY, script.get().skillKey());
                body.put(FIELD_RESOURCE_PATH, script.get().resourcePath());
                body.put(FIELD_EXIT_CODE, result.exitCode());
                body.put(FIELD_OUTPUT, result.output());
                if (result.timedOut()) {
                    body.put(FIELD_TIMED_OUT, true);
                }
                if (result.outputTruncated()) {
                    body.put(FIELD_OUTPUT_TRUNCATED, true);
                }
            }
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.error("skill_exec 结果序列化失败", e);
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED, "工具执行失败");
        }
    }

    // ---------------------------------------------------------------------
    // 入参解析
    // ---------------------------------------------------------------------

    private record ExecArgs(String skillKey, String resourcePath, List<String> args) {
    }

    private ExecArgs readArgs(String argumentsJson, Limits limits) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            throw argsInvalid("缺少 skillKey 或 resourcePath 参数");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(argumentsJson);
        } catch (Exception e) {
            throw argsInvalid("入参不是合法 JSON 对象");
        }
        if (!root.isObject()) {
            throw argsInvalid("入参不是合法 JSON 对象");
        }
        String skillKey = textOrBlank(root.get(ARG_SKILL_KEY));
        String resourcePath = textOrBlank(root.get(ARG_RESOURCE_PATH));
        if (skillKey.isBlank() || resourcePath.isBlank()) {
            throw argsInvalid("缺少 skillKey 或 resourcePath 参数");
        }
        List<String> args = new ArrayList<>();
        JsonNode argsNode = root.get(ARG_ARGS);
        if (argsNode != null && !argsNode.isNull()) {
            if (!argsNode.isArray()) {
                throw argsInvalid("args 必须是字符串数组");
            }
            if (argsNode.size() > limits.maxArgs()) {
                throw argsInvalid("args 数组元素个数超出限制");
            }
            for (JsonNode item : argsNode) {
                if (!item.isTextual() || item.asText().length() > limits.maxArgLength()) {
                    throw argsInvalid("args 数组元素必须是不超长的字符串");
                }
                args.add(item.asText());
            }
        }
        return new ExecArgs(skillKey.trim(), resourcePath.trim(), args);
    }

    private String textOrBlank(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText("");
    }

    private static BusinessException argsInvalid(String message) {
        return new BusinessException(ErrorCode.TOOL_ARGS_INVALID, message);
    }

    // ---------------------------------------------------------------------
    // 限额（🔴 全部来自 local_tools.input_schema 的 x-limits，缺失即 30060）
    // ---------------------------------------------------------------------

    private record Limits(int execTimeoutSeconds, int maxOutputBytes, int maxArgs, int maxArgLength) {

        static Limits from(String inputSchemaJson, ObjectMapper mapper) {
            JsonNode limits = readLimitsNode(inputSchemaJson, mapper);
            return new Limits(
                    requirePositive(limits, LIMIT_EXEC_TIMEOUT_SECONDS),
                    requirePositive(limits, LIMIT_MAX_OUTPUT_BYTES),
                    requirePositive(limits, LIMIT_MAX_ARGS),
                    requirePositive(limits, LIMIT_MAX_ARG_LENGTH));
        }

        private static JsonNode readLimitsNode(String inputSchemaJson, ObjectMapper mapper) {
            if (inputSchemaJson == null || inputSchemaJson.isBlank()) {
                throw configInvalid("skill_exec 的 input_schema 缺失");
            }
            try {
                return mapper.readTree(inputSchemaJson).path(SCHEMA_LIMITS);
            } catch (Exception e) {
                throw configInvalid("skill_exec 的 input_schema 不是合法 JSON");
            }
        }

        private static int requirePositive(JsonNode limits, String field) {
            JsonNode node = limits.path(field);
            if (node == null || !node.isInt() || node.asInt() <= 0) {
                throw configInvalid("skill_exec 的 input_schema.x-limits." + field + " 必须是正整数");
            }
            return node.asInt();
        }

        private static BusinessException configInvalid(String message) {
            return new BusinessException(ErrorCode.RUNTIME_CONFIG_INVALID, message);
        }
    }

    // ---------------------------------------------------------------------
    // 子进程执行（🔴 平台/租户预置脚本快照，非模型自由代码；当前线程内自我限界，不新增线程池）
    // ---------------------------------------------------------------------

    private record ExecResult(int exitCode, String output, boolean timedOut, boolean outputTruncated) {
    }

    private ExecResult runScript(String[] interpreterCommand, SkillScriptResult script, List<String> args,
                                 String configJson, Limits limits) {
        Path workDir = null;
        Process process = null;
        try {
            workDir = Files.createTempDirectory("skill_exec_");
            Path scriptFile = workDir.resolve("script" + scriptExtension(script.language()));
            Files.writeString(scriptFile, script.content(), StandardCharsets.UTF_8);

            List<String> command = buildCommand(interpreterCommand, scriptFile, args, configJson, workDir);
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            // 🔴 最小化继承环境：清空后只保留 PATH，避免把平台进程的环境变量/凭据泄露给脚本
            Map<String, String> env = pb.environment();
            String path = env.get("PATH");
            env.clear();
            if (path != null) {
                env.put("PATH", path);
            }

            process = pb.start();
            return drain(process, limits);
        } catch (IOException e) {
            log.error("skill_exec 启动子进程失败：skillKey={} resourcePath={}",
                    script.skillKey(), script.resourcePath(), e);
            throw new BusinessException(ErrorCode.TOOL_EXECUTION_FAILED, "工具执行失败");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            cleanup(workDir);
        }
    }

    private List<String> buildCommand(String[] interpreterCommand, Path scriptFile, List<String> args,
                                      String configJson, Path workDir) {
        List<String> sandboxPrefix = readSandboxCommand(configJson, workDir);
        if (sandboxPrefix.isEmpty()) {
            log.warn("skill_exec 未配置 sandboxCommand（tenant_tool_grants.config），"
                    + "子进程未做容器隔离，仅建议对受信任脚本 / 本地开发环境使用");
        }
        List<String> command = new ArrayList<>(sandboxPrefix);
        command.addAll(Arrays.asList(interpreterCommand));
        command.add(scriptFile.toString());
        command.addAll(args);
        return command;
    }

    private List<String> readSandboxCommand(String configJson, Path workDir) {
        if (configJson == null || configJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(configJson).path(CONFIG_SANDBOX_COMMAND);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            String workDirAbsolute = workDir.toAbsolutePath().toString();
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    result.add(item.asText().replace(WORKDIR_PLACEHOLDER, workDirAbsolute));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("skill_exec 解析 sandboxCommand 配置失败，忽略并按未隔离方式执行", e);
            return List.of();
        }
    }

    private String scriptExtension(String language) {
        return "shell".equals(language) ? ".sh" : "python".equals(language) ? ".py" : ".txt";
    }

    /**
     * 排空子进程输出并等待其结束（🔴 当前线程内轮询，不新建 {@code Thread}）。
     *
     * <p>合并 stdout/stderr（{@code redirectErrorStream(true)}），用 {@code available()} 轮询代替
     * 阻塞 {@code read()}，避免管道缓冲区打满导致的经典死锁；到 {@code execTimeoutSeconds}
     * 硬边界即 {@code destroyForcibly()} 强杀。
     *
     * <p>🔴 <b>强杀分支不做"收尾读取"</b>（实测发现并修正的一个坑）：{@code destroyForcibly()}
     * 之后管道可能被 JDK 立即关闭，此时再 {@code read()} 会抛 {@code IOException: Stream closed}
     * 而不是干净地返回 EOF——继续读只会白白触发一次可预期的异常，且一旦真的抛出，
     * 外层 catch 会把 {@code timedOut} 误报为 {@code false}（"异常 = 没超时"的错误蕴含）。
     * 因此强杀路径直接用轮询阶段已收集到的字节收尾，不再尝试读流；只有<b>自然退出</b>路径
     * 才做收尾读取（此时管道按正常协议关闭，读到 EOF 是可预期行为）。
     */
    private ExecResult drain(Process process, Limits limits) {
        InputStream input = process.getInputStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        boolean outputTruncated = false;
        boolean exited = false;
        long deadlineNanos = System.nanoTime() + limits.execTimeoutSeconds() * 1_000_000_000L;
        try {
            while (System.nanoTime() < deadlineNanos) {
                outputTruncated |= pump(input, buffer, chunk, limits.maxOutputBytes());
                if (!process.isAlive()) {
                    exited = true;
                    break;
                }
                Thread.sleep(POLL_INTERVAL_MILLIS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new ExecResult(-1, buffer.toString(StandardCharsets.UTF_8), true, outputTruncated);
        } catch (IOException e) {
            log.warn("skill_exec 轮询读取子进程输出时异常，按已读部分返回", e);
            process.destroyForcibly();
            return new ExecResult(-1, buffer.toString(StandardCharsets.UTF_8), false, outputTruncated);
        }

        if (!exited) {
            // 🔴 到点强杀：不再尝试读流（见方法注释），直接以轮询阶段已收集到的字节收尾
            process.destroyForcibly();
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ExecResult(-1, buffer.toString(StandardCharsets.UTF_8), true, outputTruncated);
        }

        // 自然退出：管道按正常协议关闭，收尾读取剩余字节是安全的
        try {
            outputTruncated |= pumpToEof(input, buffer, chunk, limits.maxOutputBytes());
        } catch (IOException e) {
            log.warn("skill_exec 收尾读取子进程输出时异常，按已读部分返回", e);
        }
        return new ExecResult(safeExitCode(process), buffer.toString(StandardCharsets.UTF_8),
                false, outputTruncated);
    }

    private boolean pump(InputStream input, ByteArrayOutputStream buffer, byte[] chunk, int maxBytes)
            throws IOException {
        int available = input.available();
        if (available <= 0 || buffer.size() >= maxBytes) {
            return false;
        }
        int n = input.read(chunk, 0, Math.min(chunk.length, available));
        return n > 0 && writeBounded(buffer, chunk, n, maxBytes);
    }

    private boolean pumpToEof(InputStream input, ByteArrayOutputStream buffer, byte[] chunk, int maxBytes)
            throws IOException {
        boolean truncated = false;
        int n;
        while (buffer.size() < maxBytes && (n = input.read(chunk)) > 0) {
            truncated |= writeBounded(buffer, chunk, n, maxBytes);
        }
        return truncated;
    }

    private boolean writeBounded(ByteArrayOutputStream buffer, byte[] chunk, int n, int maxBytes) {
        int room = maxBytes - buffer.size();
        if (n > room) {
            buffer.write(chunk, 0, room);
            return true;
        }
        buffer.write(chunk, 0, n);
        return false;
    }

    private int safeExitCode(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    private void cleanup(Path workDir) {
        if (workDir == null) {
            return;
        }
        try (var stream = Files.walk(workDir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 清理失败不影响本次工具调用结果，留给操作系统临时目录回收
                }
            });
        } catch (IOException e) {
            log.warn("skill_exec 清理临时工作目录失败：{}", workDir, e);
        }
    }
}
