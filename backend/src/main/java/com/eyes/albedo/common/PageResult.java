package com.eyes.albedo.common;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * 统一分页结构（框架 §14.1）：{@code data: { list, total, page, pageSize }}。
 *
 * @param list     当前页数据
 * @param total    总条数
 * @param page     当前页码（从 1 开始）
 * @param pageSize 每页条数
 * @param <T>      元素类型
 */
public record PageResult<T>(List<T> list, long total, int page, int pageSize) {

    public static <T> PageResult<T> of(List<T> list, long total, int page, int pageSize) {
        return new PageResult<>(list == null ? List.of() : list, total, page, pageSize);
    }

    /**
     * 从 Spring Data 分页结果构造（页码由 0 基转为 1 基）。
     */
    public static <T> PageResult<T> of(Page<T> page) {
        return new PageResult<>(page.getContent(), page.getTotalElements(),
                page.getNumber() + 1, page.getSize());
    }

    public static <T> PageResult<T> empty(int page, int pageSize) {
        return new PageResult<>(List.of(), 0L, page, pageSize);
    }
}
