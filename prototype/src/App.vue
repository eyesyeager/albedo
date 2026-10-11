<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import AppSidebar from "@/components/AppSidebar.vue";
import AppTopbar from "@/components/AppTopbar.vue";

const route = useRoute();
const router = useRouter();
const isLogin = computed(() => route.name === "login");

function toLogin() {
  router.push("/login");
}
</script>

<template>
  <div v-if="isLogin" class="login-shell">
    <router-view />
  </div>
  <div v-else class="shell">
    <AppSidebar />
    <div class="shell-main">
      <AppTopbar />
      <main class="shell-content">
        <router-view v-slot="{ Component }">
          <transition name="page-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script lang="ts">
export default { name: "App" };
</script>

<style scoped>
.login-shell {
  height: 100%;
}
.shell {
  display: flex;
  height: 100%;
}
.shell-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.shell-content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 24px 28px 40px;
}
</style>
