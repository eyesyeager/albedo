<script setup lang="ts">
import { skills } from "@/data/mock";
</script>

<template>
  <div class="page">
    <div class="head">
      <div>
        <h1 class="title">Skill 管理</h1>
        <p class="sub">Skill 为官方预打包的 MCP 能力推荐模板（本期不单独建模块，仅提供预置模板）</p>
      </div>
    </div>

    <div class="banner card">
      <span class="banner-ico">ℹ️</span>
      <span>
        Skill 是 MCP 的上层封装模板。应用装配工具时可直接选用以下预置能力；自定义技能包将在后续迭代支持。
      </span>
    </div>

    <div class="grid">
      <div v-for="s in skills" :key="s.id" class="skill-card card">
        <div class="skill-top">
          <div class="skill-ico">{{ s.icon }}</div>
          <span class="tag preset">预置</span>
        </div>
        <div class="skill-name">{{ s.name }}</div>
        <div class="skill-desc">{{ s.desc }}</div>
        <div class="skill-foot">
          <span class="chip">MCP · {{ s.mcp }}</span>
          <button class="btn btn-xs">装配到应用</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  max-width: 1240px;
  margin: 0 auto;
}
.head {
  margin-bottom: 18px;
}
.title {
  font-size: 24px;
  font-weight: 800;
  margin: 0 0 4px;
}
.sub {
  color: var(--text-3);
  font-size: 13.5px;
  margin: 0;
}
.banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 16px;
  background: var(--brand-soft-2);
  border: 1px solid var(--brand-soft);
  color: var(--text-2);
  font-size: 13px;
  margin-bottom: 16px;
}
.banner-ico {
  font-size: 16px;
}
.grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}
.skill-card {
  padding: 18px;
  transition: all 0.18s;
}
.skill-card:hover {
  box-shadow: var(--sh-md);
  transform: translateY(-3px);
}
.skill-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.skill-ico {
  width: 42px;
  height: 42px;
  border-radius: 12px;
  background: var(--violet-soft);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
}
.preset {
  color: var(--violet);
  background: var(--violet-soft);
}
.skill-name {
  font-size: 16px;
  font-weight: 800;
  margin-top: 14px;
}
.skill-desc {
  font-size: 12.5px;
  color: var(--text-3);
  line-height: 1.55;
  margin-top: 6px;
  min-height: 42px;
}
.skill-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 14px;
  gap: 8px;
}
.chip {
  font-size: 11.5px;
  color: var(--text-2);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 3px 9px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
