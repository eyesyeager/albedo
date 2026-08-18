package com.eyes.albedo.chat.service;

import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;

import org.springframework.stereotype.Component;

/**
 * 会话标题生成（PRD §6.5）。
 *
 * <p>规则（确定性，零失败率）：首条用户消息 → 去换行/压缩空白 → 取前
 * {@code sys_config: chat.title_auto_chars}（40）个 Unicode 字符，且不超过
 * {@code chat.title_max_chars}（60）。
 *
 * <p>🔴 用户手动改名后不得覆盖：该判定在 {@code ConversationService.applyAutoTitle} 内完成
 * （{@code titleSource=manual} 直接跳过），生成器本身无状态。
 */
@Component
public class TitleGenerator {

    private final BusinessConfig businessConfig;

    public TitleGenerator(BusinessConfig businessConfig) {
        this.businessConfig = businessConfig;
    }

    /**
     * 由首条用户消息生成标题；内容为空返回空串（调用方跳过写入）。
     */
    public String generate(String firstUserMessage) {
        if (firstUserMessage == null || firstUserMessage.isBlank()) {
            return "";
        }
        int autoChars = businessConfig.requireInt(ConfigKeys.GROUP_CHAT, ConfigKeys.TITLE_AUTO_CHARS);
        int maxChars = businessConfig.requireInt(ConfigKeys.GROUP_CHAT, ConfigKeys.TITLE_MAX_CHARS);
        int limit = Math.min(autoChars, maxChars);

        String normalized = firstUserMessage.replaceAll("\\s+", " ").trim();
        int count = normalized.codePointCount(0, normalized.length());
        if (count <= limit) {
            return normalized;
        }
        return normalized.substring(0, normalized.offsetByCodePoints(0, limit));
    }
}
