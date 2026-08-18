package com.eyes.albedo.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 消息正文校验单测（AC-CHAT-004 / EX-020）。
 *
 * <p>重点验证：长度上限来自 {@code sys_config} 而非代码常量，且按 <b>Unicode 码点</b>计数
 * （emoji 等代理对必须算 1 个字符，否则前后端计数不一致，用户会遇到「前端说可以发、后端拒绝」）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatServiceContentTest {

    @Mock
    private BusinessConfig businessConfig;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        chatService = new ChatService(null, null, null, null, null, businessConfig);
        when(businessConfig.requireInt(eq(ConfigKeys.GROUP_CHAT), eq(ConfigKeys.MESSAGE_MIN_CHARS)))
                .thenReturn(1);
        when(businessConfig.requireInt(eq(ConfigKeys.GROUP_CHAT), eq(ConfigKeys.MESSAGE_MAX_CHARS)))
                .thenReturn(20000);
    }

    @Test
    @DisplayName("正常内容：1 字符边界通过，且去除首尾空白后原样返回")
    void trimsContent() {
        assertEquals("你", chatService.normalizeContent("你"));
        assertEquals("你好", chatService.normalizeContent("  你好  "));
    }

    @Test
    @DisplayName("空内容 / 纯空白 → 30041")
    void rejectsBlank() {
        assertEquals(ErrorCode.MESSAGE_TOO_LONG,
                assertThrows(BusinessException.class, () -> chatService.normalizeContent(null)).getCode());
        assertEquals(ErrorCode.MESSAGE_TOO_LONG,
                assertThrows(BusinessException.class, () -> chatService.normalizeContent("   \n\t ")).getCode());
    }

    @Test
    @DisplayName("超过上限 → 30041；恰好等于上限 → 通过")
    void enforcesMaxChars() {
        String exactly = "内".repeat(20000);
        assertEquals(20000, chatService.normalizeContent(exactly).length());

        BusinessException e = assertThrows(BusinessException.class,
                () -> chatService.normalizeContent("内".repeat(20001)));
        assertEquals(ErrorCode.MESSAGE_TOO_LONG, e.getCode());
    }

    @Test
    @DisplayName("按 Unicode 码点计数：emoji（代理对）算 1 个字符，与前端 [...str].length 一致")
    void countsCodePointsNotJavaChars() {
        // 😀 在 Java 中是 2 个 char，但只有 1 个码点；上限设为 2 时应当放行 2 个 emoji
        when(businessConfig.requireInt(eq(ConfigKeys.GROUP_CHAT), eq(ConfigKeys.MESSAGE_MAX_CHARS)))
                .thenReturn(2);
        String twoEmoji = "😀😀";
        assertEquals(twoEmoji, chatService.normalizeContent(twoEmoji));

        assertEquals(ErrorCode.MESSAGE_TOO_LONG,
                assertThrows(BusinessException.class,
                        () -> chatService.normalizeContent("😀😀😀")).getCode());
    }

    @Test
    @DisplayName("阈值必须来自 sys_config：配置缺失时抛 50003，绝不用代码默认值兜底")
    void thresholdMustComeFromConfig() {
        when(businessConfig.requireInt(anyString(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR));
        assertEquals(ErrorCode.INTERNAL_ERROR,
                assertThrows(BusinessException.class, () -> chatService.normalizeContent("你好")).getCode());
    }
}
