package com.eyes.albedo.tenant;

/**
 * 缓存失效条目数（L1 进程内 + L2 Redis 分别计数）。
 *
 * <p>存在意义：{@code api-spec.md} §7.2.1 要求缓存失效接口返回<b>各作用域实际失效条目数</b>
 * （{@code l1Evicted} / {@code l2Evicted}），并在部分失败时返回 {@code 30061} +
 * {@code incompleteScopes[]}，🔴 <b>禁止伪报成功</b>。
 * 因此失效动作必须把"真的删了几条"回报给调用方，而不是返回 void 让接口自行编造数字。
 *
 * <p>🔴 L1 是进程内缓存，只能失效<b>本 JVM</b>（依据 ADR-001 单体单实例前提）。
 *
 * @param l1Evicted 本 JVM L1 实际移除的条目数
 * @param l2Evicted Redis 实际删除的键数
 */
public record CacheEviction(int l1Evicted, long l2Evicted) {

    public static final CacheEviction NONE = new CacheEviction(0, 0L);

    public static CacheEviction of(int l1Evicted, long l2Evicted) {
        return new CacheEviction(l1Evicted, Math.max(0L, l2Evicted));
    }

    public CacheEviction plus(CacheEviction other) {
        if (other == null) {
            return this;
        }
        return new CacheEviction(l1Evicted + other.l1Evicted, l2Evicted + other.l2Evicted);
    }

    public long total() {
        return (long) l1Evicted + l2Evicted;
    }
}
