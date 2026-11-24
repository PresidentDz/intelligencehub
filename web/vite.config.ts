import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  return {
    plugins: [vue()],
    server: {
      port: 5173,
      // In development the browser talks to Vite only; /api is forwarded to Spring Boot,
      // so the session cookie stays same-origin.
      proxy: { '/api': { target: env.KB_API_TARGET || 'http://localhost:8765' } },
    },
  }
})
