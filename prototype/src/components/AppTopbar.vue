<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAuth } from "@/store/auth";

const route = useRoute();
const router = useRouter();
const { user, logout } = useAuth();

const titleMap: Record<string, string> = {
  overview: "总览",
  apps: "应用管理",
  appDetail: "应用详情",
  models: "模型管理",
  knowledge: "知识库管理",
  kbDetail: "知识库详情",
  mcp: "MCP 管理",
  mcpDetail: "MCP 详情",
  skills: "Skill 管理",
  logs: "操作日志",
};
const title = computed(() => titleMap[route.name as string] || "Albedo");

function onLogout() {
  logout();
  router.push("/login");
}
</script>

<template>
  <header class="top">
    <div class="crumbs">
      <span class="crumb-root">Albedo</span>
      <span class="crumb-sep">/</span>
      <span class="crumb-cur">{{ title }}</span>
    </div>

    <div class="top-right">
      <div class="search">
        <svg viewBox="0 0 24 24" class="search-ico">
          <circle cx="11" cy="11" r="7" stroke="currentColor" stroke-width="1.6" fill="none" />
          <path d="M20 20l-3.2-3.2" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
        </svg>
        <input placeholder="搜索应用、知识库、模型…" />
        <kbd>⌘K</kbd>
      </div>

      <button class="icon-btn" title="帮助文档">
        <svg viewBox="0 0 24 24" width="18" height="18">
          <circle cx="12" cy="12" r="9" stroke="currentColor" stroke-width="1.6" fill="none" />
          <path d="M9.5 9a2.5 2.5 0 1 1 3.5 2.3c-.8.5-1 .9-1 1.7" stroke="currentColor" stroke-width="1.6" fill="none" stroke-linecap="round" />
          <circle cx="12" cy="17" r="1" fill="currentColor" />
        </svg>
      </button>

      <div class="user" @click="onLogout" title="点击退出登录">
        <div class="avatar" :style="{ background: user?.avatarColor }">
          {{ user?.name?.[0] }}
        </div>
        <div class="user-meta">
          <div class="user-name">{{ user?.name }}</div>
          <div class="user-role">eyesUser · {{ user?.role }}</div>
        </div>
        <svg viewBox="0 0 24 24" width="16" height="16" class="caret">
          <path d="M7 10l5 5 5-5" stroke="currentColor" stroke-width="1.6" fill="none" stroke-linecap="round" />
        </svg>
      </div>
    </div>
  </header>
</template>

<style scoped>
.top {
  height: var(--topbar-h);
  flex: none;
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  position: sticky;
  top: 0;
  z-index: 20;
}
.crumbs {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
}
.crumb-root {
  color: var(--text-3);
  font-weight: 600;
}
.crumb-sep {
  color: var(--text-4);
}
.crumb-cur {
  color: var(--text-1);
  font-weight: 700;
}
.top-right {
  display: flex;
  align-items: center;
  gap: 14px;
}
.search {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 10px 0 12px;
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: 10px;
  width: 260px;
  color: var(--text-3);
}
.search-ico {
  width: 17px;
  height: 17px;
  flex: none;
}
.search input {
  border: none;
  background: transparent;
  outline: none;
  flex: 1;
  color: var(--text-1);
  font-size: 13px;
}
.search kbd {
  font-size: 11px;
  color: var(--text-4);
  border: 1px solid var(--border-strong);
  border-radius: 5px;
  padding: 1px 5px;
  font-family: var(--mono);
}
.icon-btn {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--text-2);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
}
.icon-btn:hover {
  color: var(--brand);
  border-color: var(--brand);
  background: var(--brand-soft-2);
}
.user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 5px 10px 5px 6px;
  border-radius: 12px;
  cursor: pointer;
  transition: background 0.15s;
}
.user:hover {
  background: var(--surface-2);
}
.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  color: #fff;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}
.user-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--text-1);
  line-height: 1.2;
}
.user-role {
  font-size: 11.5px;
  color: var(--text-3);
}
.caret {
  color: var(--text-4);
}
</style>
