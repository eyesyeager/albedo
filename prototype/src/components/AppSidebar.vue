<script setup lang="ts">
import { useRoute, useRouter } from "vue-router";

const route = useRoute();
const router = useRouter();

const nav = [
  {
    name: "总览",
    path: "/overview",
    icon: "M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z",
  },
  {
    name: "应用管理",
    path: "/apps",
    icon: "M12 2a2 2 0 0 1 2 2v1.1A8 8 0 0 1 19.9 11H22a2 2 0 0 1 0 4h-2.1A8 8 0 0 1 14 20.9V22a2 2 0 0 1-4 0v-1.1A8 8 0 0 1 4.1 15H2a2 2 0 0 1 0-4h2.1A8 8 0 0 1 10 5.1V4a2 2 0 0 1 2-2zm0 5a5 5 0 1 0 0 10 5 5 0 0 0 0-10z",
  },
  {
    name: "模型管理",
    path: "/models",
    icon: "M12 2 2 7l10 5 10-5-10-5zM2 17l10 5 10-5M2 12l10 5 10-5",
  },
  {
    name: "知识库管理",
    path: "/knowledge",
    icon: "M4 4h13a2 2 0 0 1 2 2v14H6a2 2 0 0 1-2-2V4zm4 4h7M8 12h7M8 16h5",
  },
  {
    name: "检索引擎配置",
    path: "/storage",
    icon: "M12 3c4.97 0 9 1.34 9 3s-4.03 3-9 3-9-1.34-9-3 4.03-3 9-3zM3 6v12c0 1.66 4.03 3 9 3s9-1.34 9-3V6M3 12c0 1.66 4.03 3 9 3s9-1.34 9-3",
  },
  {
    name: "MCP 管理",
    path: "/mcp",
    icon: "M9 3v3M15 3v3M9 18v3M15 18v3M3 9h3M3 15h3M18 9h3M18 15h3M6 6h12v12H6z",
  },
  {
    name: "Skill 管理",
    path: "/skills",
    icon: "M12 2l2.4 7.4H22l-6 4.4 2.3 7.2L12 17l-6.3 4 2.3-7.2-6-4.4h7.6L12 2z",
  },
  {
    name: "操作日志",
    path: "/logs",
    icon: "M3 3h18v4H3V3zm0 7h18v11H3V10zm6 3v5M11 13v5M15 13v5",
  },
];

function active(path: string) {
  if (path === "/apps" && route.path.startsWith("/apps")) return true;
  if (path === "/knowledge" && route.path.startsWith("/knowledge")) return true;
  if (path === "/storage" && route.path.startsWith("/storage")) return true;
  if (path === "/mcp" && route.path.startsWith("/mcp")) return true;
  if (path === "/skills" && route.path.startsWith("/skills")) return true;
  return route.path === path;
}
</script>

<template>
  <aside class="side">
    <div class="brand" @click="router.push('/overview')">
      <div class="brand-logo">A</div>
      <div class="brand-text">
        <div class="brand-name">Albedo</div>
        <div class="brand-sub">智能体开发平台</div>
      </div>
    </div>

    <nav class="nav">
      <button
        v-for="n in nav"
        :key="n.path"
        class="nav-item"
        :class="{ on: active(n.path) }"
        @click="router.push(n.path)"
      >
        <svg viewBox="0 0 24 24" class="nav-ico" fill="none">
          <path
            :d="n.icon"
            stroke="currentColor"
            stroke-width="1.6"
            stroke-linecap="round"
            stroke-linejoin="round"
          />
        </svg>
        <span>{{ n.name }}</span>
      </button>
    </nav>

    <div class="side-foot">
      <div class="plan">
        <div class="plan-title">MVP 版本</div>
        <div class="plan-desc">v0.3 · AgentOps</div>
      </div>
    </div>
  </aside>
</template>

<style scoped>
.side {
  width: var(--sidebar-w);
  flex: none;
  background: linear-gradient(180deg, #1b1c33 0%, #16172b 100%);
  color: #c8cae0;
  display: flex;
  flex-direction: column;
  padding: 16px 14px;
}
.brand {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 8px 10px 18px;
  cursor: pointer;
}
.brand-logo {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: var(--brand-grad);
  color: #fff;
  font-weight: 800;
  font-size: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 14px rgba(99, 102, 241, 0.45);
}
.brand-name {
  font-size: 16px;
  font-weight: 800;
  color: #fff;
  letter-spacing: 0.5px;
}
.brand-sub {
  font-size: 11px;
  color: #8b8dab;
}
.nav {
  display: flex;
  flex-direction: column;
  gap: 3px;
  margin-top: 6px;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 11px;
  height: 42px;
  padding: 0 12px;
  border: none;
  background: transparent;
  color: #b6b8d4;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 500;
  transition: all 0.15s;
  text-align: left;
}
.nav-item:hover {
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
}
.nav-item.on {
  background: var(--brand-grad);
  color: #fff;
  box-shadow: 0 4px 14px rgba(99, 102, 241, 0.4);
}
.nav-ico {
  width: 19px;
  height: 19px;
  flex: none;
}
.side-foot {
  margin-top: auto;
  padding: 12px 10px 4px;
}
.plan {
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 10px;
  padding: 11px 13px;
}
.plan-title {
  font-size: 13px;
  font-weight: 700;
  color: #fff;
}
.plan-desc {
  font-size: 11.5px;
  color: #8b8dab;
  margin-top: 2px;
}
</style>
