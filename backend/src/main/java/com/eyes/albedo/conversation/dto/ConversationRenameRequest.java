package com.eyes.albedo.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 重命名会话请求（api-spec.md §4.5.4）。
 *
 * <p>长度上限取自 {@code sys_config: chat.title_max_chars}（反硬编码），
 * 因此这里只做「非空」的基础校验，具体上限在 Service 层按配置判定。
 *
 * @param title           新标题（去首尾空白后 1~title_max_chars 个 Unicode 字符）
 * @param expectedVersion 乐观锁版本（取自最近一次读取的 {@code version}）
 */
public record ConversationRenameRequest(@NotBlank(message = "不能为空") String title,
                                        @NotNull(message = "不能为空") Integer expectedVersion) {
}
