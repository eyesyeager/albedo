package com.eyes.albedo.site.dto;

import java.util.List;

/**
 * 并发编辑冲突详情（{@code 30020} 的 {@code data}）。
 *
 * <p>PRD §8.9：后提交者必须能「查看差异并重新应用」，🔴 不得静默覆盖。
 *
 * @param expectedVersion 提交者持有的版本
 * @param actualVersion   服务端当前版本
 * @param changedFields   服务端当前内容与提交内容存在差异的字段清单（差异摘要，不含正文）
 */
public record SiteConfigConflictDTO(int expectedVersion,
                                    int actualVersion,
                                    List<String> changedFields) {
}
