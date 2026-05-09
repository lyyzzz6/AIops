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
          :class="{ active: conv.id === chat.currentConversationId }"
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
import { ref, nextTick, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Delete, ChatDotRound, Promotion, Fold, Expand } from '@element-plus/icons-vue'
import { useChatStore, useSettingsStore } from '@/stores'
import MessageItem from '@/components/MessageItem.vue'

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
  '查看 nginx 服务状态',
]

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

// 清空对话
async function clearChat() {
  try {
    await ElMessageBox.confirm('确定要清空当前对话吗？', '提示', {
      type: 'warning',
    })
    chat.clearCurrentConversation()
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
  height: 100vh;
  background: #f5f7fa;
}

.sidebar {
  width: 260px;
  background: #fff;
  border-right: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
  transition: width 0.3s;
  
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
  border-bottom: 1px solid #e4e7ed;
}

.new-chat-btn {
  width: 100%;
}

.conversation-list {
  flex: 1;
  overflow-y: auto;
}

.conversation-item {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  cursor: pointer;
  transition: background 0.2s;
  
  &:hover {
    background: #f5f7fa;
  }
  
  &.active {
    background: #ecf5ff;
    color: #409eff;
  }
  
  .el-icon {
    margin-right: 8px;
    flex-shrink: 0;
  }
  
  .conversation-title {
    flex: 1;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 14px;
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
  padding: 12px 16px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  
  h1 {
    flex: 1;
    margin: 0 16px;
    font-size: 18px;
    font-weight: 500;
  }
}

.message-container {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
  
  &::-webkit-scrollbar {
    width: 6px;
  }
  
  &::-webkit-scrollbar-thumb {
    background: #c0c4cc;
    border-radius: 3px;
  }
}

.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  text-align: center;
  color: #909399;
  
  h2 {
    margin: 16px 0 8px;
    color: #303133;
  }
  
  .quick-actions {
    margin-top: 24px;
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    justify-content: center;
  }
}

.input-area {
  padding: 16px;
  background: #fff;
  border-top: 1px solid #e4e7ed;
}

.input-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 12px;
  
  .hint {
    color: #909399;
    font-size: 12px;
  }
}
</style>
