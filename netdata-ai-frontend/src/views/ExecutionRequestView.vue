<template>
  <div class="execution-request">
    <div class="page-header">
      <h2>命令执行</h2>
      <el-button @click="loadMyExecutions"><el-icon><Refresh /></el-icon>刷新</el-button>
    </div>

    <!-- 申请表单 -->
    <el-card shadow="never" class="form-card">
      <template #header>
        <div class="card-header">
          <span>提交执行请求</span>
          <el-button
            size="small"
            type="info"
            plain
            :disabled="!form.command"
            :loading="assessing"
            @click="handleRiskAssess"
          >
            风险预评估
          </el-button>
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="命令" prop="command">
          <el-input
            v-model="form.command"
            type="textarea"
            :rows="3"
            placeholder="示例: top -n 1 | head -20"
          />
        </el-form-item>
        <el-form-item label="命令类型">
          <el-select v-model="form.commandType" placeholder="自动识别" clearable style="width: 200px">
            <el-option label="Shell" value="shell" />
            <el-option label="SQL" value="sql" />
            <el-option label="HTTP" value="http" />
            <el-option label="Docker" value="docker" />
            <el-option label="Kubernetes" value="kubernetes" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标主机" required>
          <el-input v-model="form.targetHost" placeholder="如 node-01" style="width: 300px" />
        </el-form-item>

        <!-- 风险评估结果 -->
        <el-alert
          v-if="riskResult"
          :title="`风险等级：${riskResult.riskLevel}  |  风险分数：${riskResult.riskScore}  |  ${getStatusText(riskResult)}`"
          :type="riskAlertType(riskResult.riskLevel)"
          show-icon
          style="margin-bottom: 16px"
          :closable="false"
        />

        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="handleSubmit">提交</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 执行记录 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>我的执行记录</span>
          <el-radio-group v-model="listFilter" size="small" @change="loadMyExecutions">
            <el-radio-button label="">全部</el-radio-button>
            <el-radio-button label="pending">待审批</el-radio-button>
            <el-radio-button label="approved">已通过</el-radio-button>
            <el-radio-button label="completed">已完成</el-radio-button>
            <el-radio-button label="rejected">已拒绝</el-radio-button>
          </el-radio-group>
        </div>
      </template>

      <el-table :data="list" v-loading="loading" stripe>
        <el-table-column prop="requestId" label="申请号" width="140" />
        <el-table-column label="命令" min-width="260">
          <template #default="{ row }">
            <code class="code-inline">{{ row.command }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="targetHost" label="主机" width="120" />
        <el-table-column label="风险" width="100">
          <template #default="{ row }">
            <el-tag :type="riskTag(row.riskLevel)" size="small">{{ row.riskLevel }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="提交时间" width="170" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="showDetail(row)">详情</el-button>
            <el-button
              v-if="row.status === 'approved'"
              type="success"
              size="small"
              :loading="executingId === row.id"
              @click="handleExecute(row)"
            >
              执行
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="page.current"
          v-model:page-size="page.size"
          :total="page.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @size-change="loadMyExecutions"
          @current-change="loadMyExecutions"
        />
      </div>
    </el-card>

    <!-- 详情 -->
    <el-dialog v-model="detailVisible" title="执行详情" width="680px">
      <el-descriptions v-if="current" :column="2" border>
        <el-descriptions-item label="申请号">{{ current.requestId }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ statusLabel(current.status) }}</el-descriptions-item>
        <el-descriptions-item label="风险等级">{{ current.riskLevel }}</el-descriptions-item>
        <el-descriptions-item label="风险分数">{{ current.riskScore }}</el-descriptions-item>
        <el-descriptions-item label="命令类型">{{ current.commandType || '-' }}</el-descriptions-item>
        <el-descriptions-item label="目标主机">{{ current.targetHost || '-' }}</el-descriptions-item>
        <el-descriptions-item label="提交时间">{{ current.createdAt }}</el-descriptions-item>
        <el-descriptions-item label="执行时间">{{ current.executedAt || '-' }}</el-descriptions-item>
        <el-descriptions-item label="命令" :span="2">
          <pre class="code-block">{{ current.command }}</pre>
        </el-descriptions-item>
        <el-descriptions-item v-if="current.executionResult" label="执行结果" :span="2">
          <pre class="code-block">{{ current.executionResult }}</pre>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { executionApi } from '@/api'
import type { ExecutionAuditDTO } from '@/types'

const route = useRoute()

const formRef = ref<FormInstance>()
const form = reactive({
  command: '',
  commandType: '',
  targetHost: 'localhost',
})
const rules: FormRules = {
  command: [{ required: true, message: '请输入要执行的命令', trigger: 'blur' }],
}

const submitting = ref(false)
const assessing = ref(false)
const riskResult = ref<{ riskLevel: string; riskScore: number; status: string; commandType: string } | null>(null)

const loading = ref(false)
const list = ref<ExecutionAuditDTO[]>([])
const listFilter = ref<'' | 'pending' | 'approved' | 'rejected' | 'completed'>('')
const page = reactive({ current: 1, size: 10, total: 0 })

const detailVisible = ref(false)
const current = ref<ExecutionAuditDTO | null>(null)
const executingId = ref<number | null>(null)

onMounted(() => {
  // 从 query 预填命令（由 MessageItem 的「发起执行」跳转而来）
  if (route.query.command) {
    form.command = String(route.query.command)
  }
  loadMyExecutions()
})

watch(() => route.query.command, (v) => {
  if (v) form.command = String(v)
})

async function handleRiskAssess() {
  if (!form.command) return
  assessing.value = true
  try {
    riskResult.value = await executionApi.riskAssess(form.command)
  } finally {
    assessing.value = false
  }
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await executionApi.submit({
      command: form.command,
      commandType: form.commandType || undefined,
      targetHost: form.targetHost || undefined,
    })
    ElMessage.success('已提交，等待审批或可直接执行')
    handleReset()
    loadMyExecutions()
  } finally {
    submitting.value = false
  }
}

function handleReset() {
  form.command = ''
  form.commandType = ''
  form.targetHost = 'localhost'
  riskResult.value = null
  formRef.value?.clearValidate()
}

async function loadMyExecutions() {
  loading.value = true
  try {
    const res = await executionApi.list({
      current: page.current,
      size: page.size,
      status: listFilter.value || undefined,
    })
    list.value = res?.records || []
    page.total = res?.total || 0
  } finally {
    loading.value = false
  }
}

function showDetail(row: ExecutionAuditDTO) {
  current.value = row
  detailVisible.value = true
}

async function handleExecute(row: ExecutionAuditDTO) {
  try {
    await ElMessageBox.confirm(`确认执行命令？\n${row.command}`, '执行确认', { type: 'warning' })
    executingId.value = row.id
    // 调用审批接口，后端会自动执行命令并回填结果
    await executionApi.approve(row.id)
    ElMessage.success('已触发执行')
    loadMyExecutions()
  } catch { /* cancelled */ } finally {
    executingId.value = null
  }
}

function riskTag(level: string): 'success' | 'warning' | 'danger' | 'info' {
  if (level === 'high' || level === 'critical') return 'danger'
  if (level === 'medium') return 'warning'
  if (level === 'low') return 'success'
  return 'info'
}

function riskAlertType(level: string): 'success' | 'warning' | 'error' | 'info' {
  if (level === 'high' || level === 'critical') return 'error'
  if (level === 'medium') return 'warning'
  if (level === 'low') return 'success'
  return 'info'
}

function statusLabel(s: string) {
  return ({
    pending: '待审批',
    approved: '已通过',
    rejected: '已拒绝',
    executing: '执行中',
    completed: '已完成',
    failed: '失败',
  } as any)[s] || s
}

function getStatusText(riskResult: any): string {
  if (riskResult.riskLevel === 'CRITICAL') {
    return '已拦截，禁止执行'
  }
  if (riskResult.status === 'pending') {
    return '需要审批'
  }
  if (riskResult.status === 'approved' || riskResult.riskLevel === 'LOW') {
    return '可直接执行'
  }
  return statusLabel(riskResult.status)
}

function statusTag(s: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (s === 'completed' || s === 'approved') return 'success'
  if (s === 'rejected' || s === 'failed') return 'danger'
  if (s === 'pending') return 'warning'
  if (s === 'executing') return 'primary'
  return 'info'
}
</script>

<style scoped lang="scss">
.execution-request { padding: 20px; }
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  h2 { margin: 0; font-size: 20px; }
}
.form-card { margin-bottom: 12px; }
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.pagination-wrapper {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
.code-inline {
  font-family: 'Fira Code', monospace;
  font-size: 13px;
  background: #f5f7fa;
  padding: 2px 6px;
  border-radius: 4px;
}
.code-block {
  background: #f5f7fa;
  padding: 8px;
  border-radius: 4px;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-family: 'Fira Code', monospace;
  font-size: 12px;
}
</style>
