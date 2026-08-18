package com.eyes.albedo.tenant;

import com.eyes.eyesAuth.context.UserInfoHolder;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * 异步任务装饰器：把租户上下文与登录身份复制到执行线程（RISK-001 / AC-TEN-005）。
 *
 * <p>用于 {@code aiStreamExecutor} 等所有业务线程池。
 *
 * <p>🔴 缺失租户上下文的业务任务<b>直接拒绝执行</b>并记 ERROR，绝不"降级为无租户执行"。
 */
@Slf4j
public class TenantAwareTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        TenantContext.Snapshot snapshot = TenantContext.snapshotForAsync();
        Long uid = UserInfoHolder.getUid();
        String role = UserInfoHolder.getRole();
        String requestId = MDC.get(TenantFilter.MDC_REQUEST_ID);

        return () -> {
            if (snapshot == null) {
                log.error("[SECURITY] 拒绝执行缺少租户上下文的异步任务，requestId={}", requestId);
                return;
            }
            try {
                TenantContext.restore(snapshot);
                if (uid != null) {
                    UserInfoHolder.setUserInfo(uid, role);
                }
                if (requestId != null) {
                    MDC.put(TenantFilter.MDC_REQUEST_ID, requestId);
                }
                MDC.put(TenantFilter.MDC_TENANT_ID, snapshot.tenantId());
                if (uid != null) {
                    MDC.put(TenantFilter.MDC_UID, String.valueOf(uid));
                }
                runnable.run();
            } finally {
                TenantContext.clear();
                UserInfoHolder.removeAll();
                MDC.remove(TenantFilter.MDC_REQUEST_ID);
                MDC.remove(TenantFilter.MDC_TENANT_ID);
                MDC.remove(TenantFilter.MDC_UID);
            }
        };
    }
}
