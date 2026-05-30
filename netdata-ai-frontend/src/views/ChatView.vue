<template>
  <div class="chat-layout">
    <!-- 侧边栏 -->
    <aside class="sidebar" :class="{ collapsed: settings.sidebarCollapsed }">
      <div class="sidebar-header">
        <el-button type="primary" @click="createNewChat" class="new-chat-btn">
          <el-icon><Plus /></el-icon>
          <span v-if="!settings.sidebarCollapsed">新建对话</span>
        </el-button>
      </div>
      
      <el-scrollbar class="conversation-list">
        <div
          v-for="conv in chat.conversations"
          :key="conv.id"
          class="conversation-item"
          :class="{ active: conv.id === String(chat.currentConversationId) }"
          @click="chat.selectConversation(conv.id)"
        >
          <el-icon><ChatDotRound /></el-icon>
          <span class="conversation-title">{{ conv.title }}</span>
          <el-button
            text
            size="small"
            @click.stop="chat.deleteConversation(conv.id)"
            class="delete-btn"
          >
            <el-icon><Delete /></el-icon>
          </el-button>
        </div>
      </el-scrollbar>
    </aside>
    
    <!-- 主聊天区域 -->
    <main class="chat-main">
      <!-- 顶部工具栏 -->
      <header class="chat-header">
        <el-button text @click="settings.toggleSidebar">
          <el-icon><Expand v-if="settings.sidebarCollapsed" /><Fold v-else /></el-icon>
        </el-button>
        <h1>智能运维问答</h1>
        <div class="header-actions">
          <el-button text @click="clearChat" :disabled="!chat.currentMessages.length">
            <el-icon><Delete /></el-icon>
            清空对话
          </el-button>
        </div>
      </header>
      
      <!-- 消息列表 -->
      <div class="message-container" ref="messageContainer">
        <div v-if="!chat.currentMessages.length" class="empty-state">
          <el-icon :size="64" color="#c0c4cc"><ChatDotRound /></el-icon>
          <h2>欢迎使用智能运维问答系统</h2>
          <p>请输入您的运维问题，例如：</p>
          <div class="quick-actions">
            <el-button v-for="example in examples" :key="example" @click="sendExample(example)">
              {{ example }}
            </el-button>
          </div>
        </div>
        
        <MessageItem
          v-for="message in chat.currentMessages"
          :key="message.id"
          :message="message"
          @retry="retryMessage"
          @execute-command="handleExecuteCommand"
          @open-approval="handleOpenApproval"
        />
      </div>
      
      <!-- 输入区域 -->
      <footer class="input-area">
        <el-input
          v-model="inputText"
          type="textarea"
          :rows="3"
          placeholder="请输入您的运维问题... (Shift+Enter 换行，Enter 发送)"
          :disabled="chat.isLoading"
          @keydown.enter.exact="handleSend"
          @keydown.enter.shift.exact.prevent="() => {}"
        />
        <div class="input-actions">
          <span class="hint">Shift + Enter 换行</span>
          <el-button
            type="primary"
            :loading="chat.isLoading"
            :disabled="!inputText.trim()"
            @click="handleSend"
          >
            发送
            <el-icon class="el-icon--right"><Promotion /></el-icon>
          </el-button>
        </div>
      </footer>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, watch, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Delete, ChatDotRound, Promotion, Fold, Expand } from '@element-plus/icons-vue'
import { useChatStore, useSettingsStore } from '@/stores'
import MessageItem from '@/components/MessageItem.vue'
import { executionApi } from '@/api'
import type { CommandSuggestion } from '@/types'

const router = useRouter()
const route = useRoute()

// Stores
const chat = useChatStore()
const settings = useSettingsStore()

// Refs
const inputText = ref('')
const messageContainer = ref<HTMLElement>()

// 示例问题
const examples = [
  '什么是 CPU 飙升？如何排查？',
  '为什么内存使用率突然升高？',
  '如何诊断网络延迟问题？',
  '查看系统内存利用率',
]

// 进入页面时拉取历史会话，并处理外部传入的问题
onMounted(async () => {
  await chat.loadConversations()
  
  // 处理外部传入的查询参数（如从告警诊断跳转）
  const queryQuestion = route.query.q as string
  if (queryQuestion) {
    inputText.value = queryQuestion
    // 延迟发送，确保页面已完全加载
    setTimeout(() => {
      handleSend()
    }, 100)
  }
})

// 创建新对话
function createNewChat() {
  chat.createConversation()
}

// 发送消息
async function handleSend() {
  const text = inputText.value.trim()
  if (!text || chat.isLoading) return

  inputText.value = ''
  await chat.sendMessage(text)

  // 滚动到底部
  await nextTick()
  scrollToBottom()
}

// 发送示例
function sendExample(example: string) {
  inputText.value = example
  handleSend()
}

// 重试消息
function retryMessage() {
  chat.regenerateLastReply()
}

// 发起执行：根据风险审批标志分流
// - requiresApproval=false → 直接跳命令执行页预填
// - requiresApproval=true  → 直接调 API 提交审批并跳转审批中心
async function handleExecuteCommand(cmd: CommandSuggestion) {
  if (!cmd.requiresApproval) {
    router.push({ path: '/executions', query: { command: cmd.command } })
    ElMessage.info('已跳转到命令执行页面')
    return
  }
  // 需审批：直接提交审批请求并跳转审批中心
  cmd.submitStatus = 'submitting'
  try {
    const audit = await executionApi.submit({ command: cmd.command })
    cmd.submitStatus = 'submitted'
    cmd.auditRequestId = audit.requestId
    cmd.auditStatus = audit.status
    const statusMsg =
      audit.status === 'pending' ? '已提交审批，等待审批人处理' :
      audit.status === 'approved' ? '风险较低，已自动批准' :
      audit.status === 'rejected' ? '风险过高，已被自动拒绝' :
      `提交成功，状态：${audit.status}`
    ElMessage.success(`${statusMsg} (编号 ${audit.requestId})`)
    router.push({ path: '/approvals', query: { tab: 'execution' } })
  } catch (e: any) {
    cmd.submitStatus = 'failed'
    const msg = e?.response?.data?.message || e?.message || '提交审批失败'
    ElMessage.error(`提交失败：${msg}，已跳转手动提交页`)
    router.push({ path: '/executions', query: { command: cmd.command } })
  }
}

// 前往审批中心：直接跳到命令审批 Tab，带上命令关键字
function handleOpenApproval(cmd: CommandSuggestion) {
  router.push({
    path: '/approvals',
    query: { tab: 'execution', command: cmd.command },
  })
}

// 清空对话
async function clearChat() {
  try {
    await ElMessageBox.confirm('确定要清空当前对话吗？', '提示', {
      type: 'warning',
    })
    await chat.clearCurrentConversation()
    ElMessage.success('已清空当前对话')
  } catch {
    // 取消
  }
}

// 滚动到底部
function scrollToBottom() {
  if (messageContainer.value) {
    messageContainer.value.scrollTop = messageContainer.value.scrollHeight
  }
}

// 监听消息变化，自动滚动
watch(
  () => chat.currentMessages.length,
  () => {
    nextTick(scrollToBottom)
  }
)
</script>

<style scoped lang="scss">
.chat-layout {
  display: flex;
  height: 100%;
  min-height: calc(100vh - 56px);
  background: linear-gradient(180deg, #f5f7fa 0%, #e8ecf1 100%);
}

.sidebar {
  width: 260px;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(20px);
  border-right: 1px solid rgba(0, 0, 0, 0.06);
  display: flex;
  flex-direction: column;
  transition: width 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  box-shadow: 4px 0 24px rgba(0, 0, 0, 0.03);
  
  &.collapsed {
    width: 64px;
    
    .conversation-title,
    .new-chat-btn span {
      display: none;
    }
  }
}

.sidebar-header {
  padding: 16px;
  border-bottom: 1px solid rgba(0, 0, 0, 0.04);
}

.new-chat-btn {
  width: 100%;
  border-radius: 12px;
  height: 44px;
  font-weight: 500;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.15);
  
  &:hover {
    transform: translateY(-1px);
    box-shadow: 0 4px 12px rgba(64, 158, 255, 0.25);
  }
}

.conversation-list {
  flex: 1;
  overflow-y: auto;
  
  &::-webkit-scrollbar {
    width: 6px;
  }
  
  &::-webkit-scrollbar-track {
    background: transparent;
  }
  
  &::-webkit-scrollbar-thumb {
    background: rgba(0, 0, 0, 0.1);
    border-radius: 3px;
    
    &:hover {
      background: rgba(0, 0, 0, 0.18);
    }
  }
}

.conversation-item {
  display: flex;
  align-items: center;
  padding: 14px 16px;
  margin: 4px 12px;
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  
  &:hover {
    background: rgba(0, 0, 0, 0.03);
  }
  
  &.active {
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    color: white;
    box-shadow: 0 4px 16px rgba(102, 126, 234, 0.3);
  }
  
  .el-icon {
    margin-right: 10px;
    flex-shrink: 0;
  }
  
  .conversation-title {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 14px;
    font-weight: 500;
  }
  
  .delete-btn {
    opacity: 0;
    transition: opacity 0.2s;
  }
  
  &:hover .delete-btn {
    opacity: 1;
  }
}

.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.chat-header {
  display: flex;
  align-items: center;
  padding: 16px 24px;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(20px);
  border-bottom: 1px solid rgba(0, 0, 0, 0.04);
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.02);
  
  h1 {
    flex: 1;
    margin: 0 20px;
    font-size: 20px;
    font-weight: 600;
    color: #1d2129;
    letter-spacing: -0.3px;
  }
}

.message-container {
  flex: 1;
  overflow-y: auto;
  padding: 24px;
  
  &::-webkit-scrollbar {
    width: 8px;
  }
  
  &::-webkit-scrollbar-track {
    background: transparent;
  }
  
  &::-webkit-scrollbar-thumb {
    background: rgba(0, 0, 0, 0.12);
    border-radius: 4px;
    
    &:hover {
      background: rgba(0, 0, 0, 0.2);
    }
  }
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 400px;
  text-align: center;
  
  .el-icon {
    margin-bottom: 24px;
    color: #c9cdd4;
  }
  
  h2 {
    font-size: 24px;
    font-weight: 600;
    color: #1d2129;
    margin-bottom: 12px;
    letter-spacing: -0.4px;
  }
  
  p {
    color: #86909c;
    margin-bottom: 32px;
    font-size: 15px;
  }
}

.quick-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  justify-content: center;
  max-width: 600px;
  
  .el-button {
    border-radius: 20px;
    padding: 10px 20px;
    height: auto;
    border: 1px solid rgba(0, 0, 0, 0.06);
    background: white;
    color: #4e5969;
    font-weight: 500;
    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
    transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
    
    &:hover {
      transform: translateY(-2px);
      box-shadow: 0 6px 20px rgba(0, 0, 0, 0.08);
      border-color: rgba(102, 126, 234, 0.3);
      color: #667eea;
    }
  }
}

.input-area {
  padding: 20px 24px;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(20px);
  border-top: 1px solid rgba(0, 0, 0, 0.04);
  box-shadow: 0 -4px 20px rgba(0, 0, 0, 0.02);
  
  .el-textarea {
    .el-textarea__inner {
      border-radius: 16px;
      border: 1px solid rgba(0, 0, 0, 0.08);
      padding: 14px 16px;
      font-size: 15px;
      box-shadow: 0 2px 8px rgba(0, 0, 0, 0.03);
      transition: all 0.2s;
      resize: none;
      
      &:focus {
        border-color: #667eea;
        box-shadow: 0 0 0 4px rgba(102, 126, 234, 0.1);
      }
      
      &::placeholder {
        color: #c9cdd4;
      }
    }
  }
  
  .input-actions {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-top: 12px;
    
    .hint {
      color: #aeb4c0;
      font-size: 13px;
    }
    
    .el-button {
      border-radius: 12px;
      height: 40px;
      padding: 0 28px;
      font-weight: 600;
      background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
      border: none;
      box-shadow: 0 4px 14px rgba(102, 126, 234, 0.35);
      transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
      
      &:hover {
        transform: translateY(-2px);
        box-shadow: 0 6px 20px rgba(102, 126, 234, 0.45);
      }
      
      &:active {
        transform: translateY(0);
      }
      
      &.is-disabled {
        opacity: 0.5;
        cursor: not-allowed;
        transform: none;
        box-shadow: 0 2px 8px rgba(102, 126, 234, 0.2);
      }
    }
  }
}
</style>
