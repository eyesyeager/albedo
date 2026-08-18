package com.eyes.albedo.testsupport;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.eyes.eyesAuth.thrift.generate.auth.AuthDoubleReturnee;
import com.eyes.eyesAuth.thrift.generate.auth.AuthNeverExpireReturnee;
import com.eyes.eyesAuth.thrift.generate.auth.AuthService;
import com.eyes.eyesAuth.thrift.generate.auth.AuthSingleReturnee;
import com.eyes.eyesAuth.thrift.generate.common.TTCustomException;
import com.eyes.eyesAuth.thrift.generate.user.UserInfoReturnee;
import com.eyes.eyesAuth.thrift.generate.user.UserService;
import com.eyes.eyesAuth.thrift.generate.user.UserUpdateInfoReceiver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.thrift.TMultiplexedProcessor;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.layered.TFramedTransport;

/**
 * eyesUser 鉴权服务的<b>本地测试替身</b>（Mock Thrift Server）。
 *
 * <h2>🔴 为什么存在（必读，勿误用）</h2>
 * 真实 eyesUser 的 Thrift 端口（见 {@code application.yml} 的 eyes-auth.thrift.host）<b>不对公网开放</b>
 * （见《耶瞳用户中心接入文档》§2.2「需内网互通」与 §六上线清单）。实测从公网访问该端口时，
 * 前置的七层 HTTP 网关会在 10 秒后返回 {@code HTTP/1.1 502}，其响应首 4 字节 {@code "HTTP"}
 * 被 Thrift 客户端当作帧长度解析，表现为
 * {@code TTransportException: Frame size (1213486160) larger than max length}。
 *
 * <p>因此在无内网环境的开发机上，「持有效 token 的登录态」这一层回归无法进行。本类提供协议等价的
 * 替身，使惰性建户、会话 CRUD、流式对话、跨租户隔离等<b>业务逻辑</b>得以完整验证。
 *
 * <h2>🔴 使用边界（不可逾越）</h2>
 * <ul>
 *   <li>本类位于 {@code src/test}，<b>绝不</b>参与生产打包；生产/预发必须连真实 eyesUser</li>
 *   <li>它<b>不是</b>自建认证：不签发可信凭据、不校验签名、不存密码，仅按既有 Thrift 契约回放响应。
 *       「禁止自建认证/密码/自签 JWT」的红线针对生产代码路径，测试替身与
 *       {@code MockMcpServer}（计划既定的 Mock MCP）同类</li>
 *   <li>用它跑出的结论必须在测试报告中<b>标注为「Mock 替身验证」</b>，
 *       不得等同于与真实 eyesUser 的互通验证——后者仍需内网环境补做</li>
 *   <li>它<b>不验证 JWT 签名</b>（无密钥），故只能证明「我方代码在 token 有效时行为正确」，
 *       不能证明「伪造 token 会被拒绝」——该项由真实服务保证</li>
 * </ul>
 *
 * <h2>启动方式</h2>
 * <pre>
 * # 1) 启动替身（默认 19011 端口，appId=albedo）
 * java -cp "target/test-classes:target/classes:$(cat /tmp/albedo-cp.txt)" \
 *      com.eyes.albedo.testsupport.MockEyesAuthServer
 *
 * # 2) 后端指向替身（命令行覆盖，不改任何配置文件）
 * mvn spring-boot:run -Dspring-boot.run.arguments="\
 *     --eyes-auth.thrift.host=127.0.0.1 --eyes-auth.thrift.port=19011"
 * </pre>
 *
 * <p>可选参数：{@code --port=19011}、{@code --app-id=albedo}、
 * {@code --frozen-uids=1,2}（命中则返回 20003，用于验证前端清退链路）。
 *
 * <p>协议栈与 {@code com.eyes.eyesAuth.thrift.config.TTSocket} 逐层对齐（错一层即握手失败）：
 * {@code TFramedTransport → TCompactProtocol → TMultiplexedProtocol("AuthService"/"UserService")}。
 */
public final class MockEyesAuthServer {

    /** JWT 段分隔后的段数（header.payload.signature）。 */
    private static final int JWT_PARTS = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final int port;
    private final String appId;
    private final Set<Long> frozenUids;
    private TServer server;
    private Thread thread;

    public MockEyesAuthServer(int port, String appId, Set<Long> frozenUids) {
        this.port = port;
        this.appId = appId;
        this.frozenUids = Set.copyOf(frozenUids);
    }

    public static void main(String[] args) throws Exception {
        int port = 19011;
        String appId = "albedo";
        Set<Long> frozen = new HashSet<>();
        for (String arg : args) {
            if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()));
            } else if (arg.startsWith("--app-id=")) {
                appId = arg.substring("--app-id=".length());
            } else if (arg.startsWith("--frozen-uids=")) {
                for (String uid : arg.substring("--frozen-uids=".length()).split(",")) {
                    if (!uid.isBlank()) {
                        frozen.add(Long.parseLong(uid.trim()));
                    }
                }
            }
        }
        MockEyesAuthServer mock = new MockEyesAuthServer(port, appId, frozen);
        Runtime.getRuntime().addShutdownHook(new Thread(mock::stop));
        System.out.printf("[MockEyesAuth] 监听 127.0.0.1:%d  appId=%s  frozenUids=%s%n",
                port, appId, frozen);
        System.out.println("[MockEyesAuth] 🔴 仅供本地回归，绝不可用于生产");
        mock.serveBlocking();
    }

    /** 后台启动，返回后即可连接（供集成测试使用）。 */
    public void start() throws Exception {
        TServer built = build();
        this.server = built;
        this.thread = new Thread(built::serve, "mock-eyes-auth");
        this.thread.setDaemon(true);
        this.thread.start();
        waitUntilServing();
    }

    public void stop() {
        if (server != null) {
            server.stop();
        }
    }

    public int port() {
        return port;
    }

    private void serveBlocking() throws Exception {
        this.server = build();
        this.server.serve();
    }

    private TServer build() throws Exception {
        TMultiplexedProcessor processor = new TMultiplexedProcessor();
        processor.registerProcessor("AuthService", new AuthService.Processor<>(new AuthHandler()));
        processor.registerProcessor("UserService", new UserService.Processor<>(new UserHandler()));

        TServerSocket transport = new TServerSocket(port);
        return new TThreadPoolServer(new TThreadPoolServer.Args(transport)
                .processor(processor)
                .transportFactory(new TFramedTransport.Factory())
                .protocolFactory(new TCompactProtocol.Factory()));
    }

    private void waitUntilServing() throws InterruptedException {
        for (int i = 0; i < 100 && !server.isServing(); i++) {
            Thread.sleep(20);
        }
    }

    // ===================== AuthService =====================

    private final class AuthHandler implements AuthService.Iface {

        @Override
        public AuthSingleReturnee checkAuthBySingle(String requestAppId, String token)
                throws TTCustomException {
            Claims claims = parse(requestAppId, token);
            // auth-type=1（单 token）：每次校验都签发新 token，供响应头回写续期（AC-AUTH-004）
            return new AuthSingleReturnee(claims.uid(), claims.role(), reissue(claims));
        }

        @Override
        public AuthNeverExpireReturnee checkAuthByNeverExpire(String requestAppId, String token)
                throws TTCustomException {
            Claims claims = parse(requestAppId, token);
            AuthNeverExpireReturnee returnee = new AuthNeverExpireReturnee();
            returnee.setUid(claims.uid());
            returnee.setRole(claims.role());
            return returnee;
        }

        @Override
        public AuthDoubleReturnee checkAuthByDouble(String requestAppId, String sToken, String lToken)
                throws TTCustomException {
            // 本项目 auth-type=1，双 token 模式不在范围内；显式拒绝而非返回可疑的成功结果
            throw new TTCustomException(20008, "本替身未实现双 token 模式");
        }
    }

    /**
     * 解析并校验 token。
     *
     * <p>校验项与真实服务的<b>可观测语义</b>对齐：appId 一致性、格式合法性、过期、账号状态。
     * 🔴 <b>不校验签名</b>（替身无密钥），故不能用它证明「伪造 token 被拒绝」。
     */
    private Claims parse(String requestAppId, String token) throws TTCustomException {
        if (requestAppId == null || !requestAppId.equals(appId)) {
            throw new TTCustomException(20008, "appId 不匹配");
        }
        if (token == null || token.isBlank()) {
            throw new TTCustomException(20001, "身份凭据无效");
        }
        String[] parts = token.split("\\.");
        if (parts.length != JWT_PARTS) {
            throw new TTCustomException(20001, "身份凭据无效");
        }
        JsonNode payload;
        try {
            byte[] json = Base64.getUrlDecoder().decode(parts[1]);
            payload = MAPPER.readTree(new String(json, StandardCharsets.UTF_8));
        } catch (RuntimeException | java.io.IOException e) {
            throw new TTCustomException(20001, "身份凭据无效");
        }
        if (!payload.hasNonNull("uid")) {
            throw new TTCustomException(20004, "账户不存在");
        }
        if (payload.hasNonNull("appId") && !appId.equals(payload.get("appId").asText())) {
            throw new TTCustomException(20008, "token 与 appId 不匹配");
        }
        if (payload.hasNonNull("exp")
                && payload.get("exp").asLong() < Instant.now().getEpochSecond()) {
            throw new TTCustomException(20002, "身份凭据已过期");
        }
        long uid = payload.get("uid").asLong();
        if (frozenUids.contains(uid)) {
            throw new TTCustomException(20003, "账号已被冻结");
        }
        String role = payload.hasNonNull("role") ? payload.get("role").asText() : "ROLE_user";
        long exp = payload.hasNonNull("exp") ? payload.get("exp").asLong() : 0L;
        return new Claims(uid, role, exp);
    }

    /**
     * 重新签发 token：仅刷新 {@code iat}，<b>不延长 {@code exp}</b>。
     *
     * <p>不延长过期时间是为了让「token 过期 → 20002 → 前端清 token 跳 SSO」这条链路仍可被验证；
     * 若续期时顺手延长 exp，该用例将永远无法触发。
     */
    private String reissue(Claims claims) {
        String header = base64Url("{\"typ\":\"JWT\",\"alg\":\"HS256\"}");
        String payload = base64Url(String.format(
                "{\"sub\":\"eyesUser\",\"iss\":\"eyesyeager\",\"iat\":%d,\"exp\":%d,"
                        + "\"uid\":%d,\"role\":\"%s\",\"appId\":\"%s\"}",
                Instant.now().getEpochSecond(), claims.exp(), claims.uid(), claims.role(), appId));
        // 固定占位签名：替身不做密码学，且刻意与真实签名不同，避免被误当作可信凭据
        return header + "." + payload + ".mock-signature-not-verifiable";
    }

    private static String base64Url(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private record Claims(long uid, String role, long exp) {
    }

    // ===================== UserService =====================

    /**
     * 用户资料服务替身。
     *
     * <p>{@code UserProfileFetcher} 只读取 {@code username} 与 {@code avatar}
     * （明确不读 email），此处仍按真实结构返回 email，用于验证我方「不读取敏感字段」的纪律。
     */
    private static final class UserHandler implements UserService.Iface {

        @Override
        public UserInfoReturnee getUserInfo(long uid) {
            return buildUser(uid);
        }

        @Override
        public List<UserInfoReturnee> getBatchUserInfo(List<Long> uids) {
            List<UserInfoReturnee> list = new ArrayList<>();
            if (uids != null) {
                for (Long uid : uids) {
                    list.add(buildUser(uid));
                }
            }
            return list;
        }

        @Override
        public void updateUserInfo(UserUpdateInfoReceiver receiver) {
            // 本项目不调用该接口（资料修改属 eyesUser 职责），空实现即可
        }

        private static UserInfoReturnee buildUser(long uid) {
            UserInfoReturnee info = new UserInfoReturnee();
            info.setId(uid);
            info.setUsername("测试用户" + uid);
            info.setAvatar("https://placehold.co/64x64/111111/FFFFFF?text=U" + uid);
            info.setEmail("mock-" + uid + "@example.com");
            info.setCreateTime("2026-01-01 00:00:00");
            return info;
        }
    }
}
