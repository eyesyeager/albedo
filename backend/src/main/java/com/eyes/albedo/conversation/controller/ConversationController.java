package com.eyes.albedo.conversation.controller;

import com.eyes.albedo.chat.dto.MessageDTO;
import com.eyes.albedo.chat.service.ChatCancelService;
import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.PageResult;
import com.eyes.albedo.common.PageSupport;
import com.eyes.albedo.common.Result;
import com.eyes.albedo.conversation.dto.ConversationCreateRequest;
import com.eyes.albedo.conversation.dto.ConversationDTO;
import com.eyes.albedo.conversation.dto.ConversationRenameRequest;
import com.eyes.albedo.conversation.entity.Conversation;
import com.eyes.albedo.conversation.service.ConversationService;
import com.eyes.albedo.chat.service.IdempotencyService;
import com.eyes.eyesAuth.context.UserInfoHolder;
import com.eyes.eyesAuth.permission.Permission;
import com.eyes.eyesAuth.permission.PermissionEnum;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话接口（api-spec.md §4.5）。
 *
 * <p>全部要求 {@code @Permission(USER)} + 本人；越权统一 {@code 10004}。
 */
@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

    private final ConversationService conversationService;
    private final ChatCancelService cancelService;
    private final IdempotencyService idempotencyService;
    private final PageSupport pageSupport;

    public ConversationController(ConversationService conversationService,
                                  ChatCancelService cancelService,
                                  IdempotencyService idempotencyService,
                                  PageSupport pageSupport) {
        this.conversationService = conversationService;
        this.cancelService = cancelService;
        this.idempotencyService = idempotencyService;
        this.pageSupport = pageSupport;
    }

    @Permission(PermissionEnum.USER)
    @GetMapping
    public Result<PageResult<ConversationDTO>> list(@RequestParam(required = false) Integer page,
                                                    @RequestParam(required = false) Integer pageSize,
                                                    @RequestParam(required = false) String keyword) {
        return Result.success(conversationService.list(currentUid(),
                keyword,
                pageSupport.resolvePage(page),
                pageSupport.resolvePageSize(pageSize)));
    }

    /**
     * 创建会话。
     *
     * <p>幂等：请求头 {@code Idempotency-Key} 必填，重复提交返回<b>原会话</b>，不重复创建（§1.4）。
     */
    @Permission(PermissionEnum.USER)
    @PostMapping
    public Result<ConversationDTO> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) ConversationCreateRequest request) {
        long uid = currentUid();
        String key = idempotencyService.requireKey(idempotencyKey);
        String agentId = request == null ? null : request.agentId();

        return Result.success(idempotencyService.executeOnce(uid, key,
                () -> conversationService.create(uid, agentId),
                conversationId -> conversationService.detail(uid, conversationId),
                dto -> dto.conversationId()));
    }

    @Permission(PermissionEnum.USER)
    @GetMapping("/{conversationId}")
    public Result<ConversationDTO> detail(@PathVariable String conversationId) {
        return Result.success(conversationService.detail(currentUid(), conversationId));
    }

    @Permission(PermissionEnum.USER)
    @PatchMapping("/{conversationId}")
    public Result<ConversationDTO> rename(@PathVariable String conversationId,
                                          @RequestBody @Valid ConversationRenameRequest request) {
        return Result.success(conversationService.rename(currentUid(), conversationId,
                request.title(), request.expectedVersion()));
    }

    /**
     * 删除会话（软删除）。
     *
     * <p>🔴 若该会话正在生成：先取消生成（Redis 标记 + 本机关流），再软删除，
     * 后续分片被丢弃（EX-022）。
     */
    @Permission(PermissionEnum.USER)
    @DeleteMapping("/{conversationId}")
    public Result<Void> delete(@PathVariable String conversationId) {
        long uid = currentUid();
        Conversation conversation = conversationService.require(uid, conversationId);
        conversationService.inFlightMessageIds(conversation.getId())
                .forEach(cancelService::cancel);
        conversationService.delete(uid, conversationId);
        return Result.success();
    }

    @Permission(PermissionEnum.USER)
    @GetMapping("/{conversationId}/messages")
    public Result<PageResult<MessageDTO>> messages(
            @PathVariable String conversationId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false, defaultValue = "false") boolean includeSuperseded) {
        return Result.success(conversationService.messages(currentUid(), conversationId,
                pageSupport.resolvePage(page),
                pageSupport.resolvePageSize(pageSize),
                includeSuperseded));
    }

    private long currentUid() {
        Long uid = UserInfoHolder.getUid();
        if (uid == null) {
            throw BusinessException.permissionDenied("无法确定当前身份");
        }
        return uid;
    }
}
