package com.eyes.albedo.agent.controller;

import java.util.List;

import com.eyes.albedo.agent.dto.AgentDTO;
import com.eyes.albedo.agent.service.AgentService;
import com.eyes.albedo.common.PageResult;
import com.eyes.albedo.common.PageSupport;
import com.eyes.albedo.common.Result;
import com.eyes.eyesAuth.permission.Permission;
import com.eyes.eyesAuth.permission.PermissionEnum;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 展示接口（api-spec.md §4.4.1，M1 只读）。
 *
 * <p>权限 {@code NO}：匿名访客可浏览可用 Agent 展示信息（PRD §4 规则 5）。
 *
 * <p>🔴 只返回 {@code status=enabled} 且 {@code currentVersion>0} 的 Agent；
 * 空列表时 {@code total=0}，前端按 {@code agentUnavailableText} 展示并禁用输入（EX-010）。
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {

    private final AgentService agentService;
    private final PageSupport pageSupport;

    public AgentController(AgentService agentService, PageSupport pageSupport) {
        this.agentService = agentService;
        this.pageSupport = pageSupport;
    }

    @Permission(PermissionEnum.NO)
    @GetMapping
    public Result<PageResult<AgentDTO>> list(@RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer pageSize) {
        int currentPage = pageSupport.resolvePage(page);
        int size = pageSupport.resolvePageSize(pageSize);

        // Agent 数量在单租户内为个位到十位量级（管理型资源），一次查询后内存分页即可，
        // 既保证 isDefault/sortOrder/id 的稳定排序，又避免为管理型小表引入多次查询。
        List<AgentDTO> all = agentService.listRunnable();
        int from = Math.min((currentPage - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return Result.success(PageResult.of(all.subList(from, to), all.size(), currentPage, size));
    }
}
