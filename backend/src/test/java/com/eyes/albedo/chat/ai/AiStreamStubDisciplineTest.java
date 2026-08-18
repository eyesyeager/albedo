package com.eyes.albedo.chat.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;

import com.eyes.albedo.testsupport.AiStreamStub;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 🔴 <b>「桩位漂移」防回归守护</b>（对应一次真实回归：32 个 IT 静默红）。
 *
 * <p><b>事故回放</b>：「思考过程」特性给 {@code AiChatClient.stream} 增加 {@code onReasoning}
 * 参数时，保留了一个 5 参便捷重载。生产代码（{@code ChatStreamRunner}）调 6 参版，
 * 而 {@code ChatStreamIT} / {@code ToolOrchestrationIT} / {@code ToolConfirmIT} 的
 * {@code @MockBean} 只桩了 5 参版 —— Mockito 对未桩住的重载返回 {@code null}，
 * {@code outcome.cancelled()} 随即 NPE，全部退化成 {@code [meta, error, done]} + 50003。
 * 因为两个重载都能编译通过，问题在编译期毫无提示，只在 {@code mvn verify} 才暴露。
 *
 * <p><b>本测试守护两件事</b>（缺一不可）：
 * <ol>
 *   <li>{@code stream} <b>只有一个签名</b>。这是"编译器能替我们兜底"的前提：
 *       唯一签名下，形参个数一变，{@link AiStreamStub#whenStream} 立即编译失败；
 *       一旦有人再加便捷重载，编译器就会重新哑掉，本断言负责在那一刻叫停。</li>
 *   <li>{@link AiStreamStub} 的<b>参数下标</b>与生产签名一致。
 *       {@code invocation.getArgument(int)} 的下标是编译器完全看不见的，
 *       参数重排（例如把 {@code onReasoning} 挪到 {@code onOpen} 之后）不会有任何编译错误，
 *       却会让桩把正文回调当成建流回调用。这里用参数名 + 泛型类型逐位比对来拦住它。</li>
 * </ol>
 *
 * <p>为什么用参数名比对：{@code onDelta} 与 {@code onReasoning} 的擦除类型都是
 * {@code Consumer<String>}，只比类型无法发现二者互换。{@code maven-compiler-plugin}
 * 已开启 {@code <parameters>true</parameters>}，参数名在运行期可读。
 */
class AiStreamStubDisciplineTest {

    /**
     * 生产签名的期望形态：下标 → 泛型类型名。
     *
     * <p>🔴 修改本清单前请先确认：{@link AiStreamStub} 的下标常量、
     * {@code ChatStreamRunner} 的实参顺序、以及全部 IT 的 Answer 均已同步。
     */
    private static final List<String> EXPECTED_TYPES = List.of(
            "com.eyes.albedo.chat.ai.AiChatRequest",
            "java.util.function.Consumer<java.lang.String>",
            "java.util.function.Consumer<java.lang.String>",
            "java.util.function.Consumer<java.io.Closeable>",
            "java.util.function.BooleanSupplier",
            "int");

    @Test
    @DisplayName("🔴 AiChatClient.stream 必须只有一个签名（禁止便捷重载 —— 它是桩位漂移的温床）")
    void streamHasExactlyOneSignature() {
        List<Method> streams = Arrays.stream(AiChatClient.class.getDeclaredMethods())
                .filter(method -> "stream".equals(method.getName()))
                .toList();

        assertEquals(1, streams.size(),
                "🔴 检测到 " + streams.size() + " 个 stream 重载。便捷重载会让测试桩住一个版本、"
                        + "生产调用另一个版本，Mockito 对未桩版本返回 null → NPE → 整片 IT 静默红。"
                        + "请删除重载，或改造 AiStreamStub 使其显式桩住每一个重载。实际签名："
                        + streams.stream().map(Method::toGenericString).toList());
    }

    @Test
    @DisplayName("🔴 AiStreamStub 的参数下标必须与生产签名逐位一致（getArgument 下标编译器管不了）")
    void stubArgumentIndexesMatchProductionSignature() {
        Method stream = Arrays.stream(AiChatClient.class.getDeclaredMethods())
                .filter(method -> "stream".equals(method.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("AiChatClient 未声明 stream 方法"));

        assertEquals(AiStreamStub.ARG_COUNT, stream.getParameterCount(),
                "🔴 stream 形参个数已变更，请同步 AiStreamStub.ARG_COUNT / ARG_NAMES 与各 IT 的 Answer");
        assertEquals(AiStreamStub.ARG_COUNT, AiStreamStub.ARG_NAMES.length,
                "AiStreamStub.ARG_NAMES 与 ARG_COUNT 自相矛盾");

        List<String> actualTypes = Arrays.stream(stream.getGenericParameterTypes())
                .map(Type::getTypeName)
                .toList();
        assertEquals(EXPECTED_TYPES, actualTypes,
                "🔴 stream 形参类型/顺序已变更，AiStreamStub 的 getArgument 下标随即失效");

        Parameter[] parameters = stream.getParameters();
        assertTrue(parameters[0].isNamePresent(),
                "需要 maven-compiler-plugin 的 <parameters>true</parameters> 才能按参数名守护顺序");
        for (int index = 0; index < parameters.length; index++) {
            assertEquals(AiStreamStub.ARG_NAMES[index], parameters[index].getName(),
                    "🔴 stream 第 " + index + " 个形参已变为 " + parameters[index].getName()
                            + "，与 AiStreamStub.ARG_NAMES 不符。onDelta 与 onReasoning 擦除后同型，"
                            + "一旦互换只会在运行期表现为思考过程污染正文，务必同步下标常量");
        }
    }
}
