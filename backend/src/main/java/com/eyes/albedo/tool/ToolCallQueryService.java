package com.eyes.albedo.tool;

import java.util.ArrayList;
import java.util.List;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.Ids;
import com.eyes.albedo.common.PageResult;
import com.eyes.albedo.common.TimeFormat;
import com.eyes.albedo.conversation.entity.Conversation;
import com.eyes.albedo.conversation.service.ConversationService;
import com.eyes.albedo.tenant.TenantContext;
import com.eyes.albedo.tool.dto.ToolCallDTO;
import com.eyes.albedo.tool.entity.ToolCall;
import com.eyes.albedo.tool.repository.ToolCallRepository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话工具调用查询（api-spec §7.9.1，REQ-CHAT-003 / AC-CHAT-007）。
 *
 * <p>🔴 <b>租户 + 本人双重隔离</b>：
 * <ul>
 *   <li>会话归属经 {@code ConversationService.require(uid, id)} 校验（跨租户/非本人 → {@code 10004}）</li>
 *   <li>{@code tool_calls} 查询由 Hibernate discriminator 自动追加 {@code tenant_id}
 *       —— 🔴 因此本类<b>不使用原生 SQL</b>（原生 SQL 不会被追加租户条件，AR-003）</li>
 * </ul>
 *
 * <p>🔴 <b>稳定排序</b>：{@code created_at ASC, id ASC}（走 {@code idx_tenant_conv_created}），
 * 保证翻页无重复无遗漏（api-spec §1.3）。
 *
 * <p>🔴 <b>不缓存</b>：状态机随生成实时变化，缓存会让前端看到"僵尸卡片"。
 */
@Slf4j
@Service
public class ToolCallQueryService {

    private final ToolCallRepository toolCallRepository;
    private final ConversationService conversationService;

    public ToolCallQueryService(ToolCallRepository toolCallRepository,
                                ConversationService conversationService) {
        this.toolCallRepository = toolCallRepository;
        this.conversationService = conversationService;
    }

    /**
     * 分页查询。
     *
     * @param statusFilter 逗号分隔的状态过滤；🔴 取值必须属 {@link ToolCall#ALL_STATUSES}，否则 {@code 10001}
     * @throws BusinessException 10001 状态参数非法；10004 会话不存在 / 非本人 / 跨租户
     */
    @Transactional(readOnly = true)
    public PageResult<ToolCallDTO> list(long uid, String conversationIdRaw, String messageIdRaw,
                                        String statusFilter, int page, int pageSize) {
        TenantContext.requireEnabled();
        Conversation conversation = conversationService.require(uid, conversationIdRaw);
        List<String> statuses = parseStatuses(statusFilter);
        PageRequest pageable = PageRequest.of(page - 1, pageSize);

        Page<ToolCall> rows;
        if (messageIdRaw != null && !messageIdRaw.isBlank()) {
            long messageId = Ids.parse(messageIdRaw);
            rows = toolCallRepository.findByConversationIdAndMessageIdOrderByCreatedAtAscIdAsc(
                    conversation.getId(), messageId, pageable);
        } else if (!statuses.isEmpty()) {
            rows = toolCallRepository.findByConversationIdAndStatusInOrderByCreatedAtAscIdAsc(
                    conversation.getId(), statuses, pageable);
        } else {
            rows = toolCallRepository.findByConversationIdOrderByCreatedAtAscIdAsc(
                    conversation.getId(), pageable);
        }

        List<ToolCallDTO> list = new ArrayList<>(rows.getContent().size());
        for (ToolCall row : rows.getContent()) {
            if (!statuses.isEmpty() && !statuses.contains(row.getStatus())) {
                // messageId + status 同时给出时的内存二次过滤（避免为组合条件加一个新查询方法）
                continue;
            }
            list.add(toDto(row));
        }
        return PageResult.of(list, rows.getTotalElements(), page, pageSize);
    }

    private List<String> parseStatuses(String statusFilter) {
        if (statusFilter == null || statusFilter.isBlank()) {
            return List.of();
        }
        List<String> statuses = new ArrayList<>();
        for (String raw : statusFilter.split(",")) {
            String value = raw.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (!ToolCall.ALL_STATUSES.contains(value)) {
                throw BusinessException.validation("status 取值非法：" + value);
            }
            statuses.add(value);
        }
        return statuses;
    }

    /**
     * 实体 → DTO（🔴 只取摘要列，字段禁含清单见 {@link ToolCallDTO}）。
     */
    private ToolCallDTO toDto(ToolCall row) {
        return new ToolCallDTO(
                Ids.toStr(row.getId()),
                Ids.toStr(row.getMessageId()),
                row.getRound() == null ? 1 : row.getRound(),
                row.getToolType(),
                row.getToolKey(),
                row.getToolNameSnapshot(),
                row.getStatus(),
                row.getErrorCode(),
                row.getArgsSummary(),
                row.getResultSummary(),
                Integer.valueOf(1).equals(row.getTruncated()),
                TimeFormat.iso(row.getStartedAt()),
                TimeFormat.iso(row.getFinishedAt()),
                row.getDurationMs());
    }
}
