/**
 * SSE (Server-Sent Events) 客户端工具
 * 
 * 参考项目：
 * - ChatGPT-Next-Web: https://github.com/Yidadaa/ChatGPT-Next-Web
 * - LlamaChatUI: https://github.com/haotian-liu/LLaMA-Factory
 * - FastChat: https://github.com/lm-sys/FastChat
 * 
 * 核心特性：
 * 1. 支持标准 SSE 协议
 * 2. 支持 OpenAI 格式的流式响应
 * 3. 支持自定义事件类型
 * 4. 自动重连机制
 * 5. 超时处理
 */

export interface SSEEvent {
  event: string
  data: string
  id?: string
  retry?: number
}

export interface SSEClientOptions {
  url: string
  method?: string
  headers?: Record<string, string>
  body?: any
  timeout?: number
  onEvent?: (event: SSEEvent) => void
  onMessage?: (data: string) => void
  onError?: (error: Error) => void
  onClose?: () => void
  signal?: AbortSignal
}

/**
 * 解析 SSE 数据流
 * @param buffer 累积的缓冲区
 * @returns 解析出的事件列表和剩余缓冲区
 */
export function parseSSE(buffer: string): { events: SSEEvent[]; remaining: string } {
  const events: SSEEvent[] = []
  let remaining = buffer

  // SSE 事件以 \n\n 分隔，支持 \r\n\r\n
  const separator = remaining.includes('\r\n\r\n') ? '\r\n\r\n' : '\n\n'
  
  while (remaining.includes(separator)) {
    const index = remaining.indexOf(separator)
    const eventStr = remaining.slice(0, index)
    remaining = remaining.slice(index + separator.length)

    if (!eventStr.trim()) continue

    const event: SSEEvent = {
      event: 'message',
      data: '',
    }

    // 按行解析
    const lines = eventStr.split(/\r?\n/)
    for (const line of lines) {
      const trimmedLine = line.trim()
      
      if (trimmedLine.startsWith('event:')) {
        event.event = trimmedLine.slice(6).trim()
      } else if (trimmedLine.startsWith('data:')) {
        // data: 后面可能有空格，需要去掉
        const dataContent = trimmedLine.slice(5)
        event.data += dataContent.trimStart()
      } else if (trimmedLine.startsWith('id:')) {
        event.id = trimmedLine.slice(3).trim()
      } else if (trimmedLine.startsWith('retry:')) {
        const retryValue = trimmedLine.slice(6).trim()
        event.retry = parseInt(retryValue, 10)
      }
    }

    events.push(event)
  }

  return { events, remaining }
}

/**
 * 创建 SSE 客户端
 */
export async function createSSEClient(options: SSEClientOptions): Promise<() => void> {
  const {
    url,
    method = 'GET',
    headers = {},
    body,
    timeout = 60000,
    onEvent,
    onMessage,
    onError,
    onClose,
    signal,
  } = options

  const controller = new AbortController()
  const timeoutId = setTimeout(() => {
    controller.abort()
    onError?.(new Error('请求超时'))
  }, timeout)

  const fetchHeaders = new Headers({
    'Accept': 'text/event-stream',
    'Cache-Control': 'no-cache',
    ...headers,
  })

  let response: Response
  try {
    response = await fetch(url, {
      method,
      headers: fetchHeaders,
      body: body ? JSON.stringify(body) : undefined,
      signal: signal || controller.signal,
    })
  } catch (err) {
    clearTimeout(timeoutId)
    const error = err instanceof Error ? err : new Error(String(err))
    onError?.(error)
    return () => {}
  }

  clearTimeout(timeoutId)

  if (!response.ok) {
    let errorText = `HTTP Error: ${response.status}`
    try {
      errorText = await response.text()
    } catch {
      // ignore
    }
    onError?.(new Error(errorText))
    return () => {}
  }

  if (!response.body) {
    onError?.(new Error('响应体为空'))
    return () => {}
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  const cleanup = () => {
    reader.releaseLock()
    controller.abort()
    onClose?.()
  }

  const readStream = async () => {
    try {
      while (true) {
        const { value, done } = await reader.read()
        
        if (done) {
          // 处理剩余的缓冲区数据
          if (buffer.trim()) {
            const { events } = parseSSE(buffer)
            for (const event of events) {
              processEvent(event)
            }
          }
          cleanup()
          return
        }

        buffer += decoder.decode(value, { stream: true })
        
        // 解析缓冲区中的事件
        let parsed: ReturnType<typeof parseSSE>
        do {
          parsed = parseSSE(buffer)
          for (const event of parsed.events) {
            processEvent(event)
          }
          buffer = parsed.remaining
        } while (parsed.events.length > 0)
      }
    } catch (err) {
      const error = err instanceof Error ? err : new Error(String(err))
      if (error.message !== 'The operation was aborted') {
        onError?.(error)
      }
      cleanup()
    }
  }

  function processEvent(event: SSEEvent) {
    onEvent?.(event)
    
    // 默认事件或 message 事件触发 onMessage
    if (event.event === 'message' || event.event === '') {
      onMessage?.(event.data)
    }
  }

  // 立即启动读取流
  readStream().catch(err => {
    if (err instanceof Error && err.message !== 'The operation was aborted') {
      onError?.(err)
    }
  })

  return cleanup
}

/**
 * 简化的流式请求封装
 */
export interface StreamRequestOptions {
  url: string
  method?: 'GET' | 'POST'
  headers?: Record<string, string>
  body?: any
  timeout?: number
  onChunk?: (chunk: string) => void
  onThinking?: (thinking: string) => void
  onComplete?: (data?: any) => void
  onError?: (error: Error) => void
  signal?: AbortSignal
}

/**
 * 发送流式请求（简化版）
 * 专门处理 AI 聊天的流式响应
 * 
 * 后端事件格式约定：
 * - event: thinking  data: {"content": "..."}    - 模型思考过程
 * - event: chunk     data: {"delta": "..."}        - 逐段下发的文本
 * - event: end       data: {"success":..., "intent":..., "sources":..., "suggestedCommands":..., "conversationId":..., "executionTimeMs":...}
 * - event: error     data: {"message":"..."}
 * - event: ping      data: (empty)                 - 心跳保活
 */
export async function streamRequest(options: StreamRequestOptions): Promise<void> {
  const {
    url,
    method = 'POST',
    headers = {},
    body,
    timeout = 120000,
    onChunk,
    onThinking,
    onComplete,
    onError,
    signal,
  } = options

  const token = localStorage.getItem('access_token')
  const fetchHeaders: Record<string, string> = {
    'Content-Type': 'application/json',
    'Accept': 'text/event-stream',
    'Cache-Control': 'no-cache',
    ...headers,
    ...(token ? { 'Authorization': `Bearer ${token}` } : {}),
  }

  const controller = new AbortController()
  const timeoutId = setTimeout(() => {
    controller.abort()
    onError?.(new Error('请求超时'))
  }, timeout)

  let response: Response
  try {
    response = await fetch(url, {
      method,
      headers: fetchHeaders,
      body: body ? JSON.stringify(body) : undefined,
      signal: signal || controller.signal,
    })
  } catch (err) {
    clearTimeout(timeoutId)
    onError?.(err instanceof Error ? err : new Error(String(err)))
    return
  }

  clearTimeout(timeoutId)

  if (!response.ok) {
    let errorMsg = `HTTP ${response.status}`
    try {
      const text = await response.text()
      if (text) errorMsg += `: ${text}`
    } catch {
      // ignore
    }
    onError?.(new Error(errorMsg))
    return
  }

  if (!response.body) {
    onError?.(new Error('响应体为空'))
    return
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let completed = false

  try {
    while (true) {
      const { value, done } = await reader.read()

      if (done) {
        if (buffer.trim()) {
          processBuffer()
        }
        // 只有在没有收到 end 事件的情况下才调用 onComplete
        if (!completed) {
          onComplete?.()
        }
        return
      }

      buffer += decoder.decode(value, { stream: true })
      processBuffer()
    }
  } catch (err) {
    if (!(err instanceof Error && err.message === 'The operation was aborted')) {
      onError?.(err instanceof Error ? err : new Error(String(err)))
    }
  } finally {
    reader.releaseLock()
  }

  function processBuffer() {
    // 处理标准 SSE 格式
    while (true) {
      const doubleNewlineIndex = buffer.indexOf('\n\n')
      const crlfIndex = buffer.indexOf('\r\n\r\n')
      
      // 找到第一个分隔符
      let sepIndex: number
      let sepLength: number
      
      if (doubleNewlineIndex !== -1 && crlfIndex !== -1) {
        sepIndex = Math.min(doubleNewlineIndex, crlfIndex)
        sepLength = sepIndex === doubleNewlineIndex ? 2 : 4
      } else if (doubleNewlineIndex !== -1) {
        sepIndex = doubleNewlineIndex
        sepLength = 2
      } else if (crlfIndex !== -1) {
        sepIndex = crlfIndex
        sepLength = 4
      } else {
        break
      }

      const eventStr = buffer.slice(0, sepIndex)
      buffer = buffer.slice(sepIndex + sepLength)

      if (!eventStr.trim()) continue

      parseAndDispatch(eventStr)
    }
  }

  function parseAndDispatch(eventStr: string) {
    let eventType = 'message'
    let dataStr = ''

    const lines = eventStr.split(/\r?\n/)
    for (const line of lines) {
      const trimmedLine = line.trim()
      
      if (trimmedLine.startsWith('event:')) {
        eventType = trimmedLine.slice(6).trim()
        console.debug('[SSE] 解析事件类型:', eventType)
      } else if (trimmedLine.startsWith('data:')) {
        dataStr += trimmedLine.slice(5).trimStart()
      }
    }

    // 处理心跳事件（ping）
    if (eventType === 'ping') {
      console.debug('[SSE] 收到心跳 ping')
      return
    }

    // 如果没有数据，跳过
    if (!dataStr.trim()) {
      console.debug('[SSE] 数据为空，跳过. eventType:', eventType)
      return
    }

    try {
      const payload = JSON.parse(dataStr)
      
      if (eventType === 'chunk') {
        // 文本片段事件
        if (payload && typeof payload.delta === 'string') {
          console.debug('[SSE] 收到 chunk delta:', payload.delta.substring(0, 30))
          onChunk?.(payload.delta)
        } else if (typeof payload === 'string') {
          console.debug('[SSE] 收到 chunk delta (字符串):', payload.substring(0, 30))
          onChunk?.(payload)
        } else {
          console.warn('[SSE] chunk 事件缺少 delta 字段:', payload)
        }
      } else if (eventType === 'thinking') {
        // 思考过程事件
        if (payload && typeof payload.content === 'string') {
          console.debug('[SSE] 收到 thinking:', payload.content.substring(0, 30))
          onThinking?.(payload.content)
        } else if (typeof payload === 'string') {
          console.debug('[SSE] 收到 thinking (字符串):', payload.substring(0, 30))
          onThinking?.(payload)
        } else {
          console.warn('[SSE] thinking 事件缺少 content 字段:', payload)
        }
      } else if (eventType === 'end') {
        // 结束事件
        console.debug('[SSE] 收到 end 事件:', payload)
        completed = true
        onComplete?.(payload)
      } else if (eventType === 'error') {
        // 错误事件
        const errorMsg = payload?.message || payload || '流式异常'
        console.error('[SSE] 收到错误事件:', errorMsg)
        onError?.(new Error(errorMsg))
      } else if (eventType === 'message') {
        // 兼容 OpenAI 格式和通用消息格式
        if (payload.choices && payload.choices[0]?.delta?.content) {
          console.debug('[SSE] 收到 OpenAI 格式 chunk:', payload.choices[0].delta.content.substring(0, 30))
          onChunk?.(payload.choices[0].delta.content)
        } else if (payload.content) {
          console.debug('[SSE] 收到 message.content:', payload.content.substring(0, 30))
          onChunk?.(payload.content)
        } else if (payload.delta) {
          console.debug('[SSE] 收到 message.delta:', payload.delta.substring(0, 30))
          onChunk?.(payload.delta)
        }
      } else {
        console.warn('[SSE] 未知事件类型:', eventType, ', data:', dataStr)
      }
    } catch (parseError) {
      // 如果不是 JSON，直接作为文本处理
      console.debug('[SSE] 数据不是 JSON 格式，event:', eventType, ', data:', dataStr)
      if (eventType === 'message' || eventType === 'chunk') {
        onChunk?.(dataStr)
      }
    }
  }
}