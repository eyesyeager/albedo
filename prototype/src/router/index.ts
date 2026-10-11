import { createRouter, createWebHashHistory } from "vue-router";

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: "/", redirect: "/overview" },
    {
      path: "/login",
      name: "login",
      component: () => import("@/views/Login.vue"),
      meta: { public: true },
    },
    {
      path: "/overview",
      name: "overview",
      component: () => import("@/views/Overview.vue"),
    },
    {
      path: "/apps",
      name: "apps",
      component: () => import("@/views/apps/AppList.vue"),
    },
    {
      path: "/apps/:id",
      name: "app-detail",
      component: () => import("@/views/apps/AppDetail.vue"),
    },
    {
      path: "/models",
      name: "models",
      component: () => import("@/views/models/ModelList.vue"),
    },
    {
      path: "/knowledge",
      name: "knowledge",
      component: () => import("@/views/knowledge/KBList.vue"),
    },
    {
      path: "/knowledge/:id",
      name: "kb-detail",
      component: () => import("@/views/knowledge/KBDetail.vue"),
    },
    {
      path: "/mcp",
      name: "mcp",
      component: () => import("@/views/mcp/MCPList.vue"),
    },
    {
      path: "/mcp/:id",
      name: "mcp-detail",
      component: () => import("@/views/mcp/MCPDetail.vue"),
    },
    {
      path: "/skills",
      name: "skills",
      component: () => import("@/views/skills/SkillList.vue"),
    },
    {
      path: "/skills/:id",
      name: "skillDetail",
      component: () => import("@/views/skills/SkillDetail.vue"),
    },
    {
      path: "/logs",
      name: "logs",
      component: () => import("@/views/logs/OperationLogs.vue"),
    },
  ],
});

router.beforeEach((to) => {
  const token = localStorage.getItem("authorization");
  if (!to.meta.public && !token) {
    return { path: "/login", query: { redirect: to.fullPath } };
  }
  if (to.path === "/login" && token) {
    return { path: "/overview" };
  }
});

export default router;
