package com.eyes.albedo.testsupport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.eyes.albedo.chat.ai.AiChatClient;
import com.eyes.albedo.chat.ai.AiChatRequest;
import com.eyes.albedo.chat.ai.AiStreamOutcome;

import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.OngoingStubbing;

/**
 * 🔴 {@link AiChatClient#stream} 的<b>唯一</b>桩位与参数取用入口（仅 {@code src/test}）。
 *
 * <p><b>为什么必须集中</b>：本类的存在源于一次真实回归 —— 「思考过程」特性给 {@code stream}
 * 新增 {@code onReasoning} 参数时曾留下一个 5 参便捷重载，
 * 生产代码（{@code ChatStreamRunner}）调 6 参版，而 3 个 IT 的 {@code @MockBean}
 * 只桩了 5 参版：Mockito 对未桩住的重载返回 {@code null} →
 * {@code outcome.cancelled()} NPE → 32 个 IT 集体表现为 {@code [meta, error, done]} + 50003。
 * 那次事故的结构性成因是<b>同一个桩位模式在 3 个文件里复制了 11 份</b>，
 * 加参数时改漏任何一份都不会有编译错误。
 *
 * <p><b>两道防线</b>：
 * <ol>
 *   <li><b>编译期</b>：{@code AiChatClient} 已只保留唯一的 {@code stream} 签名
 *       （便捷重载已删除），因此参数个数一变，{@link #whenStream} 立刻编译失败 ——
 *       且因为桩位只有这一处，改一处即全体对齐。</li>
 *   <li><b>运行期</b>：{@code getArgument(int)} 的下标是编译器看不见的。
 *       {@code AiStreamStubDisciplineTest} 用反射比对本类的下标常量与生产签名的
 *       参数名 / 泛型类型，任何重排或新增参数都会被立即断言出来。</li>
 * </ol>
 *
 * <p>🔴 任何测试都<b>不得</b>再直接写 {@code Mockito.when(aiChatClient.stream(...))}
 * 或 {@code invocation.getArgument(<数字>)}，否则第一道防线的"改一处即全体对齐"就失效了。
 */
public final class AiStreamStub {

    /** {@code stream} 的参数个数（与生产签名同源，由纪律测试反射校验）。 */
    public static final int ARG_COUNT = 6;

    /** 下标 → 参数名映射（🔴 顺序即 {@code stream} 的形参顺序，纪律测试逐项比对）。 */
    public static final String[] ARG_NAMES = {
            "request", "onDelta", "onReasoning", "onOpen", "cancelCheck", "checkIntervalChunks"};

    private static final int ARG_REQUEST = 0;
    private static final int ARG_ON_DELTA = 1;
    private static final int ARG_ON_REASONING = 2;
    private static final int ARG_ON_OPEN = 3;
    private static final int ARG_CANCEL_CHECK = 4;

    private AiStreamStub() {
    }

    /**
     * 桩住 {@code stream}（🔴 全仓唯一的 {@code when(...stream(...))} 调用点）。
     *
     * <p>用法：{@code AiStreamStub.whenStream(aiChatClient).thenAnswer(invocation -> ...)}
     */
    public static OngoingStubbing<AiStreamOutcome> whenStream(AiChatClient client) {
        return Mockito.when(client.stream(any(AiChatRequest.class), any(), any(), any(), any(),
                anyInt()));
    }

    /** 本次调用的上游请求（用于断言 tools 下发形态与工具结果回灌）。 */
    public static AiChatRequest request(InvocationOnMock invocation) {
        return invocation.getArgument(ARG_REQUEST);
    }

    /** 正文分片回调。 */
    public static Consumer<String> onDelta(InvocationOnMock invocation) {
        return invocation.getArgument(ARG_ON_DELTA);
    }

    /** 思考过程分片回调（🔴 与正文严格分流，不得用它下发正文）。 */
    public static Consumer<String> onReasoning(InvocationOnMock invocation) {
        return invocation.getArgument(ARG_ON_REASONING);
    }

    /** 上游流建立回调（交出可关闭句柄供「停止生成」关流）。 */
    public static Consumer<Closeable> onOpen(InvocationOnMock invocation) {
        return invocation.getArgument(ARG_ON_OPEN);
    }

    /** 取消检查。 */
    public static BooleanSupplier cancelCheck(InvocationOnMock invocation) {
        return invocation.getArgument(ARG_CANCEL_CHECK);
    }

    /**
     * 模拟"上游流已建立"：交出一个空流句柄。
     *
     * <p>生产代码据此注册取消句柄，缺这一步「停止生成」链路就无从触发。
     */
    public static void openStream(InvocationOnMock invocation) {
        onOpen(invocation).accept(new ByteArrayInputStream(new byte[0]));
    }
}
