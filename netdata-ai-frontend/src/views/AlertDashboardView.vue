<template>
  <div class="alert-dashboard">
    <div class="page-header">
      <h2>告警仪表板</h2>
      <el-button @click="refreshAll">
        <el-icon><Refresh /></el-icon>
        刷新
      </el-button>
    </div>

    <!-- 统计卡片 -->
    <el-row :gutter="16">
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-value">{{ stats.total ?? '-' }}</div>
          <div class="stat-label">总告警数</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card firing">
          <div class="stat-value">{{ stats.firing ?? '-' }}</div>
          <div class="stat-label">正在告警</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card resolved">
          <div class="stat-value">{{ stats.resolved ?? '-' }}</div>
          <div class="stat-label">已恢复</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card critical">
          <div class="stat-value">{{ stats.critical ?? '-' }}</div>
          <div class="stat-label">严重告警</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 告警列表 -->
    <el-card class="alert-list-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>告警列表</span>
          <div class="filter-actions">
            <el-input
              v-model="filterKeyword"
              placeholder="关键字搜索"
              size="small"
              clearable
              style="width: 180px"
              @keyup.enter="loadAlerts"
              @clear="loadAlerts"
            />
            <el-input
              v-model="filterHost"
              placeholder="主机"
              size="small"
              clearable
              style="width: 140px"
              @keyup.enter="loadAlerts"
              @clear="loadAlerts"
            />
            <el-select
              v-model="filterStatus"
              placeholder="状态"
              size="small"
              clearable
              style="width: 120px"
              @change="loadAlerts"
            >
              <el-option label="全部" value="" />
              <el-option label="告警中" value="firing" />
              <el-option label="已恢复" value="resolved" />
            </el-select>
            <el-select
              v-model="filterSeverity"
              placeholder="级别"
              size="small"
              clearable
              style="width: 120px"
              @change="loadAlerts"
            >
              <el-option label="全部" value="" />
              <el-option label="严重" value="critical" />
              <el-option label="警告" value="warning" />
              <el-option label="信息" value="info" />
            </el-select>
            <el-button type="primary" size="small" @click="loadAlerts">查询</el-button>
          </div>
        </div>
      </template>

      <el-table :data="alerts" stripe v-loading="loading" empty-text="暂无告警数据">
        <el-table-column prop="alertName" label="告警名称" min-width="200" show-overflow-tooltip />
        <el-table-column prop="host" label="主机" width="140" />
        <el-table-column prop="metricName" label="指标" width="150" show-overflow-tooltip />
        <el-table-column label="当前/阈值" width="140">
          <template #default="{ row }">
            <span>{{ row.metricValue || '-' }} / {{ row.threshold || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="级别" width="90">
          <template #default="{ row }">
            <el-tag :type="getSeverityType(row.severity)" size="small">
              {{ row.severity }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'firing' ? 'danger' : 'success'" size="small">
              {{ row.status === 'firing' ? '告警中' : '已恢复' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="170">
          <template #default="{ row }">
            {{ formatTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" size="small" @click="diagnoseAlert(row)">
              AI 诊断
            </el-button>
            <el-button text type="primary" size="small" @click="viewDetail(row)">
              详情
            </el-button>
            <el-button
              v-if="row.status === 'firing'"
              text
              type="success"
              size="small"
              @click="resolveAlert(row)"
            >
              确认解决
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination">
        <el-pagination
          v-model:current-page="page.current"
          v-model:page-size="page.size"
          :total="page.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="loadAlerts"
          @size-change="loadAlerts"
        />
      </div>
    </el-card>

    <!-- 详情对话框 -->
    <el-dialog v-model="detailVisible" title="告警详情" width="640px">
      <el-descriptions v-if="currentAlert" :column="2" border>
        <el-descriptions-item label="告警ID">{{ currentAlert.alertId }}</el-descriptions-item>
        <el-descriptions-item label="来源">{{ currentAlert.source }}</el-descriptions-item>
        <el-descriptions-item label="告警名称" :span="2">{{ currentAlert.alertName }}</el-descriptions-item>
        <el-descriptions-item label="主机">{{ currentAlert.host }}</el-descriptions-item>
        <el-descriptions-item label="指标">{{ currentAlert.metricName }}</el-descriptions-item>
        <el-descriptions-item label="当前值">{{ currentAlert.metricValue }}</el-descriptions-item>
        <el-descriptions-item label="阈值">{{ currentAlert.threshold }}</el-descriptions-item>
        <el-descriptions-item label="级别">
          <el-tag :type="getSeverityType(currentAlert.severity)">{{ currentAlert.severity }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="currentAlert.status === 'firing' ? 'danger' : 'success'">
            {{ currentAlert.status === 'firing' ? '告警中' : '已恢复' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="描述" :span="2">{{ currentAlert.message }}</el-descriptions-item>
        <el-descriptions-item v-if="currentAlert.diagnosisResult" label="诊断结果" :span="2">
          <pre class="diag">{{ currentAlert.diagnosisResult }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatTime(currentAlert.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="解决时间">
          {{ currentAlert.resolvedAt ? formatTime(currentAlert.resolvedAt) : '-' }}
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import apiClient, { alertApi } from '@/api'

interface AlertRecord {
  id: number
  alertId: string
  source: string
  severity: string
  alertName: string
  message: string
  host: string
  metricName: string
  metricValue: string
  threshold: string
  status: string
  diagnosisResult?: string
  resolvedAt?: string
  createdAt: string
}

const router = useRouter()

const loading = ref(false)
const alerts = ref<AlertRecord[]>([])
const stats = ref<Record<string, any>>({})

const filterStatus = ref('')
const filterSeverity = ref('')
const filterHost = ref('')
const filterKeyword = ref('')

const page = reactive({ current: 1, size: 10, total: 0 })

const detailVisible = ref(false)
const currentAlert = ref<AlertRecord | null>(null)

onMounted(() => {
  refreshAll()
})

async function refreshAll() {
  await Promise.all([loadAlerts(), loadStats()])
}

async function loadAlerts() {
  loading.value = true
  try {
    const res: any = await alertApi.getAlerts({
      current: page.current,
      size: page.size,
      status: filterStatus.value || undefined,
      severity: filterSeverity.value || undefined,
      host: filterHost.value || undefined,
      keyword: filterKeyword.value || undefined,
    } as any)
    alerts.value = res?.records || []
    page.total = res?.total || 0
  } catch {
    alerts.value = []
    page.total = 0
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  try {
    const res: any = await alertApi.getStats()
    stats.value = res || {}
  } catch {
    stats.value = {}
  }
}

function getSeverityType(severity: string): 'danger' | 'warning' | 'info' | 'success' {
  switch (severity) {
    case 'critical': return 'danger'
    case 'warning': return 'warning'
    case 'info': return 'info'
    default: return 'info'
  }
}

function formatTime(time: string | Date): string {
  if (!time) return '-'
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

function diagnoseAlert(alert: AlertRecord) {
  router.push({
    path: '/chat',
    query: { q: `诊断告警：${alert.alertName}，主机：${alert.host}` },
  })
}

function viewDetail(alert: AlertRecord) {
  currentAlert.value = alert
  detailVisible.value = true
}

async function resolveAlert(alert: AlertRecord) {
  try {
    const { value } = await ElMessageBox.prompt('确认已解决该告警？可填写处置说明', '告警处置', {
      inputPlaceholder: '例如：已重启服务，指标已恢复',
      inputValue: '',
    })
    await apiClient.put(`/alerts/${alert.id}/resolve`, { diagnosisResult: value || '已处置' })
    ElMessage.success('已标记为解决')
    await refreshAll()
  } catch {
    /* cancelled */
  }
}
</script>

<style scoped lang="scss">
.alert-dashboard {
  padding: 20px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;

  h2 {
    margin: 0;
    font-size: 20px;
  }
}

.stat-card {
  text-align: center;

  :deep(.el-card__body) {
    padding: 20px;
  }

  .stat-value {
    font-size: 28px;
    font-weight: 600;
    color: #303133;
  }

  .stat-label {
    font-size: 13px;
    color: #909399;
    margin-top: 6px;
  }

  &.firing .stat-value {
    color: #f56c6c;
  }

  &.resolved .stat-value {
    color: #67c23a;
  }

  &.critical .stat-value {
    color: #e6a23c;
  }
}

.alert-list-card {
  margin-top: 16px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.filter-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}

.pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.diag {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 13px;
  font-family: inherit;
}
</style>
