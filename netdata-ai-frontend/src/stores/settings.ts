import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 应用设置状态管理
 */
export const useSettingsStore = defineStore('settings', () => {
  // 主题
  const theme = ref<'light' | 'dark'>('light')
  
  // 侧边栏是否折叠
  const sidebarCollapsed = ref(false)
  
  // 切换主题
  function toggleTheme() {
    theme.value = theme.value === 'light' ? 'dark' : 'light'
    document.documentElement.setAttribute('data-theme', theme.value)
  }
  
  // 切换侧边栏
  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }
  
  return {
    theme,
    sidebarCollapsed,
    toggleTheme,
    toggleSidebar,
  }
})
