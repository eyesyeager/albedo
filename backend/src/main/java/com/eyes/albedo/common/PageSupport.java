package com.eyes.albedo.common;

import com.eyes.albedo.sysconfig.BusinessConfig;
import com.eyes.albedo.sysconfig.ConfigKeys;

import org.springframework.stereotype.Component;

/**
 * 分页参数解析（api-spec.md §1.3）。
 *
 * <p>规则：
 * <ul>
 *   <li>{@code page} 缺省 1，必须 ≥1</li>
 *   <li>{@code pageSize} 缺省取 {@code sys_config: business.page_size_default}，
 *       上限 {@code business.page_size_max}，越界 → {@code 10001}</li>
 *   <li>🔴 默认值与上限<b>必须</b>来自 sys_config（反硬编码），代码中不得出现 20 / 100 字面量</li>
 *   <li>🔴 禁止无界查询：任何列表接口都必须经由本类得到有界的 pageSize（性能红线）</li>
 * </ul>
 */
@Component
public class PageSupport {

    private final BusinessConfig businessConfig;

    public PageSupport(BusinessConfig businessConfig) {
        this.businessConfig = businessConfig;
    }

    /**
     * 解析并校验页码。
     *
     * @throws BusinessException 10001 页码非法
     */
    public int resolvePage(Integer page) {
        if (page == null) {
            return 1;
        }
        if (page < 1) {
            throw BusinessException.validation("page 必须大于等于 1");
        }
        return page;
    }

    /**
     * 解析并校验每页条数。
     *
     * @throws BusinessException 10001 超出允许范围
     */
    public int resolvePageSize(Integer pageSize) {
        int max = businessConfig.requireInt(ConfigKeys.GROUP_BUSINESS, ConfigKeys.PAGE_SIZE_MAX);
        if (pageSize == null) {
            return Math.min(
                    businessConfig.requireInt(ConfigKeys.GROUP_BUSINESS, ConfigKeys.PAGE_SIZE_DEFAULT), max);
        }
        if (pageSize < 1 || pageSize > max) {
            throw BusinessException.validation("pageSize 取值范围 1~" + max);
        }
        return pageSize;
    }
}
