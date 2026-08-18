package com.eyes.albedo.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
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
 * 会话标题生成单测（PRD §6.5）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TitleGeneratorTest {

    @Mock
    private BusinessConfig businessConfig;

    private TitleGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new TitleGenerator(businessConfig);
        when(businessConfig.requireInt(ConfigKeys.GROUP_CHAT, ConfigKeys.TITLE_AUTO_CHARS)).thenReturn(40);
        when(businessConfig.requireInt(ConfigKeys.GROUP_CHAT, ConfigKeys.TITLE_MAX_CHARS)).thenReturn(60);
    }

    @Test
    @DisplayName("短消息：原样作为标题")
    void shortMessage() {
        assertEquals("帮我挑一份生日礼物", generator.generate("帮我挑一份生日礼物"));
    }

    @Test
    @DisplayName("去换行并压缩空白（标题不得出现多行）")
    void collapsesWhitespace() {
        assertEquals("第一行 第二行", generator.generate("第一行\n第二行"));
        assertEquals("多个 空格", generator.generate("  多个    空格  "));
    }

    @Test
    @DisplayName("超长消息：截断到 title_auto_chars（40）个字符")
    void truncatesToAutoChars() {
        String title = generator.generate("字".repeat(100));
        assertEquals(40, title.codePointCount(0, title.length()));
    }

    @Test
    @DisplayName("截断按码点计算：不会把 emoji 截成半个字符（避免出现乱码方块）")
    void truncatesByCodePoint() {
        when(businessConfig.requireInt(ConfigKeys.GROUP_CHAT, ConfigKeys.TITLE_AUTO_CHARS)).thenReturn(2);
        String title = generator.generate("😀😀😀");
        assertEquals("😀😀", title);
    }

    @Test
    @DisplayName("auto 上限不得超过 title_max_chars（配置冲突时取更小值）")
    void autoCharsNeverExceedMax() {
        when(businessConfig.requireInt(ConfigKeys.GROUP_CHAT, ConfigKeys.TITLE_AUTO_CHARS)).thenReturn(80);
        when(businessConfig.requireInt(ConfigKeys.GROUP_CHAT, ConfigKeys.TITLE_MAX_CHARS)).thenReturn(60);

        String title = generator.generate("字".repeat(100));
        assertEquals(60, title.codePointCount(0, title.length()));
    }

    @Test
    @DisplayName("空消息：返回空串（调用方跳过写入，不产生空标题）")
    void blankMessage() {
        assertEquals("", generator.generate(null));
        assertEquals("", generator.generate("   "));
    }

    @Test
    @DisplayName("配置缺失时抛 50003（不允许用代码默认值生成标题）")
    void configMissing() {
        when(businessConfig.requireInt(anyString(), anyString()))
                .thenThrow(new BusinessException(ErrorCode.INTERNAL_ERROR));
        assertEquals(ErrorCode.INTERNAL_ERROR,
                assertThrows(BusinessException.class, () -> generator.generate("你好")).getCode());
    }
}
