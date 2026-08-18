package com.eyes.albedo.configcheck.dto;

import java.util.List;

/**
 * 配置校验报告（api-spec §7.3.1 的 {@code data}）。
 *
 * <p>{@code valid=true} 时接口返回 {@code code=0}（{@code warnings[]} 不阻断）；
 * {@code valid=false} 时返回 {@code 30060} 且本对象<b>原样</b>作为 {@code data}。
 *
 * @param objectType     被校验对象类型
 * @param objectId       被校验对象 ID（string）
 * @param valid          是否合法（等价于 {@code violations.isEmpty()}）
 * @param checkedObjects 实际校验的对象数（含引用链上的对象）
 * @param violations     阻断性问题
 * @param warnings       非阻断提示（供 DBA 自查）
 */
public record ConfigValidationReportDTO(String objectType,
                                        String objectId,
                                        boolean valid,
                                        int checkedObjects,
                                        List<ConfigViolationDTO> violations,
                                        List<ConfigViolationDTO> warnings) {
}
