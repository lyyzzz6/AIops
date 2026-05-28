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

export const useChatStore = defineStore('chat', () => {
  const conversations = ref<Conversation[]>([])
  const currentConversationId = ref<string | number | null>(null)
  const isLoading = ref(false)
  const isListLoading = ref(false)
  const sessionId = ref<string>(generateSessionId())

  const currentConversation = computed(() =>
    conversations.value.find((c) => String(c.id) === String(currentConversationId.value))
  )

  const currentMessages = computed(() => currentConversation.value?.messages ?? [])

  async function loadConversations(): Promise<void> {
    isListLoading.value = true
    try {
      const page = await chatApi.getConversations({ current: 1, size: 50 })
      conversations.value = (page.records || []).map(mapConversation)
    } catch (e) {
      conversations.value = []
    } finally {
      isListLoading.value = false
    }
  }

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
    currentConversationId.value = placeholderId
    return conv
  }

  async function selectConversation(id: string | number): Promise<void> {
    currentConversationId.value = id
    const conv = conversations.value.find((c) => String(c.id) === String(id))
    if (!conv) return

    const numericId = typeof id === 'string' ? Number(id) : id
    if (Number.isNaN(numericId) || numericId <= 0) return
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

  async function sendMessage(content: string): Promise<void> {
    if (!currentConversation.value) {
      createConversation()
    }
    const conversation = currentConversation.value
    if (!conversation) return

    const userMessage: Message = {
      id: generateLocalId(),
      role: 'user',
      content,
      timestamp: new Date(),
    }
    conversation.messages.push(userMessage)

    const assistantMessageIndex = conversation.messages.length
    conversation.messages.push({
      id: generateLocalId(),
      role: 'assistant',
      content: '',
      timestamp: new Date(),
      loading: true,
    })

    if (conversation.messages.length <= 2) {
      conversation.title = content.slice(0, 20) + (content.length > 20 ? '...' : '')
    }
    conversation.updatedAt = new Date()

    const numericConvId =
      typeof currentConversationId.value === 'number' ? currentConversationId.value : undefined

    isLoading.value = true
    try {
      let fellBack = false
      let firstChunkReceived = false
      try {
        conversation.messages[assistantMessageIndex].content = ''
        await chatApi.sendMessageStream(
          {
            sessionId: sessionId.value,
            conversationId: numericConvId,
            query: content,
          },
          {
            onDelta: (delta: string) => {
              if (!firstChunkReceived) {
                conversation.messages[assistantMessageIndex].loading = false
                firstChunkReceived = true
              }
              conversation.messages[assistantMessageIndex].content += delta
              conversation.messages = [...conversation.messages]
            },
            onEnd: (payload: any) => {
              conversation.messages[assistantMessageIndex].loading = false
              firstChunkReceived = true
              if (payload) {
                conversation.messages[assistantMessageIndex].sources = payload.sources
                conversation.messages[assistantMessageIndex].suggestedCommands = payload.suggestedCommands
                if (payload.conversationId && payload.conversationId > 0) {
                  const newId = payload.conversationId as number
                  conversation.id = String(newId)
                  currentConversationId.value = newId
                }
              }
              conversation.messages = [...conversation.messages]
            },
            onError: (err: Error) => {
              throw err
            },
          }
        )
      } catch (streamErr) {
        fellBack = true
        console.warn('[chat] 流式接口失败，回退非流式：', streamErr)
      }

      if (fellBack) {
        conversation.messages[assistantMessageIndex].loading = true
        conversation.messages[assistantMessageIndex].content = ''
        const response = await chatApi.sendMessage({
          sessionId: sessionId.value,
          conversationId: numericConvId,
          query: content,
        })
        conversation.messages[assistantMessageIndex].loading = false
        conversation.messages[assistantMessageIndex].content = response.response
        conversation.messages[assistantMessageIndex].sources = response.sources
        conversation.messages[assistantMessageIndex].suggestedCommands = response.suggestedCommands
        if (response.conversationId && response.conversationId > 0) {
          const newId = response.conversationId
          conversation.id = String(newId)
          currentConversationId.value = newId
        }
        conversation.messages = [...conversation.messages]
      }
    } catch (error: any) {
      const msgIndex = conversation.messages.length - 1
      if (msgIndex >= 0 && conversation.messages[msgIndex].role === 'assistant') {
        conversation.messages[msgIndex].loading = false
        conversation.messages[msgIndex].error = error?.message || '发送失败，请重试'
        conversation.messages = [...conversation.messages]
      }
    } finally {
      isLoading.value = false
    }
  }

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

  function clearAllConversations() {
    conversations.value = []
    currentConversationId.value = null
    sessionId.value = generateSessionId()
  }

  function mapConversation(dto: ChatConversationDTO): Conversation {
    return {
      id: String(dto.id),
      title: dto.title || '新对话',
      messages: [],
      createdAt: new Date(dto.createdAt),
      updatedAt: new Date(dto.updatedAt),
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

  function generateLocalId(): string {
    return 'local-' + Date.now().toString(36) + Math.random().toString(36).slice(2)
  }

  function generateSessionId(): string {
    return 'session_' + Date.now().toString(36) + Math.random().toString(36).slice(2)
  }

  return {
    conversations,
    currentConversationId,
    isLoading,
    isListLoading,
    sessionId,
    currentConversation,
    currentMessages,
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
