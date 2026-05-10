import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type {
  Message,
  Conversation,
  ChatConversationDTO,
  ChatMessageDTO,
  SourceCitation,
  CommandSuggestion,
} from '@/types'
import { chatApi } from '@/api'

/**
 * 聊天状态管理
 *
 * 数据流：
 * - 登录后 loadConversations() 从后端拉取会话列表
 * - selectConversation() 触发 loadMessages() 拉取对应消息填充
 * - sendMessage() 以乐观更新插入占位消息，服务端返回 conversationId 后同步本地
 * - 删除 / 清空调用后端接口后再改本地
 */
export const useChatStore = defineStore('chat', () => {
  // ==================== State ====================

  /** 对话列表（服务端同步） */
  const conversations = ref<Conversation[]>([])

  /**
   * 当前对话 ID
   * - 已落盘会话：number（后端 Long）
   * - 本地占位会话：string（形如 `local-xxx`），首条消息落盘后替换为 number
   */
  const currentConversationId = ref<string | number | null>(null)

  /** 是否正在加载 */
  const isLoading = ref(false)

  /** 是否正在加载会话列表 */
  const isListLoading = ref(false)

  /** 会话 ID（发送到 /chat 的 sessionId 字段，会话未落盘时使用） */
  const sessionId = ref<string>(generateSessionId())

  // ==================== Getters ====================

  const currentConversation = computed(() =>
    conversations.value.find((c) => String(c.id) === String(currentConversationId.value))
  )

  const currentMessages = computed(() => currentConversation.value?.messages ?? [])

  // ==================== Actions ====================

  /**
   * 加载会话列表（登录后或进入 ChatView 时调用）
   */
  async function loadConversations(): Promise<void> {
    isListLoading.value = true
    try {
      const page = await chatApi.getConversations({ current: 1, size: 50 })
      conversations.value = (page.records || []).map(mapConversation)
    } catch (e) {
      // 静默失败：未登录或接口异常时保持空列表
      conversations.value = []
    } finally {
      isListLoading.value = false
    }
  }

  /**
   * 创建新对话（仅本地占位，待首条消息落盘后服务端生成真实 id）
   */
  function createConversation(title: string = '新对话'): Conversation {
    sessionId.value = generateSessionId()
    const placeholderId = `local-${Date.now()}`
    const conv: Conversation = {
      id: placeholderId,
      title,
      messages: [],
      createdAt: new Date(),
      updatedAt: new Date(),
    }
    conversations.value.unshift(conv)
    // 关键：把当前会话指针指向占位 id，否则 currentConversation 计算属性无法匹配
    currentConversationId.value = placeholderId
    return conv
  }

  /**
   * 选择对话：若为后端真实 id，拉取其消息；若为本地占位 id，仅切换当前指针
   */
  async function selectConversation(id: string | number): Promise<void> {
    currentConversationId.value = id
    const conv = conversations.value.find((c) => String(c.id) === String(id))
    if (!conv) return

    const numericId = typeof id === 'string' ? Number(id) : id
    if (Number.isNaN(numericId) || numericId <= 0) {
      // 本地占位会话，无需从后端加载
      return
    }

    // 已加载过则跳过
    if (conv.messages.length > 0) return

    try {
      const msgs = await chatApi.getMessages(numericId)
      conv.messages = msgs.map(mapMessage)
      const match = conversations.value.find((c) => String(c.id) === String(numericId)) as any
      if (match && match.sessionId) sessionId.value = match.sessionId
    } catch (e) {
      // ignore
    }
  }

  /**
   * 删除对话
   */
  async function deleteConversation(id: string | number): Promise<void> {
    const numericId = typeof id === 'string' ? Number(id) : id
    if (!Number.isNaN(numericId) && numericId > 0) {
      try {
        await chatApi.deleteConversation(numericId)
      } catch (e) {
        return
      }
    }
    const index = conversations.value.findIndex((c) => String(c.id) === String(id))
    if (index > -1) {
      conversations.value.splice(index, 1)
      if (String(currentConversationId.value) === String(id)) {
        const next = conversations.value[0]
        currentConversationId.value = next ? next.id : null
      }
    }
  }

  /**
   * 发送消息（乐观更新 + 服务端持久化）
   */
  async function sendMessage(content: string): Promise<void> {
    // 确保有当前对话（否则创建本地占位）
    if (!currentConversation.value) {
      createConversation()
    }
    const conversation = currentConversation.value
    if (!conversation) return

    // 乐观插入用户消息
    const userMessage: Message = {
      id: generateLocalId(),
      role: 'user',
      content,
      timestamp: new Date(),
    }
    conversation.messages.push(userMessage)

    // 插入助手占位
    const assistantMessage: Message = {
      id: generateLocalId(),
      role: 'assistant',
      content: '',
      timestamp: new Date(),
      loading: true,
    }
    conversation.messages.push(assistantMessage)

    // 首条消息时更新本地标题
    if (conversation.messages.length <= 2) {
      conversation.title = content.slice(0, 20) + (content.length > 20 ? '...' : '')
    }
    conversation.updatedAt = new Date()

    // 只向后端透传数值型 conversationId；本地占位 id 不发送，由后端通过 sessionId 自动创建
    const numericConvId =
      typeof currentConversationId.value === 'number' ? currentConversationId.value : undefined

    isLoading.value = true
    try {
      // 默认走 SSE 流式接口，连接或事件解析失败时回退到非流式接口
      let fellBack = false
      try {
        assistantMessage.loading = false
        assistantMessage.content = ''
        await chatApi.sendMessageStream(
          {
            sessionId: sessionId.value,
            conversationId: numericConvId,
            query: content,
          },
          {
            onDelta: (delta: string) => {
              assistantMessage.content += delta
            },
            onEnd: (payload: any) => {
              if (payload) {
                assistantMessage.sources = payload.sources
                assistantMessage.suggestedCommands = payload.suggestedCommands
                if (payload.conversationId && payload.conversationId > 0) {
                  const newId = payload.conversationId as number
                  conversation.id = String(newId)
                  currentConversationId.value = newId
                }
              }
            },
            onError: (err: Error) => {
              throw err
            },
          }
        )
      } catch (streamErr) {
        // 流式失败 → 回退非流式
        fellBack = true
        console.warn('[chat] 流式接口失败，回退非流式：', streamErr)
      }

      if (fellBack) {
        assistantMessage.loading = true
        assistantMessage.content = ''
        const response = await chatApi.sendMessage({
          sessionId: sessionId.value,
          conversationId: numericConvId,
          query: content,
        })
        assistantMessage.loading = false
        assistantMessage.content = response.response
        assistantMessage.sources = response.sources
        assistantMessage.suggestedCommands = response.suggestedCommands
        if (response.conversationId && response.conversationId > 0) {
          const newId = response.conversationId
          conversation.id = String(newId)
          currentConversationId.value = newId
        }
      }
    } catch (error: any) {
      assistantMessage.loading = false
      assistantMessage.error = error?.message || '发送失败，请重试'
    } finally {
      isLoading.value = false
    }
  }

  /**
   * 重新生成最后一条回复
   */
  async function regenerateLastReply(): Promise<void> {
    const messages = currentMessages.value
    if (messages.length < 2) return
    const lastUserMessage = [...messages].reverse().find((m) => m.role === 'user')
    if (!lastUserMessage) return
    if (messages[messages.length - 1].role === 'assistant') {
      messages.pop()
    }
    await sendMessage(lastUserMessage.content)
  }

  /**
   * 清空当前对话（保留会话）
   */
  async function clearCurrentConversation(): Promise<void> {
    const conv = currentConversation.value
    if (!conv) return
    const numericId = Number(conv.id)
    if (!Number.isNaN(numericId) && numericId > 0) {
      try {
        await chatApi.clearMessages(numericId)
      } catch (e) {
        return
      }
    }
    conv.messages = []
    conv.updatedAt = new Date()
  }

  /**
   * 清空所有本地对话状态（登出时调用）
   */
  function clearAllConversations() {
    conversations.value = []
    currentConversationId.value = null
    sessionId.value = generateSessionId()
  }

  // ==================== Mappers ====================

  function mapConversation(dto: ChatConversationDTO): Conversation {
    return {
      id: String(dto.id),
      title: dto.title || '新对话',
      messages: [],
      createdAt: new Date(dto.createdAt),
      updatedAt: new Date(dto.updatedAt),
      // 附加非标准字段，便于拉取消息时复用 sessionId
      ...({ sessionId: dto.sessionId } as any),
    }
  }

  function mapMessage(dto: ChatMessageDTO): Message {
    let sources: SourceCitation[] | undefined
    let suggestedCommands: CommandSuggestion[] | undefined
    if (dto.sources) {
      try {
        const parsed = JSON.parse(dto.sources)
        if (Array.isArray(parsed)) sources = parsed as SourceCitation[]
      } catch {
        /* ignore */
      }
    }
    // 从 metadata JSON 中恢复 suggestedCommands（保证卸载后重新导入会话仍能显示命令卡片及安全动作按钮）
    if (dto.metadata) {
      try {
        const meta = JSON.parse(dto.metadata)
        if (meta && Array.isArray(meta.suggestedCommands)) {
          suggestedCommands = meta.suggestedCommands as CommandSuggestion[]
        }
      } catch {
        /* ignore */
      }
    }
    return {
      id: String(dto.id),
      role: dto.role,
      content: dto.content,
      timestamp: new Date(dto.createdAt),
      sources,
      suggestedCommands,
    }
  }

  // ==================== Helpers ====================

  function generateLocalId(): string {
    return 'local-' + Date.now().toString(36) + Math.random().toString(36).slice(2)
  }

  function generateSessionId(): string {
    return 'session_' + Date.now().toString(36) + Math.random().toString(36).slice(2)
  }

  return {
    // State
    conversations,
    currentConversationId,
    isLoading,
    isListLoading,
    sessionId,

    // Getters
    currentConversation,
    currentMessages,

    // Actions
    loadConversations,
    createConversation,
    selectConversation,
    deleteConversation,
    sendMessage,
    regenerateLastReply,
    clearCurrentConversation,
    clearAllConversations,
  }
})
