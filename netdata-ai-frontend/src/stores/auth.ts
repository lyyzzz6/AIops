import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import apiClient from '@/api'
import router from '@/router'

export interface UserInfo {
  id: number
  username: string
  nickname: string
  email: string
  avatar: string
  roles: string[]
  permissions: string[]
}

export interface TokenInfo {
  accessToken: string
  refreshToken: string
  expiresIn: number
}

export const useAuthStore = defineStore('auth', () => {
  // State
  const token = ref<string>(localStorage.getItem('access_token') || '')
  const refreshToken = ref<string>(localStorage.getItem('refresh_token') || '')
  const user = ref<UserInfo | null>(null)

  // Getters
  const isAuthenticated = computed(() => !!token.value)
  const username = computed(() => user.value?.username || '')
  const roles = computed(() => user.value?.roles || [])
  const permissions = computed(() => user.value?.permissions || [])

  const hasRole = (role: string) => roles.value.includes(role)
  const hasPermission = (permission: string) => {
    // SUPER_ADMIN bypass
    if (roles.value.includes('SUPER_ADMIN')) return true
    return permissions.value.includes(permission)
  }

  // Actions
  async function login(username: string, password: string) {
    const res: any = await apiClient.post('/auth/login', { username, password })
    const data = res.data

    token.value = data.accessToken
    refreshToken.value = data.refreshToken
    localStorage.setItem('access_token', data.accessToken)
    localStorage.setItem('refresh_token', data.refreshToken)

    // 获取用户信息
    await fetchUserInfo()
  }

  async function fetchUserInfo() {
    try {
      const res: any = await apiClient.get('/auth/me')
      user.value = res.data
    } catch (e) {
      console.error('获取用户信息失败', e)
    }
  }

  async function refreshAccessToken() {
    try {
      const res: any = await apiClient.post('/auth/refresh', {
        refreshToken: refreshToken.value,
      })
      const data = res.data
      token.value = data.accessToken
      refreshToken.value = data.refreshToken
      localStorage.setItem('access_token', data.accessToken)
      localStorage.setItem('refresh_token', data.refreshToken)
      return true
    } catch (e) {
      logout()
      return false
    }
  }

  async function logout() {
    try {
      await apiClient.post('/auth/logout')
    } catch (e) {
      // ignore
    }
    token.value = ''
    refreshToken.value = ''
    user.value = null
    localStorage.removeItem('access_token')
    localStorage.removeItem('refresh_token')
    router.push('/login')
  }

  // 初始化：如果有token则获取用户信息
  function init() {
    if (token.value) {
      fetchUserInfo()
    }
  }

  return {
    token,
    refreshToken,
    user,
    isAuthenticated,
    username,
    roles,
    permissions,
    hasRole,
    hasPermission,
    login,
    fetchUserInfo,
    refreshAccessToken,
    logout,
    init,
  }
})
