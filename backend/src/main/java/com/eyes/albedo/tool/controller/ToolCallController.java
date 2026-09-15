package com.eyes.albedo.tool.controller;

import com.eyes.albedo.common.BusinessException;
import com.eyes.albedo.common.PageResult;
import com.eyes.albedo.common.PageSupport;
import com.eyes.albedo.common.Result;
import com.eyes.albedo.tool.ToolCallQueryService;
import com.eyes.albedo.tool.dto.ToolCallDTO;
import com.eyes.eyesAuth.context.UserInfoHolder;
import com.eyes.eyesAuth.permission.Permission;
import com.eyes.eyesAuth.permission.PermissionEnum;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具调用相关接口（api-spec §7.9.1 可追溯查询）。
 *
 * <p>{@code @Permission(USER)} + <b>本人</b>；🔴 跨租户 / 非本人 / 不存在统一 {@code 10004}
 * （不泄露存在性，AC-TEN-004）。
 */
@RestController
public class ToolCallController {

    private final ToolCallQueryService queryService;
    private final PageSupport pageSupport;

    public ToolCallController(ToolCallQueryService queryService,
                              PageSupport pageSupport) {
        this.queryService = queryService;
        this.pageSupport = pageSupport;
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
