package com.eyes.albedo.metrics.controller;

import com.eyes.albedo.common.Result;
import com.eyes.albedo.metrics.dto.AnalyticsBatchRequest;
import com.eyes.albedo.metrics.dto.AnalyticsBatchResultDTO;
import com.eyes.albedo.metrics.service.AnalyticsEventService;
import com.eyes.albedo.tenant.TenantContext;
import com.eyes.eyesAuth.context.UserInfoHolder;
import com.eyes.eyesAuth.permission.Permission;
import com.eyes.eyesAuth.permission.PermissionEnum;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 产品埋点上报（api-spec §7.10.1，REQ-OBS-001 / AC-OBS-001）。
 *
 * <p>🔴 {@code @Permission(NO)}：<b>支持匿名事件</b>（如 {@code tenantSiteView} 在用户登录前就要上报）。
 * 因此 uid 取"能取到就取"，取不到即匿名 —— 🔴 <b>不能</b>用 {@code @Permission(USER)}，
 * 那会让站点访问类埋点在未登录时全部 20001。
 *
 * <p>🔴 <b>租户由 Host 解析</b>（{@code TenantFilter} → {@code TenantContext}）：
 * 请求体内<b>不存在</b> {@code tenantId} 字段（DTO 刻意不声明），客户端传了也不会生效；
 * 若确实探测到该键，会作为未知字段进入安全判定并记日志（EX-003）。
 *
 * <p>🔴 <b>不使用 {@code Idempotency-Key}</b>：去重由 {@code clientEventId} +
 * {@code uk(tenant_id, client_event_id)} 完成（api-spec §1.4），重复上报计入 {@code duplicated}。
 *
 * <p>🔴 <b>绝不拖慢主链路</b>：本端点是<b>独立</b>请求，对话热路径不会同步调用它；
 * 写库失败一律吞掉并计入 {@code discarded}，响应恒 {@code code=0}。
 */
@Slf4j
@RestController
public class AnalyticsEventController {

    private final AnalyticsEventService analyticsEventService;

    public AnalyticsEventController(AnalyticsEventService analyticsEventService) {
        this.analyticsEventService = analyticsEventService;
    }

    /**
     * 批量上报。
     *
     * <p>错误码：{@code 10001}（{@code events} 为空 / 超批量上限 / 缺 {@code clientEventId} 或
     * {@code eventName}）、{@code 30010}/{@code 30011}（未知或暂停租户）、{@code 50003}。
     */
    @Permission(PermissionEnum.NO)
    @PostMapping("/api/v1/events")
    public Result<AnalyticsBatchResultDTO> report(@RequestBody(required = false)
                                                  AnalyticsBatchRequest request) {
        return Result.success(analyticsEventService.report(currentUidOrAnonymous(), request));
    }

    /**
     * 当前 uid（🔴 取不到即匿名，绝不抛异常）。
     *
     * <p>{@code @Permission(NO)} 下 {@code UserInfoHolder} 可能完全没有身份；
     * 同时也兼容 {@code eyes-auth.enabled=false} 的本地场景。
     * 🔴 优先取 {@code TenantContext.uid()}（鉴权成功后由 {@code PermissionAspect} 回填），
     * 与其它接口保持同一取值口径。
     */
    private Long currentUidOrAnonymous() {
        Long contextUid = TenantContext.uid().orElse(null);
        if (contextUid != null) {
            return contextUid;
        }
        try {
            return UserInfoHolder.getUid();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
