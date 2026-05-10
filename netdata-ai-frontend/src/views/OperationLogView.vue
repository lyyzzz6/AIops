<template>
  <div class="operation-log">
    <div class="page-header">
      <h2>操作审计</h2>
      <el-button @click="loadLogs"><el-icon><Refresh /></el-icon>刷新</el-button>
    </div>

    <!-- 统计卡片 -->
    <el-row :gutter="12" class="stat-row" v-if="stats">
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">总操作数</div>
          <div class="stat-num">{{ stats.total ?? '-' }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card success">
          <div class="stat-label">成功</div>
          <div class="stat-num">{{ stats.success ?? '-' }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card danger">
          <div class="stat-label">失败</div>
          <div class="stat-num">{{ stats.failed ?? '-' }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card warning">
          <div class="stat-label">今日操作</div>
          <div class="stat-num">{{ stats.today ?? '-' }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 筛选 -->
    <el-card shadow="never" class="filter-card">
      <el-form :inline="true" :model="filter">
        <el-form-item label="模块">
          <el-select v-model="filter.module" placeholder="全部" clearable style="width: 140px">
            <el-option label="用户" value="user" />
            <el-option label="角色" value="role" />
            <el-option label="审批" value="approval" />
            <el-option label="命令执行" value="execution" />
            <el-option label="知识库" value="knowledge" />
            <el-option label="登录" value="auth" />
          </el-select>
        </el-form-item>
        <el-form-item label="操作">
          <el-input v-model="filter.action" placeholder="如 CREATE" clearable style="width: 140px" />
        </el-form-item>
        <el-form-item label="用户名">
          <el-input v-model="filter.username" placeholder="用户名" clearable style="width: 140px" />
        </el-form-item>
        <el-form-item label="时间范围">
          <el-date-picker
            v-model="dateRange"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始"
            end-placeholder="结束"
            value-format="YYYY-MM-DDTHH:mm:ss"
            style="width: 360px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 列表 -->
    <el-card shadow="never">
      <el-table :data="logs" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="username" label="用户" width="100" />
        <el-table-column prop="module" label="模块" width="100" />
        <el-table-column prop="action" label="操作" width="120" />
        <el-table-column prop="target" label="目标" width="140" />
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column label="结果" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ipAddress" label="IP" width="130" />
        <el-table-column prop="executionTimeMs" label="耗时(ms)" width="90" />
        <el-table-column prop="createdAt" label="时间" width="170" />
        <el-table-column label="详情" width="80" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click="showDetail(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.current"
          v-model:page-size="pagination.size"
          :total="pagination.total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @size-change="loadLogs"
          @current-change="loadLogs"
        />
      </div>
    </el-card>

    <!-- 详情 -->
    <el-dialog v-model="detailVisible" title="操作详情" width="640px">
      <el-descriptions v-if="currentLog" :column="2" border>
        <el-descriptions-item label="ID">{{ currentLog.id }}</el-descriptions-item>
        <el-descriptions-item label="TraceID">{{ currentLog.traceId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="用户">{{ currentLog.username || '-' }}</el-descriptions-item>
        <el-descriptions-item label="IP">{{ currentLog.ipAddress || '-' }}</el-descriptions-item>
        <el-descriptions-item label="模块">{{ currentLog.module }}</el-descriptions-item>
        <el-descriptions-item label="操作">{{ currentLog.action }}</el-descriptions-item>
        <el-descriptions-item label="请求方法">{{ currentLog.requestMethod || '-' }}</el-descriptions-item>
        <el-descriptions-item label="响应码">{{ currentLog.responseCode ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="URL" :span="2">{{ currentLog.requestUrl || '-' }}</el-descriptions-item>
        <el-descriptions-item label="参数" :span="2">
          <pre class="code-block">{{ currentLog.requestParams || '-' }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="错误信息" :span="2" v-if="currentLog.errorMessage">
          <pre class="code-block error">{{ currentLog.errorMessage }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="UA" :span="2">{{ currentLog.userAgent || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { operationLogApi } from '@/api'
import type { OperationLogDTO } from '@/types'

const loading = ref(false)
const logs = ref<OperationLogDTO[]>([])
const stats = ref<Record<string, any> | null>(null)
const detailVisible = ref(false)
const currentLog = ref<OperationLogDTO | null>(null)
const dateRange = ref<[string, string] | null>(null)

const filter = reactive({
  module: '',
  action: '',
  username: '',
})

const pagination = reactive({ current: 1, size: 20, total: 0 })

onMounted(async () => {
  await Promise.all([loadLogs(), loadStats()])
})

async function loadLogs() {
  loading.value = true
  try {
    const res = await operationLogApi.list({
      current: pagination.current,
      size: pagination.size,
      module: filter.module || undefined,
      action: filter.action || undefined,
      username: filter.username || undefined,
      startTime: dateRange.value?.[0],
      endTime: dateRange.value?.[1],
    })
    logs.value = res?.records || []
    pagination.total = res?.total || 0
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  try {
    stats.value = await operationLogApi.getStats()
  } catch { /* ignore */ }
}

function handleSearch() {
  pagination.current = 1
  loadLogs()
}

function handleReset() {
  filter.module = ''
  filter.action = ''
  filter.username = ''
  dateRange.value = null
  pagination.current = 1
  loadLogs()
}

function showDetail(row: OperationLogDTO) {
  currentLog.value = row
  detailVisible.value = true
}
</script>

<style scoped lang="scss">
.operation-log { padding: 20px; }
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  h2 { margin: 0; font-size: 20px; }
}
.stat-row { margin-bottom: 12px; }
.stat-card {
  .stat-label { font-size: 13px; color: #909399; }
  .stat-num { font-size: 24px; font-weight: 600; color: #303133; margin-top: 6px; }
  &.success .stat-num { color: #67c23a; }
  &.danger .stat-num { color: #f56c6c; }
  &.warning .stat-num { color: #e6a23c; }
}
.filter-card { margin-bottom: 12px; }
.pagination-wrapper {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
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
  &.error { background: #fef0f0; color: #f56c6c; }
}
</style>
