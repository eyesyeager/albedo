package com.eyes.albedo.site.dto;

/**
 * 发布校验违规项（{@code 30021} 的 {@code data.violations[]} 元素）。
 *
 * @param field  出错字段（对外 camelCase）
 * @param rule   违反的规则分类，如 {@code required} / {@code length} / {@code unsafeContent} / {@code insecureUrl}
 * @param detail 可展示的说明（🔴 禁含内部地址、堆栈与密钥）
 */
public record ViolationDTO(String field, String rule, String detail) {

    public static ViolationDTO required(String field) {
        return new ViolationDTO(field, "required", "必填项不能为空");
    }

    public static ViolationDTO length(String field, int min, int max) {
        return new ViolationDTO(field, "length", "长度必须在 " + min + "~" + max + " 个字符之间");
    }

    public static ViolationDTO unsafeContent(String field, String detail) {
        return new ViolationDTO(field, "unsafeContent", detail);
    }

    public static ViolationDTO insecureUrl(String field) {
        return new ViolationDTO(field, "insecureUrl", "仅允许 https 地址或站内相对路径");
    }
}
