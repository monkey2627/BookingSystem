import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

import App from './App.vue'
import router from './router'

const app = createApp(App)

// Pinia：Vue 3 的状态管理库，替代 Vuex
// 特点：更简单（不需要 mutations）、TypeScript 支持更好、支持 DevTools
app.use(createPinia())

// Vue Router：页面路由，决定 URL 对应哪个组件
app.use(router)

// Element Plus：阿里出品的 Vue 3 UI 组件库
// locale: zhCn 把所有内置文本（日期选择器、分页等）设为中文
app.use(ElementPlus, { locale: zhCn })

// 全局注册所有 Element Plus 图标
// 这样在模板里可以直接写 <el-icon><Search /></el-icon>
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.mount('#app')
