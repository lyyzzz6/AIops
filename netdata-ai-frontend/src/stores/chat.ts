import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { Message, Conversation, SourceCitation, CommandSuggestion } from '@/types'
import { chatApi } from '@/api'

/**
 * 聊天状态管理
 * 
 * 使用 Pinia 进行状态管理
 * 管理对话列表、当前对话、消息发送等
 */
export const useChatStore = defineStore('chat', () => {
  // ==================== State ====================
  
  /** 对话列表 */
  const conversations = ref<Conversation[]>([])
  
  /** 当前对话 ID */
  const currentConversationId = ref<string | null>(null)
  
  /** 是否正在加载 */
  const isLoading = ref(false)
  
  /** 会话 ID（用于 API） */
  const sessionId = ref<string>(generateSessionId())
  
  // ==================== Getters ====================
  
  /** 当前对话 */
  const currentConversation = computed(() => {
    return conversations.value.find(c => c.id === currentConversationId.value)
  })
  
  /** 当前对话的消息列表 */
  const currentMessages = computed(() => {
    return currentConversation.value?.messages ?? []
  })
  
  // ==================== Actions ====================
  
  /**
   * 创建新对话
   */
  function createConversation(title: string = '新对话'): string {
    const id = generateId()
    const conversation: Conversation = {
      id,
      title,
      messages: [],
      createdAt: new Date(),
      updatedAt: new Date(),
    }
    conversations.value.unshift(conversation)
    currentConversationId.value = id
    return id
  }
  
  /**
   * 选择对话
   */
  function selectConversation(id: string) {
    currentConversationId.value = id
  }
  
  /**
   * 删除对话
   */
  function deleteConversation(id: string) {
    const index = conversations.value.findIndex(c => c.id === id)
    if (index > -1) {
      conversations.value.splice(index, 1)
      // 如果删除的是当前对话，选择第一个
      if (currentConversationId.value === id) {
        currentConversationId.value = conversations.value[0]?.id ?? null
      }
    }
  }
  
  /**
   * 发送消息
   */
  async function sendMessage(content: string): Promise<void> {
    // 确保有当前对话
    if (!currentConversationId.value) {
      createConversation()
    }
    
    const conversation = currentConversation.value
    if (!conversation) return
    
    // 添加用户消息
    const userMessage: Message = {
      id: generateId(),
      role: 'user',
      content,
      timestamp: new Date(),
    }
    conversation.messages.push(userMessage)
    
    // 添加助手消息（loading 状态）
    const assistantMessage: Message = {
      id: generateId(),
      role: 'assistant',
      content: '',
      timestamp: new Date(),
      loading: true,
    }
    conversation.messages.push(assistantMessage)
    
    // 更新对话标题（使用第一条消息）
    if (conversation.messages.length <= 2) {
      conversation.title = content.slice(0, 20) + (content.length > 20 ? '...' : '')
    }
    
    conversation.updatedAt = new Date()
    
    // 发送请求
    isLoading.value = true
    
    try {
      const response = await chatApi.sendMessage({
        sessionId: sessionId.value,
        query: content,
      })
      
      // 更新助手消息
      assistantMessage.loading = false
      assistantMessage.content = response.response
      assistantMessage.sources = response.sources
      assistantMessage.suggestedCommands = response.suggestedCommands
      
    } catch (error: any) {
      assistantMessage.loading = false
      assistantMessage.error = error.message || '发送失败，请重试'
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
    
    // 找到最后一条用户消息
    const lastUserMessage = [...messages].reverse().find(m => m.role === 'user')
    if (!lastUserMessage) return
    
    // 删除最后一条助手消息
    const lastAssistantIndex = messages.length - 1
    if (messages[lastAssistantIndex].role === 'assistant') {
      messages.pop()
    }
    
    // 重新发送
    await sendMessage(lastUserMessage.content)
  }
  
  /**
   * 清空当前对话
   */
  function clearCurrentConversation() {
    if (currentConversation.value) {
      currentConversation.value.messages = []
      currentConversation.value.updatedAt = new Date()
    }
  }
  
  /**
   * 清空所有对话
   */
  function clearAllConversations() {
    conversations.value = []
    currentConversationId.value = null
  }
  
  // ==================== Helpers ====================
  
  function generateId(): string {
    return Date.now().toString(36) + Math.random().toString(36).slice(2)
  }
  
  function generateSessionId(): string {
    return 'session_' + generateId()
  }
  
  return {
    // State
    conversations,
    currentConversationId,
    isLoading,
    sessionId,
    
    // Getters
    currentConversation,
    currentMessages,
    
    // Actions
    createConversation,
    selectConversation,
    deleteConversation,
    sendMessage,
    regenerateLastReply,
    clearCurrentConversation,
    clearAllConversations,
  }
})
