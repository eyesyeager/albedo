import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

/**
 * Vite 配置。
 *
 * 关键点：
 * 1. 产物为纯静态 html/css/js，可直接由 Nginx / 任意静态托管部署（架构红线）。
 * 2. dev server 开放 host 并允许任意 Host 访问，配合后端 `sys_config: tenant.dev_host_mapping`
 *    完成本地多租户验证（如把 localhost:5173 映射为 gift）。
 * 3. dev proxy 透传原始 Host（changeOrigin: false），使后端 TenantFilter 拿到与浏览器一致的 Host。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '0.0.0.0',
    port: 5173,
    strictPort: false,
    // 允许通过任意域名访问 dev server（本地租户 Host 验证需要）
    allowedHosts: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: false, // 🔴 保留浏览器原始 Host，租户识别依赖它
        ws: false,
      },
      '/site': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: false,
        ws: false,
      },
    },
  },
  build: {
    target: 'es2020',
    sourcemap: false,
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        // 仅固定框架层分包；业务与三方库（Element Plus / markdown-it 等）
        // 由 @前端 在实现阶段按路由懒加载自然分包，避免产生空 chunk
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    include: ['tests/unit/**/*.spec.ts'],
    globals: true,
  },
})
