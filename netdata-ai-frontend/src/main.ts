import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
import 'highlight.js/styles/github-dark.css'

import App from './App.vue'
import router from './router'
import { setupPermissionDirectives } from './directives/permission'
import { useAuthStore } from './stores/auth'

import './assets/main.scss'

const app = createApp(App)
const pinia = createPinia()

// 注册 Element Plus 图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(pinia)
app.use(router)
app.use(ElementPlus)

// 注册权限指令
setupPermissionDirectives(app)

// 初始化认证状态
const authStore = useAuthStore()
authStore.init()

app.mount('#app')
