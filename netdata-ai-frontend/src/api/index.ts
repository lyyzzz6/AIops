import axios from 'axios'
import type { ChatRequest, ChatResponse } from '@/types'
import { ElMessage } from 'element-plus'

/**
 * API 客户端配置
 */
const apiClient = axios.create({
  baseURL: '/api/v1',
  timeout: 60000,
  headers: {
    'Content-Type': 'application/json',
  },
})

// 是否正在刷新token
let isRefreshing = false
let refreshSubscribers: ((token: string) => void)[] = []

function onTokenRefreshed(token: string) {
  refreshSubscribers.forEach((cb) => cb(token))
  refreshSubscribers = []
}

function addRefreshSubscriber(cb: (token: string) => void) {
  refreshSubscribers.push(cb)
}

// 请求拦截器：自动附加 JWT Token
apiClient.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('access_token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => {
    return Promise.reject(error)
  }
)

// 响应拦截器：统一错误处理 + 401 自动刷新
apiClient.interceptors.response.use(
  (response) => {
    return response.data
  },
  async (error) => {
    const originalRequest = error.config

    // 401 未认证 - 尝试刷新token
    if (error.response?.status === 401 && !originalRequest._retry) {
      if (originalRequest.url?.includes('/auth/login') || originalRequest.url?.includes('/auth/refresh')) {
        return Promise.reject(error)
      }

      originalRequest._retry = true

      if (!isRefreshing) {
        isRefreshing = true
        const refreshTokenStr = localStorage.getItem('refresh_token')

        if (!refreshTokenStr) {
          redirectToLogin()
          return Promise.reject(error)
        }

        try {
          const res = await axios.post('/api/v1/auth/refresh', { refreshToken: refreshTokenStr })
          const { accessToken, refreshToken } = res.data.data
          localStorage.setItem('access_token', accessToken)
          localStorage.setItem('refresh_token', refreshToken)
          isRefreshing = false
          onTokenRefreshed(accessToken)

          originalRequest.headers.Authorization = `Bearer ${accessToken}`
          return apiClient(originalRequest)
        } catch (refreshError) {
          isRefreshing = false
          redirectToLogin()
          return Promise.reject(refreshError)
        }
      } else {
        // 等待token刷新完成后重试
        return new Promise((resolve) => {
          addRefreshSubscriber((newToken: string) => {
            originalRequest.headers.Authorization = `Bearer ${newToken}`
            resolve(apiClient(originalRequest))
          })
        })
      }
    }

    // 403 权限不足
    if (error.response?.status === 403) {
      ElMessage.error('权限不足，无法执行此操作')
    }

    // 429 限流
    if (error.response?.status === 429) {
      ElMessage.warning('请求过于频繁，请稍后再试')
    }

    // 其他错误
    const message = error.response?.data?.message || error.message || '请求失败'
    if (error.response?.status !== 401) {
      ElMessage.error(message)
    }

    return Promise.reject(error)
  }
)

function redirectToLogin() {
  localStorage.removeItem('access_token')
  localStorage.removeItem('refresh_token')
  window.location.href = '/login'
}

/**
 * 聊天 API
 */
export const chatApi = {
  /**
   * 发送消息
   */
  async sendMessage(request: ChatRequest): Promise<ChatResponse> {
    return apiClient.post('/chat', request)
  },

  /**
   * 流式对话（SSE）
   * 注意：当前版本使用普通请求，后续可升级为 SSE
   */
  async *sendMessageStream(
    request: ChatRequest,
    onMessage: (chunk: string) => void
  ): AsyncGenerator<string> {
    // 简化实现：使用普通请求
    const response = await this.sendMessage(request)
    onMessage(response.response)
    yield response.response
  },
}

/**
 * 知识库 API
 */
export const knowledgeApi = {
  /**
   * 获取文档列表
   */
  async getDocuments() {
    return apiClient.get('/knowledge/documents')
  },

  /**
   * 上传文档
   */
  async uploadDocument(data: { title: string; source: string; content: string }) {
    return apiClient.post('/knowledge/upload', data)
  },

  /**
   * 删除文档
   */
  async deleteDocument(id: string) {
    return apiClient.delete(`/knowledge/documents/${id}`)
  },
}

/**
 * 告警 API
 */
export const alertApi = {
  /**
   * 获取告警列表
   */
  async getAlerts(params?: { status?: string; severity?: string }) {
    return apiClient.get('/alerts', { params })
  },

  /**
   * 获取告警统计
   */
  async getStats() {
    return apiClient.get('/alerts/stats')
  },
}

/**
 * 审批 API
 */
export const approvalApi = {
  /**
   * 获取待审批列表
   */
  async getPendingApprovals() {
    return apiClient.get('/approvals/pending')
  },

  /**
   * 审批通过
   */
  async approve(id: string) {
    return apiClient.post(`/approvals/${id}/approve`)
  },

  /**
   * 审批拒绝
   */
  async reject(id: string, reason: string) {
    return apiClient.post(`/approvals/${id}/reject`, { reason })
  },
}

/**
 * 认证 API
 */
export const authApi = {
  async login(username: string, password: string) {
    return apiClient.post('/auth/login', { username, password })
  },
  async logout() {
    return apiClient.post('/auth/logout')
  },
  async refresh(refreshToken: string) {
    return apiClient.post('/auth/refresh', { refreshToken })
  },
  async me() {
    return apiClient.get('/auth/me')
  },
}

/**
 * 用户管理 API
 */
export const userApi = {
  async getUsers(params?: { current?: number; size?: number; keyword?: string }) {
    return apiClient.get('/users', { params })
  },
  async createUser(data: { username: string; password: string; nickname: string; email?: string; roleIds?: number[] }) {
    return apiClient.post('/users', data)
  },
  async updateUser(id: number, data: { nickname?: string; email?: string; status?: number }) {
    return apiClient.put(`/users/${id}`, data)
  },
  async deleteUser(id: number) {
    return apiClient.delete(`/users/${id}`)
  },
  async resetPassword(id: number, newPassword: string) {
    return apiClient.put(`/users/${id}/reset-password`, { newPassword })
  },
  async assignRoles(id: number, roleIds: number[]) {
    return apiClient.put(`/users/${id}/roles`, { roleIds })
  },
}

/**
 * 角色管理 API
 */
export const roleApi = {
  async getRoles() {
    return apiClient.get('/roles')
  },
  async createRole(data: { roleCode: string; roleName: string; description?: string }) {
    return apiClient.post('/roles', data)
  },
  async assignPermissions(roleId: number, permissionIds: number[]) {
    return apiClient.put(`/roles/${roleId}/permissions`, { permissionIds })
  },
  async getPermissions() {
    return apiClient.get('/roles/permissions')
  },
}

/**
 * 系统 API
 */
export const systemApi = {
  /**
   * 健康检查
   */
  async health() {
    return apiClient.get('/health')
  },
}

export default apiClient
