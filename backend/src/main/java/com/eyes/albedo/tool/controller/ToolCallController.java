package com.eyes.albedo.tool.controller;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.PageResult;
import com.eyes.albedo.common.PageSupport;
import com.eyes.albedo.common.Result;
import com.eyes.albedo.tool.ToolCallQueryService;
import com.eyes.albedo.tool.ToolConfirmService;
import com.eyes.albedo.tool.dto.ToolCallDTO;
import com.eyes.albedo.tool.dto.ToolConfirmRequest;
import com.eyes.albedo.tool.dto.ToolConfirmResultDTO;
import com.eyes.eyesAuth.context.UserInfoHolder;
import com.eyes.eyesAuth.permission.Permission;
import com.eyes.eyesAuth.permission.PermissionEnum;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具调用相关接口（api-spec §7.8.2 确认 + §7.9.1 可追溯查询）。
 *
 * <p>两个接口都是 {@code @Permission(USER)} + <b>本人</b>；🔴 跨租户 / 非本人 / 不存在统一 {@code 10004}
 * （不泄露存在性，AC-TEN-004）。
 *
 * <p>🔴 确认接口<b>不使用</b> {@code Idempotency-Key}（api-spec §1.4）：
 * 幂等由资源状态机 + 行锁保证，携带该头也会被忽略 —— 这里<b>刻意不声明</b>该请求头参数。
 */
@RestController
public class ToolCallController {

    private final ToolConfirmService confirmService;
    private final ToolCallQueryService queryService;
    private final PageSupport pageSupport;

    public ToolCallController(ToolConfirmService confirmService,
                              ToolCallQueryService queryService,
                              PageSupport pageSupport) {
        this.confirmService = confirmService;
        this.queryService = queryService;
        this.pageSupport = pageSupport;
    }

    /**
     * 提交高风险工具的确认决定（api-spec §7.8.2）。
     *
     * <p>错误码：{@code 10001}（decision 非法 / reason 超长）、{@code 10003}（成员被禁用）、
     * {@code 10004}（消息或工具调用不存在 / 非本人 / 跨租户 / 无待确认调用）、
     * {@code 30055}（与既有决定相反）、{@code 50003}（审计写入失败，EX-024）。
     */
    @Permission(PermissionEnum.USER)
    @PostMapping("/api/v1/messages/{messageId}/tool-calls/{toolCallId}/confirm")
    public Result<ToolConfirmResultDTO> confirm(@PathVariable String messageId,
                                                @PathVariable String toolCallId,
                                                @RequestBody(required = false)
                                                ToolConfirmRequest request) {
        return Result.success(confirmService.confirm(currentUid(), messageId, toolCallId, request));
    }

    /**
     * 会话工具调用列表（api-spec §7.9.1）。
     *
     * <p>错误码：{@code 10001}（分页 / 状态参数非法）、{@code 10004}（会话不存在 / 非本人 / 跨租户）。
     */
    @Permission(PermissionEnum.USER)
    @GetMapping("/api/v1/conversations/{conversationId}/tool-calls")
    public Result<PageResult<ToolCallDTO>> toolCalls(@PathVariable String conversationId,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer pageSize,
                                                     @RequestParam(required = false) String messageId,
                                                     @RequestParam(required = false) String status) {
        return Result.success(queryService.list(currentUid(), conversationId, messageId, status,
                pageSupport.resolvePage(page), pageSupport.resolvePageSize(pageSize)));
    }

    private long currentUid() {
        Long uid = UserInfoHolder.getUid();
        if (uid == null) {
            throw BusinessException.permissionDenied("无法确定当前身份");
        }
        return uid;
    }
}
