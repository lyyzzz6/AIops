/**
 * 智能运维系统 - TypeScript 类型定义
 * 
 * @author 刘一舟
 * @since 2026-04-30
 */

// ==================== 认证相关类型 ====================

/** 登录请求 */
export interface LoginRequest {
  username: string
  password: string
}

/** Token响应 */
export interface TokenResponse {
  accessToken: string
  refreshToken: string
  expiresIn: number
  tokenType: string
}

/** 用户信息 */
export interface UserInfo {
  id: number
  username: string
  nickname: string
  email: string
  avatar: string
  roles: string[]
  permissions: string[]
  lastLoginAt: string
}

// ==================== 聊天相关类型 ====================

/** 消息角色 */
export type MessageRole = 'user' | 'assistant' | 'system'

/** 消息 */
export interface Message {
  id: string
  role: MessageRole
  content: string
  timestamp: Date
  /** 来源引用 */
  sources?: SourceCitation[]
  /** 建议命令 */
  suggestedCommands?: CommandSuggestion[]
  /** 是否正在加载 */
  loading?: boolean
  /** 错误信息 */
  error?: string
}

/** 来源引用 */
export interface SourceCitation {
  source: string
  title: string
  score: number
  snippet: string
}

/** 命令建议 */
export interface CommandSuggestion {
  command: string
  description: string
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  requiresApproval: boolean
  /** 前端临时字段：审批提交状态，用于按钮联动反馈 */
  submitStatus?: 'idle' | 'submitting' | 'submitted' | 'failed'
  /** 前端临时字段：提交后得到的审计单号 */
  auditRequestId?: string
  /** 前端临时字段：提交后得到的审计状态 */
  auditStatus?: 'pending' | 'approved' | 'rejected' | 'executing' | 'completed' | 'failed'
}

/** 对话会话 */
export interface Conversation {
  id: string
  title: string
  messages: Message[]
  createdAt: Date
  updatedAt: Date
}

// ==================== API 响应类型 ====================

/** 聊天请求 */
export interface ChatRequest {
  sessionId?: string
  userId?: string
  conversationId?: number | null
  query: string
}

/** 聊天响应 */
export interface ChatResponse {
  success: boolean
  response: string
  intent: 'KNOWLEDGE_QUERY' | 'FAULT_DIAGNOSIS' | 'COMMAND_EXECUTE' | 'HYBRID' | 'UNKNOWN'
  sources: SourceCitation[]
  suggestedCommands: CommandSuggestion[]
  executionTimeMs: number
  conversationId?: number | null
}

/** 后端会话 DTO（chat_conversation 表） */
export interface ChatConversationDTO {
  id: number
  sessionId: string
  userId?: number
  title: string
  intent?: string
  agentUsed?: string
  messageCount: number
  createdAt: string
  updatedAt: string
}

/** 后端消息 DTO（chat_message 表） */
export interface ChatMessageDTO {
  id: number
  conversationId: number
  role: MessageRole
  content: string
  tokens?: number
  sources?: string
  metadata?: string
  createdAt: string
}

// ==================== 告警相关类型 ====================

/** 告警级别 */
export type AlertSeverity = 'info' | 'warning' | 'critical'

/** 告警状态 */
export type AlertStatus = 'firing' | 'resolved'

/** 告警记录 */
export interface Alert {
  id: string
  alertId: string
  source: string
  severity: AlertSeverity
  alertName: string
  message: string
  host: string
  metricName: string
  metricValue: string
  threshold: string
  status: AlertStatus
  createdAt: Date
  resolvedAt?: Date
}

// ==================== 审批相关类型 ====================

/** 审批状态 */
export type ApprovalStatus = 'pending' | 'approved' | 'rejected' | 'executed' | 'failed'

/** 审批请求（旧兼容结构） */
export interface ApprovalRequest {
  id: string
  requestId: string
  command: string
  description: string
  riskLevel: string
  riskScore: number
  status: ApprovalStatus
  requester: string
  approver?: string
  approvedAt?: Date
  executionResult?: string
  createdAt: Date
}

// ==================== RBAC / 审计 / 审批 / 执行 DTO ====================

export interface SysRoleDTO {
  id: number
  roleCode: string
  roleName: string
  description?: string
  parentId?: number
  sortOrder?: number
  status: number
  createdAt?: string
  updatedAt?: string
}

export interface SysPermissionDTO {
  id: number
  permissionCode: string
  permissionName: string
  module: string
  action: string
  description?: string
  riskLevel: 'low' | 'medium' | 'high'
}

export interface OperationLogDTO {
  id: number
  traceId?: string
  userId?: number
  username?: string
  module: string
  action: string
  target?: string
  description?: string
  requestMethod?: string
  requestUrl?: string
  requestParams?: string
  responseCode?: number
  ipAddress?: string
  userAgent?: string
  executionTimeMs?: number
  status: number
  errorMessage?: string
  createdAt: string
}

export type PermissionRequestType = 'ROLE_ASSIGN' | 'PERMISSION_GRANT' | 'TEMP_ELEVATION'
export type PermissionRequestStatus = 'PENDING' | 'REVIEWING' | 'APPROVED' | 'REJECTED' | 'EXPIRED'

export interface PermissionRequestDTO {
  id: number
  requestNo: string
  requesterId: number
  requestType: PermissionRequestType
  targetUserId?: number
  targetRoleId?: number
  targetPermissionIds?: string
  reason: string
  durationHours?: number
  riskLevel: 'low' | 'medium' | 'high'
  status: PermissionRequestStatus
  currentApproverId?: number
  approvedBy?: number
  rejectReason?: string
  approvedAt?: string
  expiresAt?: string
  createdAt: string
  updatedAt: string
}

export interface ApprovalFlowDTO {
  id: number
  requestId: number
  stepOrder: number
  approverId: number
  action?: 'APPROVE' | 'REJECT' | 'TRANSFER'
  comment?: string
  actedAt?: string
  createdAt: string
}

export type ExecutionStatus = 'pending' | 'approved' | 'rejected' | 'executing' | 'completed' | 'failed'

export interface ExecutionAuditDTO {
  id: number
  requestId: string
  userId?: number
  command: string
  commandType?: string
  targetHost?: string
  riskLevel: 'low' | 'medium' | 'high' | 'critical'
  riskScore: number
  status: ExecutionStatus
  approverId?: number
  executionResult?: string
  approvedAt?: string
  executedAt?: string
  createdAt: string
  updatedAt: string
}

/** 通用分页结果 */
export interface PageResult<T> {
  records: T[]
  total: number
  current: number
  size: number
  pages: number
}

// ==================== 知识库相关类型 ====================

/** 文档 */
export interface Document {
  id: number | string
  title: string
  source: string
  contentType: string
  category?: string
  content?: string
  wordCount: number
  chunkCount: number
  status: number
  createdBy?: number
  createdAt: string
  updatedAt?: string
}

/** 文档上传请求 */
export interface DocumentUploadRequest {
  title: string
  source: string
  content: string
  category?: string
}
