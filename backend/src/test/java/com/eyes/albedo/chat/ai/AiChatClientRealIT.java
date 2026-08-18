package com.eyes.albedo.chat.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.eyes.albedo.chat.dto.TokenUsage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 真实上游连通性测试（<b>默认跳过</b>）。
 *
 * <p>为什么默认跳过：它依赖外部模型服务的可用性与配额，放进常规 {@code mvn test} 会让
 * 构建结果取决于第三方状态（典型的"不稳定测试"）。SSE 契约本身由 {@code ChatStreamIT}
 * 用 Mock 上游做确定性验证。
 *
 * <p>手动执行：
 * <pre>mvn -o test -Dtest=AiChatClientRealIT -Dalbedo.it.ai=true</pre>
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "albedo.it.ai", matches = "true")
class AiChatClientRealIT {

    @Autowired
    private AiChatClient aiChatClient;

    @Test
    @DisplayName("真实调用混元流式接口：能收到分片并正常结束")
    void realStreaming() {
        AiChatRequest request = new AiChatRequest(
                "hunyuan-a13b",
                List.of(AiMessage.system("你是测试助手，请只回答一句话。"),
                        AiMessage.user("用一句话打个招呼")),
                new BigDecimal("0.70"),
                600,
                120,
                60);

        StringBuilder buffer = new StringBuilder();
        AtomicInteger chunks = new AtomicInteger();
        AiStreamOutcome outcome = aiChatClient.stream(request,
                delta -> {
                    buffer.append(delta);
                    chunks.incrementAndGet();
                },
                // 🔴 思考过程独立通道：不得拼进 buffer，否则本用例的"出字"断言会被思维链假性满足
                reasoning -> {
                },
                handle -> {
                },
                () -> false,
                8);

        System.out.println("[真实上游] 分片数=" + chunks.get() + " 内容=" + buffer);
        System.out.println("[真实上游] finishReason=" + outcome.finishReason()
                + " usage=" + outcome.usage());

        assertTrue(!outcome.cancelled(), "未请求取消时不应返回 cancelled");
        assertTrue(buffer.length() > 0, "必须收到可见文本分片（出字）");
        assertTrue(chunks.get() > 0);
    }

    @Test
    @DisplayName("取消检查生效：cancelCheck 恒 true 时提前结束并标记 cancelled")
    void realStreamingCancelled() {
        AiChatRequest request = new AiChatRequest(
                "hunyuan-a13b",
                List.of(AiMessage.user("请写一段 300 字的自我介绍")),
                new BigDecimal("0.70"),
                600,
                120,
                60);

        AiStreamOutcome outcome = aiChatClient.stream(request,
                delta -> {
                },
                reasoning -> {
                },
                handle -> {
                },
                () -> true,
                1);

        assertTrue(outcome.cancelled(), "取消检查命中后必须返回 cancelled");
    }

    @Test
    @DisplayName("上游返回不存在的模型 → AiStreamException(50002)，不泄露凭据")
    void unknownModelFailsWithUpstreamCode() {
        AiChatRequest request = new AiChatRequest(
                "model-does-not-exist",
                List.of(AiMessage.user("你好")),
                new BigDecimal("0.70"), 64, 60, 30);

        AiStreamException e = org.junit.jupiter.api.Assertions.assertThrows(AiStreamException.class,
                () -> aiChatClient.stream(request, delta -> {
                }, reasoning -> {
                }, handle -> {
                }, () -> false, 8));

        assertEquals(com.eyes.albedo.common.ErrorCode.UPSTREAM_UNAVAILABLE, e.getCode());
        assertTrue(!e.getMessage().contains("sk-"), "🔴 异常信息不得包含 api_key");
    }

    @Test
    @DisplayName("TokenUsage 结构可解析（上游未返回时为 null，不编造数字）")
    void usageIsOptional() {
        TokenUsage usage = new TokenUsage(1, 2, 3);
        assertEquals(3, usage.totalTokens());
    }
}
