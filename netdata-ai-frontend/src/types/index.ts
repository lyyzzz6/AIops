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

/** 审批请求 */
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

// ==================== 知识库相关类型 ====================

/** 文档 */
export interface Document {
  id: string
  title: string
  source: string
  contentType: string
  category: string
  wordCount: number
  chunkCount: number
  status: 'processing' | 'completed' | 'failed'
  createdAt: Date
}

/** 文档上传请求 */
export interface DocumentUploadRequest {
  title: string
  source: string
  content: string
  category?: string
}
