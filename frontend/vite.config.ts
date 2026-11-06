import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = path.dirname(fileURLToPath(import.meta.url))

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, projectRoot, '')
  const backendOrigin = env.ALBEDO_BACKEND_ORIGIN || process.env.ALBEDO_BACKEND_ORIGIN || 'http://localhost:8000'
  return {
    plugins: [react()],
    resolve: {
      alias: { '@': path.resolve(projectRoot, 'src') },
    },
    server: {
      host: '0.0.0.0',
      port: 5173,
      allowedHosts: true,
      proxy: {
        '/api': { target: backendOrigin, changeOrigin: true },
        '/v1': { target: backendOrigin, changeOrigin: true },
      },
    },
    build: {
      target: 'es2020',
      chunkSizeWarningLimit: 1200,
    },
  }
})
