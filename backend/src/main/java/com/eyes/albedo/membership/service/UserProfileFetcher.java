package com.eyes.albedo.membership.service;

import com.eyes.albedo.membership.dto.ProfileSnapshot;
import com.eyes.eyesAuth.thrift.TTClientPool;
import com.eyes.eyesAuth.thrift.config.TTSocket;
import com.eyes.eyesAuth.thrift.generate.user.UserInfoReturnee;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * eyesUser 资料读取（best-effort）。
 *
 * <p>规则（PRD §8.3 / api-spec §4.3.1）：
 * <ul>
 *   <li>只取 {@code username} 与 {@code avatar} 两个展示字段；
 *       🔴 eyesUser 虽然返回 {@code email}，本类<b>绝不</b>读取或落库</li>
 *   <li>任何失败（Thrift 不可用、超时、账号异常）都<b>不抛异常</b>，返回空快照，
 *       保证「头像/昵称获取失败不阻断身份建立」</li>
 *   <li>连接按「借出 → 调用 → 归还（异常时失效）」使用，避免连接泄漏</li>
 * </ul>
 */
@Slf4j
@Component
public class UserProfileFetcher {

    private final ObjectProvider<TTClientPool> clientPoolProvider;

    public UserProfileFetcher(ObjectProvider<TTClientPool> clientPoolProvider) {
        this.clientPoolProvider = clientPoolProvider;
    }

    /**
     * 拉取用户展示资料；失败返回 {@link ProfileSnapshot#empty()}。
     */
    public ProfileSnapshot fetch(long uid) {
        // 用 ObjectProvider 而非强依赖：eyes-auth 未启用（如测试 profile）或连接池初始化失败时，
        // 资料读取应当降级为空快照，而不是让整个应用无法启动——资料本身是 best-effort 数据。
        TTClientPool clientPool = clientPoolProvider.getIfAvailable();
        if (clientPool == null) {
            log.debug("eyesUser 连接池不可用，按空资料快照处理：uid={}", uid);
            return ProfileSnapshot.empty();
        }
        TTSocket socket = null;
        try {
            socket = clientPool.getConnect();
            UserInfoReturnee info = socket.getUserClient().getUserInfo(uid);
            if (info == null) {
                return ProfileSnapshot.empty();
            }
            return new ProfileSnapshot(info.getUsername(), info.getAvatar()).normalized();
        } catch (Exception e) {
            if (socket != null) {
                clientPool.invalidateObject(socket);
                socket = null;
            }
            // 只记录分类与 uid，不记录返回体（可能含邮箱）
            log.warn("获取 eyesUser 资料失败，按空快照继续：uid={} cause={}", uid, e.getClass().getSimpleName());
            return ProfileSnapshot.empty();
        } finally {
            if (socket != null && socket.isOpen()) {
                clientPool.returnConnection(socket);
            }
        }
    }
}
