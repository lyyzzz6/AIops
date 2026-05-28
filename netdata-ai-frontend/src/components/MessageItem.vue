<template>
  <div class="message-item" :class="message.role">
    <!-- 头像 -->
    <div class="avatar">
      <el-icon v-if="message.role === 'user'"><User /></el-icon>
      <el-icon v-else><Monitor /></el-icon>
    </div>
    
    <!-- 消息内容 -->
    <div class="message-content">
      <!-- 角色标签 -->
      <div class="role-label">
        {{ message.role === 'user' ? '我' : '智能助手' }}
        <span class="time">{{ formatTime(message.timestamp) }}</span>
      </div>
      
      <!-- 文本内容 -->
      <div class="content-text">
        <!-- Loading 状态 -->
        <div v-if="message.loading" class="loading-dots">
          <span></span>
          <span></span>
          <span></span>
        </div>
        
        <!-- 错误状态 -->
        <div v-else-if="message.error" class="error-message">
          <el-icon><WarningFilled /></el-icon>
          {{ message.error }}
          <el-button text type="primary" @click="$emit('retry')">
            重试
          </el-button>
        </div>
        
        <!-- 正常内容 -->
        <template v-else>
          <!-- 思考过程 -->
          <div v-if="message.thinking" class="thinking-box">
            <div class="thinking-header">
              <el-icon><Search /></el-icon>
              <span>思考过程</span>
            </div>
            <div class="thinking-content">
              {{ message.thinking }}
            </div>
          </div>
          
          <!-- Markdown 渲染 - 只有有内容时才显示 -->
          <div 
            v-if="message.content && message.content.trim()" 
            class="markdown-body" 
            v-html="renderedContent"
          ></div>
          
          <!-- 空内容提示 -->
          <div v-else-if="!message.loading && !message.error" class="empty-content">
            <span class="text-muted">暂无内容</span>
          </div>
          
          <!-- 来源引用 -->
          <div v-if="message.sources?.length" class="sources">
            <div class="sources-title">
              <el-icon><Link /></el-icon>
              参考来源
            </div>
            <div class="source-list">
              <div
                v-for="(source, index) in message.sources"
                :key="index"
                class="source-item"
              >
                <el-tag size="small" type="info">[{{ index + 1 }}]</el-tag>
                <span class="source-title">{{ source.title }}</span>
                <span class="source-score">相关度: {{ (source.score * 100).toFixed(0) }}%</span>
              </div>
            </div>
          </div>
          
          <!-- 建议命令 -->
          <div v-if="message.suggestedCommands?.length" class="commands">
            <div class="commands-title">
              <el-icon><Platform /></el-icon>
              建议执行的命令
            </div>
            <div class="command-list">
              <div
                v-for="(cmd, index) in message.suggestedCommands"
                :key="index"
                class="command-item"
              >
                <div class="command-header">
                  <code class="command-code">{{ cmd.command }}</code>
                  <el-tag
                    :type="getRiskType(cmd.riskLevel)"
                    size="small"
                  >
                    {{ cmd.riskLevel }}
                  </el-tag>
                </div>
                <div class="command-desc">{{ cmd.description }}</div>
                <div class="command-actions">
                  <el-button
                    v-if="!cmd.requiresApproval"
                    size="small"
                    type="primary"
                    @click="$emit('executeCommand', cmd)"
                  >
                    发起执行
                  </el-button>
                  <template v-else>
                    <el-button
                      size="small"
                      :type="submitBtnType(cmd)"
                      :loading="cmd.submitStatus === 'submitting'"
                      :disabled="cmd.submitStatus === 'submitting' || cmd.submitStatus === 'submitted'"
                      @click="$emit('executeCommand', cmd)"
                    >
                      {{ submitBtnLabel(cmd) }}
                    </el-button>
                    <el-button
                      size="small"
                      type="primary"
                      plain
                      @click="$emit('openApproval', cmd)"
                    >
                      前往审批中心
                    </el-button>
                    <el-tag
                      v-if="cmd.submitStatus === 'submitted' && cmd.auditRequestId"
                      size="small"
                      :type="auditTagType(cmd.auditStatus)"
                    >
                      {{ auditStatusLabel(cmd.auditStatus) }} {{ cmd.auditRequestId }}
                    </el-tag>
                  </template>
                </div>
              </div>
            </div>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { User, Monitor, WarningFilled, Link, Platform, Search } from '@element-plus/icons-vue'
import type { Message, CommandSuggestion } from '@/types'
import dayjs from 'dayjs'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'

// Props
const props = defineProps<{
  message: Message
}>()

// Emits
defineEmits<{
  retry: []
  executeCommand: [cmd: CommandSuggestion]
  openApproval: [cmd: CommandSuggestion]
}>()

// Markdown 渲染器
const md: MarkdownIt = new MarkdownIt({
  html: false,
  linkify: true,
  highlight: (str, lang) => {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre class="hljs"><code>${hljs.highlight(str, { language: lang }).value}</code></pre>`
      } catch {
        // ignore
      }
    }
    return `<pre class="hljs"><code>${md.utils.escapeHtml(str)}</code></pre>`
  },
})

// 渲染后的内容 - 每次都重新渲染以确保实时更新
const renderedContent = computed(() => {
  const content = props.message.content || ''
  return md.render(content)
})

// 格式化时间
function formatTime(timestamp: Date): string {
  return dayjs(timestamp).format('HH:mm')
}

// 获取风险等级类型
function getRiskType(level: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (level) {
    case 'LOW': return 'success'
    case 'MEDIUM': return 'warning'
    case 'HIGH': return 'danger'
    default: return 'info'
  }
}

// 审批按钮文案
function submitBtnLabel(cmd: CommandSuggestion): string {
  switch (cmd.submitStatus) {
    case 'submitting': return '提交中…'
    case 'submitted': return '已提交'
    case 'failed': return '重新申请'
    default: return '申请审批'
  }
}

// 审批按钮颜色
function submitBtnType(cmd: CommandSuggestion): 'warning' | 'success' | 'danger' | 'info' {
  switch (cmd.submitStatus) {
    case 'submitted': return 'success'
    case 'failed': return 'danger'
    default: return 'warning'
  }
}

// 审计状态标签
function auditStatusLabel(st?: string): string {
  switch (st) {
    case 'pending': return '待审批'
    case 'approved': return '已批准'
    case 'rejected': return '已拒绝'
    case 'executing': return '执行中'
    case 'completed': return '已完成'
    case 'failed': return '失败'
    default: return '已提交'
  }
}

function auditTagType(st?: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (st) {
    case 'approved':
    case 'completed': return 'success'
    case 'rejected':
    case 'failed': return 'danger'
    case 'pending':
    case 'executing': return 'warning'
    default: return 'info'
  }
}
</script>

<style scoped lang="scss">
.message-item {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  
  &.user {
    .avatar {
      background: #409eff;
    }
    .message-content {
      background: #ecf5ff;
    }
  }
  
  &.assistant {
    .avatar {
      background: #67c23a;
    }
    .message-content {
      background: #fff;
    }
  }
}

.avatar {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex-shrink: 0;
}

.message-content {
  flex: 1;
  min-width: 0;
  border-radius: 8px;
  padding: 12px 16px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.05);
}

.role-label {
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 8px;
  color: #303133;
  
  .time {
    font-weight: normal;
    color: #909399;
    font-size: 12px;
    margin-left: 8px;
  }
}

.content-text {
  font-size: 14px;
  line-height: 1.6;
  color: #303133;
}

.loading-dots {
  display: flex;
  gap: 4px;
  
  span {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: #409eff;
    animation: bounce 1.4s infinite ease-in-out both;
    
    &:nth-child(1) { animation-delay: -0.32s; }
    &:nth-child(2) { animation-delay: -0.16s; }
  }
}

@keyframes bounce {
  0%, 80%, 100% { transform: scale(0); }
  40% { transform: scale(1); }
}

.error-message {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #f56c6c;
}

.empty-content {
  .text-muted {
    color: #909399;
    font-size: 13px;
    font-style: italic;
  }
}

.markdown-body {
  :deep(pre) {
    background: #1e1e1e;
    border-radius: 8px;
    padding: 16px;
    overflow-x: auto;
    
    code {
      font-family: 'Fira Code', monospace;
      font-size: 13px;
    }
  }
  
  :deep(code:not(.hljs code)) {
    background: #f5f7fa;
    padding: 2px 6px;
    border-radius: 4px;
    font-family: 'Fira Code', monospace;
    font-size: 13px;
  }
  
  :deep(h1), :deep(h2), :deep(h3) {
    margin: 16px 0 8px;
  }
  
  :deep(ul), :deep(ol) {
    padding-left: 20px;
  }
  
  :deep(p) {
    margin: 8px 0;
  }
}

.thinking-box {
  background: #fffbe6;
  border: 1px solid #ffe58f;
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
}

.thinking-header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 500;
  color: #faad14;
  margin-bottom: 8px;
}

.thinking-content {
  font-size: 13px;
  color: #8b7355;
  line-height: 1.6;
  font-style: italic;
}

.sources {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px solid #e4e7ed;
}

.sources-title {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #909399;
  margin-bottom: 8px;
}

.source-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.source-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  
  .source-title {
    flex: 1;
    color: #303133;
  }
  
  .source-score {
    color: #909399;
    font-size: 12px;
  }
}

.commands {
  margin-top: 16px;
  padding: 12px;
  background: #fafafa;
  border-radius: 8px;
}

.commands-title {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #909399;
  margin-bottom: 12px;
}

.command-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.command-item {
  padding: 12px;
  background: #fff;
  border-radius: 6px;
  border: 1px solid #e4e7ed;
}

.command-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.command-code {
  flex: 1;
  font-family: 'Fira Code', monospace;
  font-size: 13px;
  background: #f5f7fa;
  padding: 4px 8px;
  border-radius: 4px;
}

.command-desc {
  font-size: 13px;
  color: #606266;
  margin-bottom: 8px;
}

.command-actions {
  display: flex;
  gap: 8px;
}
</style>
