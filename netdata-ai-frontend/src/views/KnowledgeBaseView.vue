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
          @input="handleSearch"
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
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click="viewDocument(row)">查看</el-button>
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
    
    <!-- 查看文档对话框 -->
    <el-dialog v-model="showViewDialog" title="文档详情" width="700px">
      <el-descriptions :column="2" border v-if="currentDocument">
        <el-descriptions-item label="标题">{{ currentDocument.title }}</el-descriptions-item>
        <el-descriptions-item label="来源">{{ currentDocument.source }}</el-descriptions-item>
        <el-descriptions-item label="分类">{{ currentDocument.category || '未分类' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="getStatusType(currentDocument.status)" size="small">
            {{ getStatusText(currentDocument.status) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="字数">{{ currentDocument.wordCount }}</el-descriptions-item>
        <el-descriptions-item label="切片数">{{ currentDocument.chunkCount }}</el-descriptions-item>
        <el-descriptions-item label="创建时间" :span="2">{{ currentDocument.createdAt }}</el-descriptions-item>
      </el-descriptions>
      <el-divider>文档内容预览</el-divider>
      <div class="document-content" v-if="documentContent">{{ documentContent }}</div>
      <div v-else class="empty-content">暂无内容</div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Upload, Search } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import type { Document } from '@/types'
import { knowledgeApi } from '@/api'

const searchKeyword = ref('')
const showUploadDialog = ref(false)
const showViewDialog = ref(false)
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

const uploadForm = ref({
  title: '',
  source: '',
  category: 'manual',
  contentType: 'text',
  content: '',
})

const currentDocument = ref<Document | null>(null)
const documentContent = ref('')

const documents = ref<Document[]>([])

// 获取状态类型
function getStatusType(status: number): 'success' | 'warning' | 'danger' {
  switch (status) {
    case 1: return 'success'
    case 0: return 'warning'
    default: return 'danger'
  }
}

// 获取状态文本
function getStatusText(status: number): string {
  switch (status) {
    case 1: return '已完成'
    case 0: return '处理中'
    default: return '失败'
  }
}

// 格式化时间
function formatTime(time: string): string {
  return dayjs(time).format('YYYY-MM-DD HH:mm')
}

// 加载文档列表
async function loadDocuments() {
  loading.value = true
  try {
    const result = await knowledgeApi.getDocuments({
      current: currentPage.value,
      size: pageSize.value,
      keyword: searchKeyword.value || undefined,
    })
    documents.value = result.records || []
    total.value = result.total || 0
  } catch (error) {
    ElMessage.error('加载文档失败')
  } finally {
    loading.value = false
  }
}

// 搜索文档
function handleSearch() {
  currentPage.value = 1
  loadDocuments()
}

// 创建文档
async function uploadDocument() {
  if (!uploadForm.value.title || !uploadForm.value.content) {
    ElMessage.warning('请填写完整信息')
    return
  }
  
  loading.value = true
  try {
    await knowledgeApi.createDocument({
      title: uploadForm.value.title,
      source: uploadForm.value.source || 'manual/upload',
      contentType: uploadForm.value.contentType,
      category: uploadForm.value.category,
      content: uploadForm.value.content,
    })
    ElMessage.success('文档创建成功')
    showUploadDialog.value = false
    loadDocuments()
    
    // 重置表单
    uploadForm.value = {
      title: '',
      source: '',
      category: 'manual',
      contentType: 'text',
      content: '',
    }
  } catch (error) {
    ElMessage.error('创建文档失败')
  } finally {
    loading.value = false
  }
}

// 查看文档
async function viewDocument(doc: Document) {
  try {
    const fullDoc = await knowledgeApi.getDocumentById(doc.id)
    currentDocument.value = fullDoc
    documentContent.value = fullDoc.content || '暂无内容'
  } catch {
    currentDocument.value = doc
    documentContent.value = doc.content || '无法加载内容'
  }
  showViewDialog.value = true
}

// 删除文档
async function deleteDocument(doc: Document) {
  try {
    await ElMessageBox.confirm(`确定要删除文档「${doc.title}」吗？`, '提示', {
      type: 'warning',
    })
    await knowledgeApi.deleteDocument(doc.id as string)
    const index = documents.value.findIndex(d => d.id === doc.id)
    if (index > -1) {
      documents.value.splice(index, 1)
    }
    ElMessage.success('删除成功')
  } catch {
    // 取消
  }
}

// 初始化加载
onMounted(() => {
  loadDocuments()
})
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

.document-content {
  padding: 12px;
  background: #f5f7fa;
  border-radius: 4px;
  max-height: 400px;
  overflow-y: auto;
  white-space: pre-wrap;
  font-size: 14px;
  line-height: 1.6;
}

.empty-content {
  padding: 20px;
  text-align: center;
  color: #909399;
}
</style>
