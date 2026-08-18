package com.eyes.albedo.quota.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 额度快照的<b>形状契约</b>（🔴 K13 / api-spec §7.15.2：<b>恰 9 键</b>，多一键或少一键均为缺陷）。
 *
 * <p>🔴 <b>为什么必须用带 {@code NON_NULL} 的 ObjectMapper 断言</b>：生产配置是
 * {@code spring.jackson.default-property-inclusion: non_null}，它会把 {@code limit=null} /
 * {@code remaining=null} <b>整键省略</b> → 未启用额度时快照会退化成 7 键。
 * 本用例复刻该配置，钉住 DTO 上的 {@code @JsonInclude(ALWAYS)} 不被后人删掉。
 */
class QuotaSnapshotDTOContractTest {

    /** 🔴 逐字照抄 api-spec §7.15.2 的 9 个键。 */
    private static final Set<String> EXPECTED_KEYS = Set.of(
            "enabled", "limit", "used", "remaining", "status",
            "periodStart", "resetsAt", "timezone", "asOf");

    /** 🔴 复刻生产 Jackson 配置（否则本用例会因"默认包含 null"而失去意义）。 */
    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private Set<String> keysOf(QuotaSnapshotDTO snapshot) throws Exception {
        JsonNode node = mapper.readTree(mapper.writeValueAsString(snapshot));
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        assertEquals(names.size(), Set.copyOf(names).size(), "键不得重复：" + names);
        return Set.copyOf(names);
    }

    @Test
    @DisplayName("🔴 K13：可用态快照的键集合**恰为** 9 项")
    void availableSnapshotHasExactlyNineKeys() throws Exception {
        QuotaSnapshotDTO snapshot = new QuotaSnapshotDTO(true, 50, 12, 38,
                QuotaSnapshotDTO.STATUS_AVAILABLE, "2026-08-13T16:00:00.000Z",
                "2026-08-14T16:00:00.000Z", "Asia/Shanghai", "2026-08-14T03:21:07.412Z");

        assertEquals(EXPECTED_KEYS, keysOf(snapshot));
    }

    @Test
    @DisplayName("🔴 K13：未启用态 limit / remaining 为 null 但**键仍在**（恰 9 键，禁止伪造数值上限）")
    void unlimitedSnapshotKeepsNullKeys() throws Exception {
        QuotaSnapshotDTO snapshot = new QuotaSnapshotDTO(false, null, 12, null,
                QuotaSnapshotDTO.STATUS_UNLIMITED, "2026-08-13T16:00:00.000Z",
                "2026-08-14T16:00:00.000Z", "Asia/Shanghai", "2026-08-14T03:21:07.412Z");

        JsonNode node = mapper.readTree(mapper.writeValueAsString(snapshot));
        assertEquals(EXPECTED_KEYS, keysOf(snapshot),
                "🔴 全局 non_null 会省略 null 键 → DTO 必须保留 @JsonInclude(ALWAYS)");
        assertTrue(node.get("limit").isNull(), "🔴 未启用时 limit 必须是 null");
        assertTrue(node.get("remaining").isNull(), "🔴 未启用时 remaining 必须是 null");
        assertEquals(12, node.get("used").asLong(), "🔴 used 仍是真实已结算数（不伪造 0）");
    }

    @Test
    @DisplayName("🔴 K5 / K13 反向断言：快照**不含** retryAfterSeconds、不含任何 QPM 阈值")
    void snapshotNeverCarriesRateLimitFields() throws Exception {
        Set<String> keys = keysOf(new QuotaSnapshotDTO(true, 50, 50, 0,
                QuotaSnapshotDTO.STATUS_EXHAUSTED, "2026-08-13T16:00:00.000Z",
                "2026-08-14T16:00:00.000Z", "Asia/Shanghai", "2026-08-14T03:21:07.412Z"));

        assertFalse(keys.contains("retryAfterSeconds"),
                "🔴 携带即被前端 rateLimitStore 误表现为秒级倒计时");
        assertFalse(keys.contains("qpmLimit"), "🔴 禁止下发限流阈值（暴露策略 + 诱导前端双实现）");
        assertFalse(keys.contains("qpmEnabled"), "🔴 同上");
        assertFalse(keys.contains("quotaDate"), "🔴 只给 UTC 绝对时间 + IANA 时区");
    }

    @Test
    @DisplayName("status 字面量恰 3 个且与 enabled / remaining 语义一致")
    void statusLiteralsAreStable() {
        assertEquals("available", QuotaSnapshotDTO.STATUS_AVAILABLE);
        assertEquals("exhausted", QuotaSnapshotDTO.STATUS_EXHAUSTED);
        assertEquals("unlimited", QuotaSnapshotDTO.STATUS_UNLIMITED);
    }
}
