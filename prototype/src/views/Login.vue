<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAuth } from "@/store/auth";

const route = useRoute();
const router = useRouter();
const { saveToken } = useAuth();

const account = ref("linshen@eyescode.top");
const pwd = ref("demo1234");
const loading = ref(false);
const err = ref("");

function fakeJwt() {
  const head = btoa(JSON.stringify({ alg: "HS256", typ: "JWT" }));
  const body = btoa(
    JSON.stringify({
      uid: 10247,
      role: "USER",
      clientId: 10001,
      iat: Math.floor(Date.now() / 1000),
    })
  );
  return `${head}.${body}.sig-${(Math.random() * 1e9).toString(16)}`;
}

function login() {
  if (!account.value || !pwd.value) {
    err.value = "请输入账号与密码";
    return;
  }
  err.value = "";
  loading.value = true;
  setTimeout(() => {
    saveToken(fakeJwt());
    const redirect = (route.query.redirect as string) || "/overview";
    router.replace(redirect);
  }, 700);
}
</script>

<template>
  <div class="login">
    <section class="left">
      <div class="left-inner">
        <div class="brand-row">
          <div class="logo">A</div>
          <div>
            <div class="logo-name">Albedo</div>
            <div class="logo-sub">通用企业智能体开发平台</div>
          </div>
        </div>
        <h1>构建、调试并运营<br />你的企业级 AI 智能体</h1>
        <p class="lede">
          统一接入知识库、MCP 工具与多类模型，以 AGUI 协议对外提供能力，
          覆盖「配置 → 开发 → 调试 → 发布 → 运营」全生命周期。
        </p>
        <ul class="feats">
          <li><span class="ck">✓</span> 零代码/低代码搭建 Agent 应用</li>
          <li><span class="ck">✓</span> 知识库 RAG · 来源溯源</li>
          <li><span class="ck">✓</span> MCP 工具级管控与调试</li>
          <li><span class="ck">✓</span> AGUI 事件流 API · JWT 验签</li>
        </ul>
        <div class="left-foot">v0.3 · MVP · AgentOps</div>
      </div>
    </section>

    <section class="right">
      <div class="sso-card">
        <div class="sso-badge">
          <span class="eye">👁</span> 耶瞳用户中心 · 统一登录
        </div>
        <h2>登录以继续使用 Albedo</h2>
        <p class="sso-tip">
          本平台接入 eyesUser 单点登录，登录一次即可免登访问所有已接入应用。
        </p>

        <label class="field-label">账号</label>
        <input v-model="account" class="field" placeholder="邮箱 / 手机号" />

        <label class="field-label" style="margin-top: 16px">密码</label>
        <input
          v-model="pwd"
          class="field"
          type="password"
          placeholder="请输入密码"
          @keyup.enter="login"
        />

        <div v-if="err" class="err">{{ err }}</div>

        <button class="btn-primary login-btn" :disabled="loading" @click="login">
          {{ loading ? "正在校验…" : "登录并授权" }}
        </button>

        <div class="sso-divider"><span>安全说明</span></div>
        <div class="sso-note">
          • 登录采用浏览器重定向 + JWT Token + 后端 Thrift 校验<br />
          • 回跳后 token 仅存于 localStorage，并立即清理 URL<br />
          • 鉴权失效（20000~20005）将自动清除并跳登录
        </div>
      </div>
      <div class="right-foot">
        © 2026 Albedo · 参考阿里云百炼 / 腾讯云 ADP 设计原型
      </div>
    </section>
  </div>
</template>

<style scoped>
.login {
  height: 100%;
  display: grid;
  grid-template-columns: 1.05fr 1fr;
}
.left {
  background: var(--brand-grad);
  position: relative;
  overflow: hidden;
  color: #fff;
}
.left::before {
  content: "";
  position: absolute;
  width: 520px;
  height: 520px;
  right: -160px;
  top: -160px;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.18), transparent 70%);
}
.left::after {
  content: "";
  position: absolute;
  width: 420px;
  height: 420px;
  left: -120px;
  bottom: -140px;
  background: radial-gradient(circle, rgba(255, 255, 255, 0.12), transparent 70%);
}
.left-inner {
  position: relative;
  z-index: 1;
  padding: 64px;
  display: flex;
  flex-direction: column;
  height: 100%;
  justify-content: center;
  max-width: 560px;
}
.brand-row {
  display: flex;
  align-items: center;
  gap: 13px;
  margin-bottom: 56px;
}
.logo {
  width: 46px;
  height: 46px;
  border-radius: 13px;
  background: rgba(255, 255, 255, 0.18);
  border: 1px solid rgba(255, 255, 255, 0.3);
  font-weight: 800;
  font-size: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.logo-name {
  font-size: 20px;
  font-weight: 800;
}
.logo-sub {
  font-size: 12.5px;
  opacity: 0.85;
}
h1 {
  font-size: 34px;
  line-height: 1.3;
  font-weight: 800;
  margin: 0 0 18px;
  letter-spacing: 0.5px;
}
.lede {
  font-size: 15px;
  line-height: 1.7;
  opacity: 0.92;
  margin: 0 0 28px;
}
.feats {
  list-style: none;
  padding: 0;
  margin: 0;
  display: grid;
  gap: 12px;
}
.feats li {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14.5px;
}
.ck {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.2);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  flex: none;
}
.left-foot {
  margin-top: 48px;
  font-size: 12.5px;
  opacity: 0.7;
}
.right {
  background: var(--surface);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
}
.sso-card {
  width: 380px;
}
.sso-badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--brand-700);
  background: var(--brand-soft);
  border-radius: 999px;
  padding: 6px 12px;
  margin-bottom: 22px;
}
.eye {
  font-size: 14px;
}
h2 {
  font-size: 22px;
  font-weight: 800;
  margin: 0 0 8px;
}
.sso-tip {
  color: var(--text-3);
  font-size: 13.5px;
  margin: 0 0 26px;
  line-height: 1.6;
}
.login-btn {
  width: 100%;
  height: 42px;
  font-size: 15px;
  margin-top: 8px;
}
.err {
  color: var(--red);
  font-size: 13px;
  margin-top: 14px;
}
.sso-divider {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--text-4);
  font-size: 12px;
  margin: 24px 0 12px;
}
.sso-divider::before,
.sso-divider::after {
  content: "";
  flex: 1;
  height: 1px;
  background: var(--border);
}
.sso-note {
  font-size: 12px;
  color: var(--text-3);
  line-height: 1.7;
  background: var(--surface-2);
  border-radius: 10px;
  padding: 12px 14px;
}
.right-foot {
  position: absolute;
  bottom: 24px;
  font-size: 12px;
  color: var(--text-4);
}
</style>
