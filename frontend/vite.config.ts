import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      // Local development only: the proxy lets the browser use one origin for the Vite UI
      // and /api requests, so backend CORS configuration is unnecessary at this stage.
      // Production CORS depends on whether deployment keeps or separates those origins.
      '/api': {
        target: 'http://localhost:8080',
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    environmentOptions: {
      jsdom: { url: 'http://localhost:5173' },
    },
  },
})
