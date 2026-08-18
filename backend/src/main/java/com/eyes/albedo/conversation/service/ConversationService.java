package com.eyes.albedo.conversation.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.eyes.albedo.agent.entity.Agent;
import com.eyes.albedo.agent.service.AgentService;
import com.eyes.albedo.chat.dto.MessageDTO;
import com.eyes.albedo.chat.entity.Message;
import com.eyes.albedo.chat.repository.MessageRepository;
import com.eyes.albedo.chat.service.MessageMapper;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.common.Ids;
import com.eyes.albedo.common.PageResult;
import com.eyes.albedo.common.TimeFormat;
import com.eyes.albedo.conversation.dto.ConversationDTO;
import com.eyes.albedo.conversation.entity.Conversation;
import com.eyes.albedo.conversation.repository.ConversationRepository;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.tenant.TenantContext;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话服务：列表、详情、创建、重命名、软删除、历史消息。
 *
 * <p>越权处理统一口径：跨租户、跨用户、已删除的会话 ID 一律抛 {@code 10004}，
 * 🔴 <b>不区分</b>「不存在」与「无权访问」，避免通过错误码差异枚举其他租户的资源（AC-TEN-004）。
 */
@Slf4j
@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final AgentService agentService;
    private final MessageMapper messageMapper;
    private final BusinessConfig businessConfig;

    public ConversationService(ConversationRepository conversationRepository,
                               MessageRepository messageRepository,
                               AgentService agentService,
                               MessageMapper messageMapper,
                               BusinessConfig businessConfig) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.agentService = agentService;
        this.messageMapper = messageMapper;
        this.businessConfig = businessConfig;
    }

    /**
     * 会话列表（当前租户 + 当前用户 + 未删除，按 {@code updatedAt DESC, id DESC} 稳定排序）。
     */
    @Transactional(readOnly = true)
    public PageResult<ConversationDTO> list(long uid, String keyword, int page, int pageSize) {
        TenantContext.requireEnabled();
        int titleMax = businessConfig.requireInt(ConfigKeys.GROUP_CHAT, ConfigKeys.TITLE_MAX_CHARS);
        if (keyword != null && keyword.codePointCount(0, keyword.length()) > titleMax) {
            throw BusinessException.validation("keyword 长度不能超过 " + titleMax);
        }
        PageRequest pageable = PageRequest.of(page - 1, pageSize);
        Page<Conversation> result = (keyword == null || keyword.isBlank())
                ? conversationRepository.findMine(uid, pageable)
                : conversationRepository.searchMine(uid, keyword.trim(), pageable);
        return PageResult.of(result.map(this::toDTO));
    }

    /**
     * 会话详情。
     *
     * @throws BusinessException 10004 不存在 / 非本人 / 跨租户 / 已删除
     */
    @Transactional(readOnly = true)
    public ConversationDTO detail(long uid, String conversationIdRaw) {
        return toDTO(require(uid, conversationIdRaw));
    }

    /**
     * 创建空会话（首条消息也可通过 {@code POST /conversations/new/messages} 原子创建）。
     *
     * @param agentIdRaw 指定 Agent（可空 → 默认 Agent）
     */
    @Transactional
    public ConversationDTO create(long uid, String agentIdRaw) {
        TenantContext.requireEnabled();
        Agent agent = agentService.resolveForNewConversation(agentIdRaw);
        return toDTO(persistNew(uid, agent));
    }

    /**
     * 新建会话实体（供 chat 模块在「首发即建会话」事务中复用）。
     */
    @Transactional
    public Conversation persistNew(long uid, Agent agent) {
        Conversation conversation = new Conversation();
        conversation.setUid(uid);
        conversation.setAgentId(agent.getId());
        // 🔴 绑定创建时的已发布版本，此后 Agent 升级不影响本会话（RISK-005）
        conversation.setAgentVersion(agent.getCurrentVersion());
        conversation.setTitle("");
        conversation.setTitleSource(Conversation.TITLE_SOURCE_AUTO);
        conversation.setStatus(Conversation.STATUS_ACTIVE);
        conversation.setMessageCount(0);
        return conversationRepository.saveAndFlush(conversation);
    }

    /**
     * 重命名会话。
     *
     * <p>{@code titleSource} 置为 {@code manual} → 此后自动标题<b>不得覆盖</b>（AC-CON-004）。
     *
     * @throws BusinessException 10001 标题非法；10004 会话不存在；30020 并发冲突（EX-023）
     */
    @Transactional
    public ConversationDTO rename(long uid, String conversationIdRaw, String title, int expectedVersion) {
        Conversation conversation = require(uid, conversationIdRaw);
        String normalized = title == null ? "" : title.trim();
        int titleMax = businessConfig.requireInt(ConfigKeys.GROUP_CHAT, ConfigKeys.TITLE_MAX_CHARS);
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > titleMax) {
            throw BusinessException.validation("title 长度必须在 1~" + titleMax + " 个字符之间");
        }
        if (conversation.getVersion() == null || conversation.getVersion() != expectedVersion) {
            // 多标签页并发重命名：拒绝后提交者，🔴 不静默覆盖
            throw new BusinessException(ErrorCode.VERSION_CONFLICT, "会话已被其他操作更新，请刷新后重试");
        }
        conversation.setTitle(normalized);
        conversation.setTitleSource(Conversation.TITLE_SOURCE_MANUAL);
        return toDTO(conversationRepository.saveAndFlush(conversation));
    }

    /**
     * 软删除会话（30 天后由 M2 定时任务物理清除）。
     *
     * <p>🔴 正在生成时必须<b>先取消生成再删除</b>，后续分片丢弃（EX-022）；
     * 取消动作由 chat 模块在 Controller 层前置执行（避免 conversation → chat 的循环依赖）。
     */
    @Transactional
    public void delete(long uid, String conversationIdRaw) {
        Conversation conversation = require(uid, conversationIdRaw);
        conversation.setStatus(Conversation.STATUS_DELETED);
        conversation.setDeletedAt(Instant.now());
        conversationRepository.saveAndFlush(conversation);
        log.info("会话已软删除：conversationId={}", conversation.getId());
    }

    /**
     * 会话消息历史。
     *
     * @param includeSuperseded 是否包含被取代的历史尝试（默认 false）
     */
    @Transactional(readOnly = true)
    public PageResult<MessageDTO> messages(long uid, String conversationIdRaw, int page, int pageSize,
                                           boolean includeSuperseded) {
        Conversation conversation = require(uid, conversationIdRaw);
        PageRequest pageable = PageRequest.of(page - 1, pageSize);
        Page<Message> result = includeSuperseded
                ? messageRepository.findVisibleIncludingSuperseded(conversation.getId(), pageable)
                : messageRepository.findVisible(conversation.getId(), pageable);
        // 🔴 批量转换：整页消息只查一次 tool_calls（逐条 map(toDTO) 会构成 N+1）
        return PageResult.of(messageMapper.toDTOs(result.getContent()),
                result.getTotalElements(), page, pageSize);
    }

    /**
     * 加载并校验会话归属（当前租户 + 当前用户 + 未删除）。
     *
     * @throws BusinessException 10004 统一的「不存在」语义
     */
    @Transactional(readOnly = true)
    public Conversation require(long uid, String conversationIdRaw) {
        TenantContext.requireEnabled();
        long id = Ids.parse(conversationIdRaw);
        return conversationRepository.findByIdAndUidAndDeletedAtIsNull(id, uid)
                .orElseThrow(BusinessException::notFound);
    }

    /**
     * 校验会话可继续发送：会话本身未只读，且绑定的 Agent 仍可运行。
     *
     * @throws BusinessException 30040 会话只读；30031 Agent 已停用
     */
    @Transactional(readOnly = true)
    public void requireWritable(Conversation conversation) {
        if (Conversation.STATUS_READ_ONLY.equals(conversation.getStatus())) {
            throw new BusinessException(ErrorCode.CONVERSATION_READONLY);
        }
        // Agent 停用/归档 → 30031（EX-011），由前端转为只读语义
        agentService.requireRunnable(conversation.getAgentId());
    }

    /**
     * 生成结束后刷新会话摘要字段（消息数、最后消息时间），同时保证列表排序及时更新。
     */
    @Transactional
    public void refreshStats(long conversationId) {
        Optional<Conversation> found = conversationRepository.findById(conversationId);
        if (found.isEmpty()) {
            return;
        }
        Conversation conversation = found.get();
        conversation.setMessageCount((int) messageRepository.countVisible(conversationId));
        conversation.setLastMessageAt(Instant.now());
        conversationRepository.saveAndFlush(conversation);
    }

    /**
     * 写入自动生成的标题（🔴 用户手动改名后不再覆盖，AC-CON-004）。
     */
    @Transactional
    public Optional<String> applyAutoTitle(long conversationId, String title) {
        if (title == null || title.isBlank()) {
            return Optional.empty();
        }
        Optional<Conversation> found = conversationRepository.findById(conversationId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Conversation conversation = found.get();
        if (conversation.manualTitle() || !conversation.getTitle().isBlank()) {
            return Optional.empty();
        }
        conversation.setTitle(title);
        conversation.setTitleSource(Conversation.TITLE_SOURCE_AUTO);
        conversationRepository.saveAndFlush(conversation);
        return Optional.of(title);
    }

    /**
     * 会话内仍在生成的 assistant 消息 ID（删除前取消用）。
     */
    @Transactional(readOnly = true)
    public List<Long> inFlightMessageIds(long conversationId) {
        return messageRepository.findInFlight(conversationId).stream()
                .map(Message::getId)
                .toList();
    }

    ConversationDTO toDTO(Conversation conversation) {
        return new ConversationDTO(
                Ids.toStr(conversation.getId()),
                conversation.getTitle(),
                conversation.getTitleSource(),
                Ids.toStr(conversation.getAgentId()),
                conversation.getAgentVersion() == null ? 0L : conversation.getAgentVersion(),
                conversation.getStatus(),
                conversation.getMessageCount() == null ? 0 : conversation.getMessageCount(),
                TimeFormat.iso(conversation.getLastMessageAt()),
                TimeFormat.iso(conversation.getUpdatedAt()),
                conversation.getVersion() == null ? 0 : conversation.getVersion());
    }
}
