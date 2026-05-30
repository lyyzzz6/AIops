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
  gap: 16px;
  margin-bottom: 28px;
  max-width: 850px;
  
  &.user {
    flex-direction: row-reverse;
    margin-left: auto;
    
    .avatar {
      background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
      box-shadow: 0 4px 14px rgba(102, 126, 234, 0.3);
    }
    .message-content {
      background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
      color: white;
      box-shadow: 0 8px 24px rgba(102, 126, 234, 0.25);
    }
    .role-label {
      color: rgba(255, 255, 255, 0.9);
      
      .time {
        color: rgba(255, 255, 255, 0.7);
      }
    }
    .content-text {
      color: white;
    }
  }
  
  &.assistant {
    .avatar {
      background: linear-gradient(135deg, #11998e 0%, #38ef7d 100%);
      box-shadow: 0 4px 14px rgba(17, 153, 142, 0.25);
    }
    .message-content {
      background: rgba(255, 255, 255, 0.95);
      backdrop-filter: blur(10px);
      box-shadow: 0 4px 20px rgba(0, 0, 0, 0.06);
    }
  }
}

.avatar {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex-shrink: 0;
  font-size: 20px;
}

.message-content {
  flex: 1;
  min-width: 0;
  border-radius: 18px;
  padding: 18px 22px;
  transition: transform 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  
  &:hover {
    transform: translateY(-1px);
  }
}

.role-label {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 10px;
  color: #1d2129;
  letter-spacing: -0.2px;
  
  .time {
    font-weight: normal;
    color: #86909c;
    font-size: 13px;
    margin-left: 10px;
  }
}

.content-text {
  font-size: 15px;
  line-height: 1.7;
  color: #1d2129;
}

.loading-dots {
  display: flex;
  gap: 6px;
  
  span {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
    animation: bounce 1.4s infinite ease-in-out both;
    
    &:nth-child(1) { animation-delay: -0.32s; }
    &:nth-child(2) { animation-delay: -0.16s; }
  }
}

@keyframes bounce {
  0%, 80%, 100% { transform: scale(0.6); }
  40% { transform: scale(1); }
}

.error-message {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 18px;
  background: rgba(245, 108, 108, 0.08);
  border-radius: 12px;
  color: #f56c6c;
  border: 1px solid rgba(245, 108, 108, 0.15);
  
  .el-button {
    color: #f56c6c;
    font-weight: 500;
  }
}

.empty-content {
  .text-muted {
    color: #86909c;
    font-size: 14px;
    font-style: italic;
  }
}

.markdown-body {
  :deep(pre) {
    background: #0f172a;
    border-radius: 14px;
    padding: 18px;
    overflow-x: auto;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
    
    code {
      font-family: 'SF Mono', 'Fira Code', monospace;
      font-size: 13px;
      line-height: 1.6;
    }
  }
  
  :deep(code:not(.hljs code)) {
    background: rgba(102, 126, 234, 0.08);
    padding: 3px 8px;
    border-radius: 6px;
    font-family: 'SF Mono', 'Fira Code', monospace;
    font-size: 13px;
    color: #667eea;
  }
  
  :deep(h1), :deep(h2), :deep(h3) {
    margin: 18px 0 10px;
    font-weight: 600;
    color: #1d2129;
  }
  
  :deep(ul), :deep(ol) {
    padding-left: 24px;
    margin: 10px 0;
  }
  
  :deep(p) {
    margin: 10px 0;
  }
  
  :deep(a) {
    color: #667eea;
    text-decoration: none;
    font-weight: 500;
    
    &:hover {
      text-decoration: underline;
    }
  }
}

.thinking-box {
  background: linear-gradient(135deg, #fffbe6 0%, #fff5d8 100%);
  border: 1px solid rgba(250, 173, 20, 0.2);
  border-radius: 14px;
  padding: 14px 16px;
  margin-bottom: 14px;
  box-shadow: 0 2px 8px rgba(250, 173, 20, 0.08);
}

.thinking-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: #d48806;
  margin-bottom: 10px;
}

.thinking-content {
  font-size: 14px;
  color: #8b7355;
  line-height: 1.7;
  font-style: italic;
}

.sources {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid rgba(0, 0, 0, 0.06);
}

.sources-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  color: #86909c;
  margin-bottom: 10px;
  font-weight: 500;
}

.source-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.source-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 14px;
  padding: 10px 14px;
  background: rgba(102, 126, 234, 0.04);
  border-radius: 10px;
  border: 1px solid rgba(102, 126, 234, 0.08);
  
  .source-title {
    flex: 1;
    color: #1d2129;
    font-weight: 500;
  }
  
  .source-score {
    color: #86909c;
    font-size: 13px;
  }
}

.commands {
  margin-top: 20px;
  padding: 16px;
  background: linear-gradient(135deg, rgba(102, 126, 234, 0.05) 0%, rgba(118, 74, 162, 0.05) 100%);
  border-radius: 14px;
  border: 1px solid rgba(102, 126, 234, 0.1);
}

.commands-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  color: #667eea;
  margin-bottom: 14px;
  font-weight: 600;
}

.command-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.command-item {
  padding: 16px;
  background: white;
  border-radius: 12px;
  border: 1px solid rgba(0, 0, 0, 0.06);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.03);
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
  
  &:hover {
    transform: translateY(-2px);
    box-shadow: 0 6px 16px rgba(0, 0, 0, 0.06);
    border-color: rgba(102, 126, 234, 0.15);
  }
}

.command-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.command-code {
  flex: 1;
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 14px;
  background: #0f172a;
  color: #e2e8f0;
  padding: 8px 12px;
  border-radius: 8px;
  overflow-x: auto;
}

.command-desc {
  font-size: 14px;
  color: #4e5969;
  margin-bottom: 12px;
}

.command-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  
  .el-button {
    border-radius: 10px;
    height: 36px;
    padding: 0 18px;
    font-weight: 500;
    
    &.el-button--primary {
      background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
      border: none;
      box-shadow: 0 2px 8px rgba(102, 126, 234, 0.25);
      
      &:hover {
        transform: translateY(-1px);
        box-shadow: 0 4px 12px rgba(102, 126, 234, 0.35);
      }
    }
  }
}
</style>
