import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [
    vue(),
    {
      name: 'local-chat-request-size',
      configureServer(server) {
        // GET 编码后的 4000 个 Unicode 字符也能到达后端长度校验。
        if (server.httpServer) server.httpServer.maxHeaderSize = 64 * 1024
      },
      configurePreviewServer(server) {
        server.httpServer.maxHeaderSize = 64 * 1024
      },
    },
  ],
  server: {
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8123',
        changeOrigin: true,
      },
    },
  },
  preview: {
    host: '127.0.0.1',
    port: 4173,
    strictPort: true,
  },
})
