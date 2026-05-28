import axios from 'axios'
import type {
  ChatRequest,
  ChatResponse,
  ChatConversationDTO,
  ChatMessageDTO,
  PageResult,
  SysRoleDTO,
  SysPermissionDTO,
  OperationLogDTO,
  PermissionRequestDTO,
  ExecutionAuditDTO,
  Document,
} from '@/types'
import { ElMessage } from 'element-plus'
import { streamRequest } from './sse-client'

/**
 * API 客户端配置
 *
 * 响应拦截器约定：
 * - 后端返回 `R<T>`：拦截器原样返回整个对象，上层通过 `.data` 取出真正数据。
 * - 后端直接返回 plain 对象（如 /chat）：拦截器返回该对象本身。
 *
 * 为减少视图层样板代码，本文件封装方法统一 `.then((res) => res.data ?? res)` 解包，
 * 返回给调用方的就是业务数据本身。
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
 * 从 R<T> 或 plain 响应中统一解包 data
 */
function unwrap<T>(res: any): T {
  if (res && typeof res === 'object' && 'code' in res && 'data' in res) {
    return res.data as T
  }
  return res as T
}

/**
 * 聊天 API
 */
export const chatApi = {
  /**
   * 发送消息（/chat 返回 plain 对象，非 R<T>）
   */
  async sendMessage(request: ChatRequest): Promise<ChatResponse> {
    const res = await apiClient.post('/chat', request)
    return res as unknown as ChatResponse
  },

  /**
   * 发送消息—流式输出（SSE）
   * - 逐段 onDelta 下发纯文本；
   * - onThinking 下发模型思考过程；
   * - 流结束时 onEnd 下发一条 ChatResponse（不包含 response 字段，由调用方自行累加 delta）。
   */
  async sendMessageStream(
    request: ChatRequest,
    callbacks: {
      onDelta: (delta: string) => void
      onThinking?: (thinking: string) => void
      onEnd?: (payload: Partial<Omit<ChatResponse, 'response'>>) => void
      onError?: (err: Error) => void
      signal?: AbortSignal
    }
  ): Promise<void> {
    // 使用新的 SSE 客户端
    await streamRequest({
      url: '/api/v1/chat/stream',
      method: 'POST',
      body: request,
      timeout: 120000,
      onChunk: callbacks.onDelta,
      onThinking: callbacks.onThinking,
      onComplete: (data) => {
        // 后端返回的数据优先，如果没有则使用默认值
        const payload = data || {
          success: true,
          sources: [],
          intent: 'UNKNOWN',
          suggestedCommands: [],
          executionTimeMs: 0,
        }
        callbacks.onEnd?.(payload as Partial<Omit<ChatResponse, 'response'>>)
      },
      onError: callbacks.onError,
      signal: callbacks.signal,
    })
  },

  /**
   * 分页获取当前用户的会话列表
   */
  async getConversations(params: { current?: number; size?: number } = {}): Promise<PageResult<ChatConversationDTO>> {
    const res = await apiClient.get('/chat/conversations', { params })
    return unwrap<PageResult<ChatConversationDTO>>(res)
  },

  /**
   * 获取会话下的所有消息
   */
  async getMessages(conversationId: number): Promise<ChatMessageDTO[]> {
    const res = await apiClient.get(`/chat/conversations/${conversationId}/messages`)
    return unwrap<ChatMessageDTO[]>(res)
  },

  /**
   * 删除会话
   */
  async deleteConversation(conversationId: number): Promise<void> {
    await apiClient.delete(`/chat/conversations/${conversationId}`)
  },

  /**
   * 清空会话消息但保留会话
   */
  async clearMessages(conversationId: number): Promise<void> {
    await apiClient.delete(`/chat/conversations/${conversationId}/messages`)
  },
}

/**
 * 知识库 API
 */
export const knowledgeApi = {
  async getDocuments(params: { current?: number; size?: number; keyword?: string } = {}): Promise<PageResult<Document>> {
    const res = await apiClient.get('/knowledge/documents', { params })
    return unwrap<PageResult<Document>>(res)
  },
  async getDocumentById(id: number | string): Promise<Document> {
    const res = await apiClient.get(`/knowledge/documents/${id}`)
    return unwrap<Document>(res)
  },
  async createDocument(data: { title: string; source: string; contentType?: string; category?: string; content: string }): Promise<Document> {
    const res = await apiClient.post('/knowledge/documents', data)
    return unwrap<Document>(res)
  },
  async deleteDocument(id: string): Promise<void> {
    await apiClient.delete(`/knowledge/documents/${id}`)
  },
}

/**
 * 告警 API
 */
export const alertApi = {
  async getAlerts(params?: { status?: string; severity?: string }) {
    const res = await apiClient.get('/alerts', { params })
    return unwrap(res)
  },
  async getStats() {
    const res = await apiClient.get('/alerts/stats')
    return unwrap(res)
  },
}

/**
 * 权限审批 API（RBAC，对齐 ApprovalController）
 */
export const approvalApi = {
  /** 提交权限申请 */
  async submitRequest(data: {
    requestType: string
    targetUserId?: number
    targetRoleId?: number
    targetPermissionIds?: string
    reason: string
    durationHours?: number
  }): Promise<PermissionRequestDTO> {
    const res = await apiClient.post('/approvals/requests', data)
    return unwrap<PermissionRequestDTO>(res)
  },
  /** 审批通过 */
  async approve(id: number, comment?: string) {
    const res = await apiClient.put(`/approvals/requests/${id}/approve`, { comment })
    return unwrap(res)
  },
  /** 审批拒绝 */
  async reject(id: number, rejectReason: string) {
    const res = await apiClient.put(`/approvals/requests/${id}/reject`, { rejectReason })
    return unwrap(res)
  },
  /** 转交审批 */
  async transfer(id: number, transferToUserId: number, comment?: string) {
    const res = await apiClient.put(`/approvals/requests/${id}/transfer`, { transferToUserId, comment })
    return unwrap(res)
  },
  /** 我的待审批列表 */
  async getPending(params: { current?: number; size?: number } = {}) {
    const res = await apiClient.get('/approvals/pending', { params })
    return unwrap<PageResult<PermissionRequestDTO>>(res)
  },
  /** 我的申请记录 */
  async getMyRequests(params: { current?: number; size?: number; status?: string } = {}) {
    const res = await apiClient.get('/approvals/my-requests', { params })
    return unwrap<PageResult<PermissionRequestDTO>>(res)
  },
  /** 全部审批记录（管理员） */
  async getAll(params: { current?: number; size?: number; status?: string; requestType?: string } = {}) {
    const res = await apiClient.get('/approvals/requests', { params })
    return unwrap<PageResult<PermissionRequestDTO>>(res)
  },
  /** 审批详情含审批流 */
  async getDetail(id: number) {
    const res = await apiClient.get(`/approvals/requests/${id}`)
    return unwrap<Record<string, any>>(res)
  },
  /** 审批统计 */
  async getStats() {
    const res = await apiClient.get('/approvals/stats')
    return unwrap<Record<string, any>>(res)
  },
}

/**
 * 命令执行审计 API（对齐 ExecutionAuditController）
 */
export const executionApi = {
  /** 提交命令执行请求 */
  async submit(data: { command: string; commandType?: string; targetHost?: string }) {
    const res = await apiClient.post('/executions', data)
    return unwrap<ExecutionAuditDTO>(res)
  },
  /** 风险预评估 */
  async riskAssess(command: string) {
    const res = await apiClient.post('/executions/risk-assess', { command })
    return unwrap<{ riskLevel: string; riskScore: number; status: string; commandType: string }>(res)
  },
  /** 审批通过 */
  async approve(id: number) {
    const res = await apiClient.put(`/executions/${id}/approve`)
    return unwrap<ExecutionAuditDTO>(res)
  },
  /** 审批拒绝 */
  async reject(id: number, reason: string) {
    const res = await apiClient.put(`/executions/${id}/reject`, { reason })
    return unwrap<ExecutionAuditDTO>(res)
  },
  /** 记录执行结果 */
  async recordResult(id: number, result: string, success: boolean) {
    const res = await apiClient.put(`/executions/${id}/result`, { result, success })
    return unwrap<ExecutionAuditDTO>(res)
  },
  /** 分页查询审计记录 */
  async list(params: { current?: number; size?: number; status?: string; riskLevel?: string; targetHost?: string } = {}) {
    const res = await apiClient.get('/executions', { params })
    return unwrap<PageResult<ExecutionAuditDTO>>(res)
  },
  /** 执行统计 */
  async getStats() {
    const res = await apiClient.get('/executions/stats')
    return unwrap<Record<string, any>>(res)
  },
}

/**
 * 操作审计日志 API（对齐 OperationLogController）
 */
export const operationLogApi = {
  async list(params: {
    current?: number
    size?: number
    module?: string
    action?: string
    username?: string
    startTime?: string
    endTime?: string
  } = {}) {
    const res = await apiClient.get('/operation-logs', { params })
    return unwrap<PageResult<OperationLogDTO>>(res)
  },
  async getStats() {
    const res = await apiClient.get('/operation-logs/stats')
    return unwrap<Record<string, any>>(res)
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
 * 用户管理 API（对齐 UserController）
 */
export const userApi = {
  async getUsers(params: { current?: number; size?: number; keyword?: string } = {}) {
    const res = await apiClient.get('/users', { params })
    return unwrap(res)
  },
  async getUser(id: number) {
    const res = await apiClient.get(`/users/${id}`)
    return unwrap(res)
  },
  async createUser(data: { username: string; password: string; nickname: string; email?: string; roleIds?: number[] }) {
    const res = await apiClient.post('/users', data)
    return unwrap(res)
  },
  async updateUser(id: number, data: { nickname?: string; email?: string; status?: number }) {
    const res = await apiClient.put(`/users/${id}`, data)
    return unwrap(res)
  },
  async deleteUser(id: number) {
    await apiClient.delete(`/users/${id}`)
  },
  /** 重置其他用户密码（对齐后端 PUT /users/{id}/password） */
  async resetPassword(id: number, newPassword: string) {
    await apiClient.put(`/users/${id}/password`, { newPassword })
  },
  /** 修改自己的密码 */
  async changeOwnPassword(oldPassword: string, newPassword: string) {
    await apiClient.put('/users/me/password', { oldPassword, newPassword })
  },
  async assignRoles(id: number, roleIds: number[]) {
    await apiClient.post(`/users/${id}/roles`, { roleIds })
  },
}

/**
 * 角色管理 API（对齐 RoleController）
 */
export const roleApi = {
  async getRoles(): Promise<SysRoleDTO[]> {
    const res = await apiClient.get('/roles')
    return unwrap<SysRoleDTO[]>(res)
  },
  async getRole(id: number): Promise<SysRoleDTO> {
    const res = await apiClient.get(`/roles/${id}`)
    return unwrap<SysRoleDTO>(res)
  },
  async createRole(data: { roleCode: string; roleName: string; description?: string }) {
    const res = await apiClient.post('/roles', data)
    return unwrap(res)
  },
  async updateRole(id: number, data: Partial<SysRoleDTO>) {
    const res = await apiClient.put(`/roles/${id}`, data)
    return unwrap(res)
  },
  /** 获取某角色的权限列表 */
  async getRolePermissions(roleId: number): Promise<SysPermissionDTO[]> {
    const res = await apiClient.get(`/roles/${roleId}/permissions`)
    return unwrap<SysPermissionDTO[]>(res)
  },
  /** 给角色分配权限 */
  async assignPermissions(roleId: number, permissionIds: number[]) {
    await apiClient.put(`/roles/${roleId}/permissions`, { permissionIds })
  },
  /** 获取系统所有权限（对齐后端 /roles/permissions/all） */
  async getAllPermissions(): Promise<SysPermissionDTO[]> {
    const res = await apiClient.get('/roles/permissions/all')
    return unwrap<SysPermissionDTO[]>(res)
  },
}

/**
 * 系统 API
 */
export const systemApi = {
  async health() {
    return apiClient.get('/health')
  },
}

export default apiClient