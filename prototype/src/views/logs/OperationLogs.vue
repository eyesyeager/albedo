<script setup lang="ts">
import { ref } from "vue";
import { operationLogs } from "@/data/mock";
import type { OperationLog } from "@/types";
import StatusTag from "@/components/StatusTag.vue";

const logs = ref<OperationLog[]>([...operationLogs]);
const module = ref("全部");
const result = ref("全部");

const modules = ["全部", "应用管理", "模型管理", "知识库", "MCP", "操作日志"];
const filtered = () =>
  logs.value.filter(
    (l) =>
      (module.value === "全部" || l.module === module.value) &&
      (result.value === "全部" || (result.value === "成功" ? l.result === "success" : l.result === "fail"))
  );
</script>

<template>
  <div class="page">
    <div class="head">
      <div>
        <h1 class="title">操作日志</h1>
        <p class="sub">记录关键操作以满足审计与追溯需要，持久化留存并支持导出</p>
      </div>
      <button class="btn">导出日志</button>
    </div>

    <div class="filters card">
      <div class="seg">
        <button
          v-for="m in modules"
          :key="m"
          :class="{ on: module === m }"
          @click="module = m"
        >{{ m }}</button>
      </div>
      <div class="seg">
        <button :class="{ on: result === '全部' }" @click="result = '全部'">全部</button>
        <button :class="{ on: result === '成功' }" @click="result = '成功'">成功</button>
        <button :class="{ on: result === '失败' }" @click="result = '失败'">失败</button>
      </div>
      <div class="search">
        <input class="field" placeholder="搜索操作人 / 对象…" />
      </div>
    </div>

    <div class="card">
      <table class="tbl">
        <thead>
          <tr>
            <th>时间</th><th>操作人</th><th>模块</th><th>操作</th>
            <th>对象</th><th>结果</th><th>IP</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="l in filtered()" :key="l.id">
            <td class="mono muted">{{ l.createdAt }}</td>
            <td><b>{{ l.operator }}</b></td>
            <td><span class="chip">{{ l.module }}</span></td>
            <td>{{ l.action }}</td>
            <td>{{ l.target }}</td>
            <td>
              <StatusTag
                :color="l.result === 'success' ? 'var(--green)' : 'var(--red)'"
                :bg="l.result === 'success' ? 'var(--green-soft)' : 'var(--red-soft)'"
                dot
              >{{ l.result === "success" ? "成功" : "失败" }}</StatusTag>
            </td>
            <td class="mono muted">{{ l.ip }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="!filtered().length" class="empty">无匹配日志</div>
    </div>
  </div>
</template>

<style scoped>
.page {
  max-width: 1240px;
  margin: 0 auto;
}
.head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
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
.filters {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.search {
  margin-left: auto;
  width: 240px;
}
.search .field {
  height: 34px;
}
.chip {
  font-size: 12px;
  color: var(--text-2);
  background: var(--surface-2);
  border: 1px solid var(--border);
  border-radius: 999px;
  padding: 3px 10px;
}
</style>
