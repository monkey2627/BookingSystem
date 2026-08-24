import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

// vite.config.ts 的核心作用：
//   1. 注册 Vue 插件，让 Vite 能识别 .vue 文件
//   2. 配置 @ 路径别名，代码里写 @/utils/request 等价于 src/utils/request
//   3. 配置开发代理，解决跨域问题

export default defineConfig({
  plugins: [vue()],

  resolve: {
    alias: {
      // @ 指向 src 目录，避免写 ../../utils/request 这种相对路径
      '@': resolve(__dirname, 'src')
    }
  },

  server: {
    port: 5173,
    proxy: {
      // 开发时所有 /api 开头的请求由 Vite 代理转发给后端
      // 浏览器看到的是 localhost:5173/api/...，不知道有跨域，绕过同源策略
      // 生产环境由 Nginx 做同样的事（location /api/ → proxy_pass http://localhost:8081）
      '/api': {
        target: 'http://localhost:80',
        changeOrigin: true  // 修改请求头中的 Host 为 target，避免后端 vhost 校验失败
      }
    }
  }
})
