<template>
  <div class="knowledge-base">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>知识库管理</span>
          <el-button type="primary" @click="showUploadDialog = true">
            <el-icon><Upload /></el-icon>
            上传文档
          </el-button>
        </div>
      </template>
      
      <!-- 搜索栏 -->
      <div class="search-bar">
        <el-input
          v-model="searchKeyword"
          placeholder="搜索文档..."
          clearable
          style="width: 300px"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
      </div>
      
      <!-- 文档列表 -->
      <el-table :data="documents" stripe>
        <el-table-column prop="title" label="标题" min-width="200" />
        <el-table-column prop="source" label="来源" width="200" />
        <el-table-column prop="category" label="分类" width="120" />
        <el-table-column prop="wordCount" label="字数" width="100" />
        <el-table-column prop="chunkCount" label="切片数" width="100" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary">查看</el-button>
            <el-button text type="danger" @click="deleteDocument(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
    
    <!-- 上传对话框 -->
    <el-dialog v-model="showUploadDialog" title="上传文档" width="600px">
      <el-form :model="uploadForm" label-width="80px">
        <el-form-item label="标题">
          <el-input v-model="uploadForm.title" placeholder="请输入文档标题" />
        </el-form-item>
        <el-form-item label="来源">
          <el-input v-model="uploadForm.source" placeholder="文档来源（URL 或文件名）" />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="uploadForm.category" placeholder="选择分类">
            <el-option label="运维手册" value="manual" />
            <el-option label="故障案例" value="case" />
            <el-option label="最佳实践" value="practice" />
            <el-option label="其他" value="other" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容">
          <el-input
            v-model="uploadForm.content"
            type="textarea"
            :rows="10"
            placeholder="请输入文档内容..."
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showUploadDialog = false">取消</el-button>
        <el-button type="primary" @click="uploadDocument">上传</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Upload, Search } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import type { Document } from '@/types'

const searchKeyword = ref('')
const showUploadDialog = ref(false)

const uploadForm = ref({
  title: '',
  source: '',
  category: 'manual',
  content: '',
})

// 模拟文档数据
const documents = ref<Document[]>([
  {
    id: '1',
    title: 'Linux 服务器运维手册',
    source: 'https://example.com/linux-ops',
    contentType: 'markdown',
    category: 'manual',
    wordCount: 15000,
    chunkCount: 45,
    status: 'completed',
    createdAt: new Date(),
  },
  {
    id: '2',
    title: 'Nginx 性能调优指南',
    source: 'https://example.com/nginx-tuning',
    contentType: 'markdown',
    category: 'practice',
    wordCount: 8000,
    chunkCount: 25,
    status: 'completed',
    createdAt: new Date(Date.now() - 86400000),
  },
])

// 获取状态类型
function getStatusType(status: string): 'success' | 'warning' | 'danger' {
  switch (status) {
    case 'completed': return 'success'
    case 'processing': return 'warning'
    default: return 'danger'
  }
}

// 获取状态文本
function getStatusText(status: string): string {
  switch (status) {
    case 'completed': return '已完成'
    case 'processing': return '处理中'
    default: return '失败'
  }
}

// 格式化时间
function formatTime(time: Date): string {
  return dayjs(time).format('YYYY-MM-DD HH:mm')
}

// 上传文档
async function uploadDocument() {
  if (!uploadForm.value.title || !uploadForm.value.content) {
    ElMessage.warning('请填写完整信息')
    return
  }
  
  // 模拟上传
  ElMessage.success('文档上传成功')
  showUploadDialog.value = false
  
  // 重置表单
  uploadForm.value = {
    title: '',
    source: '',
    category: 'manual',
    content: '',
  }
}

// 删除文档
async function deleteDocument(doc: Document) {
  try {
    await ElMessageBox.confirm(`确定要删除文档「${doc.title}」吗？`, '提示', {
      type: 'warning',
    })
    // 删除文档
    const index = documents.value.findIndex(d => d.id === doc.id)
    if (index > -1) {
      documents.value.splice(index, 1)
    }
    ElMessage.success('删除成功')
  } catch {
    // 取消
  }
}
</script>

<style scoped lang="scss">
.knowledge-base {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.search-bar {
  margin-bottom: 16px;
}
</style>
