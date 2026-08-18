package com.eyes.albedo.chat.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.eyes.albedo.agent.dto.AgentRuntime;
import com.eyes.albedo.agent.entity.Agent;
import com.eyes.albedo.agent.service.AgentService;
import com.eyes.albedo.chat.dto.MessageSegment;
import com.eyes.albedo.chat.dto.PreparedGeneration;
import com.eyes.albedo.chat.dto.StopResultDTO;
import com.eyes.albedo.chat.dto.TokenUsage;
import com.eyes.albedo.chat.entity.Message;
import com.eyes.albedo.chat.repository.MessageRepository;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.ErrorCode;
import com.eyes.albedo.common.Ids;
import com.eyes.albedo.conversation.entity.Conversation;
import com.eyes.albedo.conversation.service.ConversationService;
import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;
import com.eyes.albedo.tenant.TenantContext;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 对话服务：消息校验、原子创建、状态流转、停止与幂等回放。
 *
 * <p>职责切分：本类负责<b>同步阶段</b>（校验 + 事务写库 + 状态流转），
 * {@link ChatStreamRunner} 负责<b>异步阶段</b>（上游流式 + SSE 写出）。
 * 这样事务边界短小清晰：🔴 绝不在流式生成期间持有数据库事务。
 */
@Slf4j
@Service
public class ChatService {

    /** {@code POST /conversations/{id}/messages} 中表示「原子创建会话」的特殊路径值。 */
    public static final String NEW_CONVERSATION = "new";

    private final MessageRepository messageRepository;
    private final ConversationService conversationService;
    private final AgentService agentService;
    private final MessageMapper messageMapper;
    private final ChatCancelService cancelService;
    private final BusinessConfig businessConfig;

    public ChatService(MessageRepository messageRepository,
                       ConversationService conversationService,
                       AgentService agentService,
                       MessageMapper messageMapper,
                       ChatCancelService cancelService,
                       BusinessConfig businessConfig) {
        this.messageRepository = messageRepository;
        this.conversationService = conversationService;
        this.agentService = agentService;
        this.messageMapper = messageMapper;
        this.cancelService = cancelService;
        this.businessConfig = businessConfig;
    }

    // ===================== 校验 =====================

    /**
     * 校验消息正文并返回规范化结果。
     *
     * @throws BusinessException 30041 空白或超过 {@code chat.message_max_chars}（AC-CHAT-004 / EX-020）
     */
    public String normalizeContent(String content) {
        String normalized = content == null ? "" : content.trim();
        int min = businessConfig.requireInt(ConfigKeys.GROUP_CHAT, ConfigKeys.MESSAGE_MIN_CHARS);
        int max = businessConfig.requireInt(ConfigKeys.GROUP_CHAT, ConfigKeys.MESSAGE_MAX_CHARS);
        // 按 Unicode 码点计数：emoji / 生僻字按 1 个字符，与前端 [...str].length 一致
        int length = normalized.isEmpty() ? 0 : normalized.codePointCount(0, normalized.length());
        if (length < min) {
            throw new BusinessException(ErrorCode.MESSAGE_TOO_LONG, "消息内容不能为空");
        }
        if (length > max) {
            throw new BusinessException(ErrorCode.MESSAGE_TOO_LONG, "消息长度不能超过 " + max + " 个字符");
        }
        return normalized;
    }

    // ===================== 准备阶段（事务） =====================

    /**
     * 首发即建会话：原子完成「建会话 + 保存用户消息 + 建 assistant(queued)」（AC-CON-002）。
     */
    @Transactional
    public PreparedGeneration prepareNewConversation(long uid, String agentIdRaw, String content,
                                                     String idempotencyKey) {
        String tenantId = TenantContext.requireEnabled().tenantId();
        Agent agent = agentService.resolveForNewConversation(agentIdRaw);
        Conversation conversation = conversationService.persistNew(uid, agent);
        AgentRuntime runtime = agentService.runtime(agent.getId(), agent.getCurrentVersion());

        Message userMessage = saveUserMessage(conversation.getId(), uid, content, idempotencyKey);
        Message assistant = saveAssistantPlaceholder(conversation.getId(), uid, runtime, 1, null, null);
        return new PreparedGeneration(tenantId, uid, conversation.getId(), userMessage.getId(),
                assistant.getId(), runtime, content, true);
    }

    /**
     * 在既有会话中发送消息。
     *
     * @throws BusinessException 10004 会话不存在/非本人；30040 会话只读；30031 Agent 已停用
     */
    @Transactional
    public PreparedGeneration prepareExistingConversation(long uid, String conversationIdRaw, String content,
                                                         String idempotencyKey) {
        String tenantId = TenantContext.requireEnabled().tenantId();
        Conversation conversation = conversationService.require(uid, conversationIdRaw);
        conversationService.requireWritable(conversation);
        // 🔴 使用会话绑定的版本，而不是 Agent 的最新版本（RISK-005）
        AgentRuntime runtime = agentService.runtime(conversation.getAgentId(), conversation.getAgentVersion());

        Message userMessage = saveUserMessage(conversation.getId(), uid, content, idempotencyKey);
        Message assistant = saveAssistantPlaceholder(conversation.getId(), uid, runtime, 1, null, null);
        boolean needTitle = conversation.getTitle() == null || conversation.getTitle().isBlank();
        return new PreparedGeneration(tenantId, uid, conversation.getId(), userMessage.getId(),
                assistant.getId(), runtime, firstUserContent(conversation.getId(), content), needTitle);
    }

    /**
     * 重新生成：新建 {@code attemptNo+1} 尝试，旧尝试 {@code isCurrent=0} 保留。
     *
     * <p>🔴 不重复保存用户消息（AC-CHAT-003）。
     */
    @Transactional
    public PreparedGeneration prepareRegenerate(long uid, String messageIdRaw, String idempotencyKey) {
        String tenantId = TenantContext.requireEnabled().tenantId();
        long messageId = Ids.parse(messageIdRaw);
        Message target = messageRepository.findByIdAndUid(messageId, uid)
                .orElseThrow(BusinessException::notFound);
        if (!Message.ROLE_ASSISTANT.equals(target.getRole())) {
            // 只允许对 assistant 尝试重新生成；其他一律按不存在处理，不暴露资源类型
            throw BusinessException.notFound();
        }
        Conversation conversation = conversationService.require(uid,
                String.valueOf(target.getConversationId()));
        conversationService.requireWritable(conversation);
        AgentRuntime runtime = agentService.runtime(conversation.getAgentId(), conversation.getAgentVersion());

        List<Message> preceding = messageRepository.findPrecedingUserMessage(
                conversation.getId(), target.getId(), PageRequest.of(0, 1));
        if (preceding.isEmpty()) {
            throw BusinessException.notFound();
        }
        Message userMessage = preceding.get(0);

        target.setIsCurrent(0);
        messageRepository.save(target);

        int attemptNo = (target.getAttemptNo() == null ? 1 : target.getAttemptNo()) + 1;
        Message assistant = saveAssistantPlaceholder(conversation.getId(), uid, runtime, attemptNo,
                target.getId(), idempotencyKey);
        boolean needTitle = conversation.getTitle() == null || conversation.getTitle().isBlank();
        return new PreparedGeneration(tenantId, uid, conversation.getId(), userMessage.getId(),
                assistant.getId(), runtime, userMessage.getContent(), needTitle);
    }

    // ===================== 幂等回放 =====================

    /**
     * 按幂等键查找既有记录。
     */
    @Transactional(readOnly = true)
    public Optional<Message> findByIdempotencyKey(long uid, String idempotencyKey) {
        return messageRepository.findByUidAndIdempotencyKey(uid, idempotencyKey);
    }

    /**
     * 定位幂等回放要重放的 assistant 消息。
     *
     * @param recorded 幂等键命中的消息（user 或 assistant）
     * @return 对应的 assistant 消息；找不到则 empty
     */
    @Transactional(readOnly = true)
    public Optional<Message> resolveReplayAssistant(Message recorded) {
        if (Message.ROLE_ASSISTANT.equals(recorded.getRole())) {
            return Optional.of(recorded);
        }
        List<Message> assistants = messageRepository.findAssistantAfter(
                recorded.getConversationId(), recorded.getId(), PageRequest.of(0, 1));
        return assistants.isEmpty() ? Optional.empty() : Optional.of(assistants.get(0));
    }

    // ===================== 状态流转（事务） =====================

    /**
     * queued → streaming。
     */
    @Transactional
    public void markStreaming(long assistantMessageId) {
        messageRepository.findById(assistantMessageId).ifPresent(message -> {
            if (!message.terminal()) {
                message.setStatus(Message.STATUS_STREAMING);
                messageRepository.save(message);
            }
        });
    }

    /**
     * 写入生成结果（终态）。
     *
     * <p>🔴 幂等：已是终态的消息不再覆盖（防止「停止」与「生成结束」竞态互相踩）。
     *
     * @param reasoning 思考过程（可空；🔴 与 {@code content} 分列存储，见
     *                  {@link Message#getReasoning()}）
     * @param segments  按轮次段落（可空；🔴 纯渲染投影，失败/为空只影响排版不影响正文）
     */
    @Transactional
    public void finish(long assistantMessageId, String content, String reasoning,
                       List<MessageSegment> segments, String status,
                       String finishReason, TokenUsage usage, String model, Integer errorCode) {
        Optional<Message> found = messageRepository.findById(assistantMessageId);
        if (found.isEmpty()) {
            return;
        }
        Message message = found.get();
        if (message.terminal()) {
            log.debug("assistant 消息已是终态，跳过覆盖：messageId={} status={}",
                    assistantMessageId, message.getStatus());
            return;
        }
        message.setContent(content == null ? "" : content);
        // 🔴 空思考过程写 null 而非 ""：区分「没有思考过程」与「思考过程为空串」，
        //    也让非推理模型的历史行保持 NULL、不占存储
        message.setReasoning(reasoning == null || reasoning.isEmpty() ? null : reasoning);
        message.setSegments(messageMapper.writeSegments(segments));
        message.setStatus(status);
        message.setFinishReason(finishReason == null ? "" : finishReason);
        message.setModel(model == null ? "" : model);
        message.setTokenUsage(messageMapper.writeUsage(usage));
        message.setErrorCode(errorCode);
        messageRepository.saveAndFlush(message);
    }

    /**
     * 停止生成（api-spec.md §4.6.2）。
     *
     * <p>动作：写 Redis 取消标记 + 立即关闭本机上游流；已生成内容由生成线程保存为 {@code stopped}。
     * 幂等：已是终态时直接返回其真实状态，不报错。
     */
    @Transactional
    public StopResultDTO stop(long uid, String messageIdRaw) {
        TenantContext.Snapshot tenant = TenantContext.requireEnabled();
        long messageId = Ids.parse(messageIdRaw);
        Message message = messageRepository.findByIdAndUid(messageId, uid)
                .orElseThrow(BusinessException::notFound);
        if (!Message.ROLE_ASSISTANT.equals(message.getRole())) {
            throw BusinessException.notFound();
        }
        if (message.terminal()) {
            return new StopResultDTO(Ids.toStr(message.getId()), message.getStatus());
        }

        cancelService.cancel(tenant.tenantId(), messageId);
        // 立即把状态推进到 stopped：前端据此在 ≤1s 内停止追加（AC-CHAT-002）；
        // 生成线程稍后写入「已生成内容」时会因为终态判定而不再改状态，只补内容。
        message.setStatus(Message.STATUS_STOPPED);
        message.setFinishReason(Message.FINISH_STOPPED);
        messageRepository.saveAndFlush(message);
        return new StopResultDTO(Ids.toStr(message.getId()), Message.STATUS_STOPPED);
    }

    /**
     * 停止后补写已生成内容（不改变终态）。
     *
     * <p>🔴 「取长者」而非无条件覆盖：停止时可能有两条路径先后写入，
     * 短的那次不得把已保存的更长内容截掉。
     */
    @Transactional
    public void appendStoppedContent(long assistantMessageId, String content, String reasoning,
                                     List<MessageSegment> segments,
                                     String model, TokenUsage usage) {
        messageRepository.findById(assistantMessageId).ifPresent(message -> {
            if (content != null && !content.isEmpty()
                    && (message.getContent() == null || message.getContent().length() < content.length())) {
                message.setContent(content);
            }
            // 思考过程同样取长者：中途停止时思考可能已产出一部分，保留它才能解释"为何停在这里"
            if (reasoning != null && !reasoning.isEmpty()
                    && (message.getReasoning() == null
                    || message.getReasoning().length() < reasoning.length())) {
                message.setReasoning(reasoning);
            }
            // 段落是排版投影：有新的就覆盖，没有就保持原样（绝不因它清空已有排版）
            String encoded = messageMapper.writeSegments(segments);
            if (encoded != null) {
                message.setSegments(encoded);
            }
            if (message.getModel() == null || message.getModel().isBlank()) {
                message.setModel(model == null ? "" : model);
            }
            if (message.getTokenUsage() == null) {
                message.setTokenUsage(messageMapper.writeUsage(usage));
            }
            if (!message.terminal()) {
                message.setStatus(Message.STATUS_STOPPED);
                message.setFinishReason(Message.FINISH_STOPPED);
            }
            messageRepository.saveAndFlush(message);
        });
    }

    /**
     * 读取消息（供回放与测试）。
     */
    @Transactional(readOnly = true)
    public Optional<Message> find(long messageId) {
        return messageRepository.findById(messageId);
    }

    // ===================== 内部实现 =====================

    private Message saveUserMessage(long conversationId, long uid, String content, String idempotencyKey) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setUid(uid);
        message.setRole(Message.ROLE_USER);
        message.setContent(content);
        // 服务端已确认落库 → 直接 sent（前端的 pending 是本地乐观态）
        message.setStatus(Message.STATUS_SENT);
        message.setAttemptNo(1);
        message.setIsCurrent(1);
        message.setIdempotencyKey(idempotencyKey);
        return messageRepository.saveAndFlush(message);
    }

    private Message saveAssistantPlaceholder(long conversationId, long uid, AgentRuntime runtime,
                                             int attemptNo, Long supersedesMessageId,
                                             String idempotencyKey) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setUid(uid);
        message.setRole(Message.ROLE_ASSISTANT);
        message.setContent("");
        message.setStatus(Message.STATUS_QUEUED);
        message.setAttemptNo(attemptNo);
        message.setIsCurrent(1);
        message.setSupersedesMessageId(supersedesMessageId);
        message.setModel(runtime.model());
        message.setAgentVersion(runtime.version());
        message.setIdempotencyKey(idempotencyKey);
        return messageRepository.saveAndFlush(message);
    }

    private String firstUserContent(long conversationId, String fallback) {
        List<Message> first = messageRepository.findFirstUserMessage(conversationId, PageRequest.of(0, 1));
        return first.isEmpty() ? fallback : first.get(0).getContent();
    }

    /**
     * 生成结束后刷新会话统计（消息数 / 最后消息时间）。
     */
    @Transactional
    public void touchConversation(long conversationId) {
        conversationService.refreshStats(conversationId);
    }

    /**
     * 会话是否已被删除（生成过程中被删除则丢弃后续分片，EX-022）。
     */
    @Transactional(readOnly = true)
    public boolean conversationDeleted(long conversationId, long uid) {
        try {
            conversationService.require(uid, String.valueOf(conversationId));
            return false;
        } catch (BusinessException e) {
            return true;
        }
    }

    /**
     * 记录生成开始时间（可观测用，避免在异步段读请求上下文）。
     */
    public Instant now() {
        return Instant.now();
    }
}
