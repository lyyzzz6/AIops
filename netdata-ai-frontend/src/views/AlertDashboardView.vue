<template>
  <div class="alert-dashboard">
    <div class="page-header">
      <div class="header-left">
        <h2>告警仪表板</h2>
      </div>
      <div class="header-right">
        <div class="current-time">{{ currentTime }}</div>
        <el-select v-model="refreshInterval" size="small" style="width: 140px" @change="handleRefreshIntervalChange">
          <el-option label="不刷新" :value="0" />
          <el-option label="30秒" :value="30000" />
          <el-option label="1分钟" :value="60000" />
          <el-option label="5分钟" :value="300000" />
        </el-select>
        <el-button @click="refreshAll">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
      </div>
    </div>

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

    <el-row :gutter="16" class="middle-section">
      <el-col :span="7">
        <el-card class="realtime-alerts-card" shadow="never">
          <template #header>
            <div class="card-header">
              <span>实时告警</span>
            </div>
          </template>
          <div 
            ref="realtimeAlertsScroll" 
            class="realtime-alerts-scroll"
            @mouseenter="pauseScroll"
            @mouseleave="resumeScroll"
          >
            <div v-for="(alert, index) in realtimeAlerts.concat(realtimeAlerts)" :key="`${alert.id}-${index}`" class="realtime-alert-item">
              <el-tag :type="getSeverityType(alert.severity)" size="small" class="alert-tag">
                {{ alert.severity }}
              </el-tag>
              <div class="alert-info">
                <div class="alert-name">{{ alert.alertName }}</div>
                <div class="alert-host">{{ alert.host }}</div>
                <div class="alert-time">{{ formatTime(alert.createdAt) }}</div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="17">
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
                <span>{{ row.metricValue ?? '-' }} / {{ row.threshold ?? '-' }}</span>
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
      </el-col>
    </el-row>

    <el-card class="trend-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span>指标趋势</span>
          <el-button link @click="trendCollapsed = !trendCollapsed">
            <el-icon v-if="trendCollapsed"><ArrowDown /></el-icon>
            <el-icon v-else><ArrowUp /></el-icon>
            {{ trendCollapsed ? '展开' : '收起' }}
          </el-button>
        </div>
      </template>
      <div v-show="!trendCollapsed" class="trend-content">
        <div ref="trendChartRef" class="trend-chart"></div>
      </div>
    </el-card>

    <div class="page-footer">
      <div class="footer-left">
        <span class="system-status">
          <el-icon class="status-icon"><CircleCheck /></el-icon>
          系统正常
        </span>
      </div>
      <div class="footer-center">
        <span>最后更新时间：{{ lastUpdateTime }}</span>
      </div>
      <div class="footer-right">
        <span>版本号：v1.0.0</span>
      </div>
    </div>

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
import { ref, reactive, onMounted, onUnmounted, computed, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh, ArrowDown, ArrowUp, CircleCheck } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import dayjs from 'dayjs'
import * as echarts from 'echarts'
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
const currentTime = ref('')
const lastUpdateTime = ref('-')
const refreshInterval = ref(30000)
const trendCollapsed = ref(false)
let refreshTimer: number | null = null
let timeTimer: number | null = null

const filterStatus = ref('')
const filterSeverity = ref('')
const filterHost = ref('')
const filterKeyword = ref('')

const page = reactive({ current: 1, size: 10, total: 0 })

const detailVisible = ref(false)
const currentAlert = ref<AlertRecord | null>(null)

const realtimeAlerts = computed(() => {
  return alerts.value.filter(a => a.status === 'firing').slice(0, 10)
})

const scrollPaused = ref(false)
const realtimeAlertsScroll = ref<HTMLElement | null>(null)
let scrollTimer: number | null = null

const trendChartRef = ref<HTMLElement | null>(null)
let trendChart: echarts.ECharts | null = null

function startScrollAnimation() {
  if (scrollTimer) window.clearInterval(scrollTimer)
  scrollTimer = window.setInterval(() => {
    if (!scrollPaused.value && realtimeAlertsScroll.value) {
      const scrollDiv = realtimeAlertsScroll.value
      if (scrollDiv.scrollTop >= scrollDiv.scrollHeight / 2 - scrollDiv.clientHeight) {
        scrollDiv.scrollTop = 0
      } else {
        scrollDiv.scrollTop += 1
      }
    }
  }, 50)
}

function pauseScroll() {
  scrollPaused.value = true
}

function resumeScroll() {
  scrollPaused.value = false
}

function updateCurrentTime() {
  currentTime.value = dayjs().format('YYYY-MM-DD HH:mm:ss')
}

function startRefreshTimer() {
  if (refreshTimer) {
    window.clearInterval(refreshTimer)
    refreshTimer = null
  }
  if (refreshInterval.value > 0) {
    refreshTimer = window.setInterval(() => {
      refreshAll()
    }, refreshInterval.value)
  }
}

function handleRefreshIntervalChange() {
  startRefreshTimer()
}

async function refreshAll() {
  await Promise.all([loadAlerts(), loadStats(), loadTrend()])
  lastUpdateTime.value = dayjs().format('YYYY-MM-DD HH:mm:ss')
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
    alerts.value = res?.records ?? []
    page.total = res?.total ?? 0
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
    stats.value = res ?? {}
  } catch {
    stats.value = {}
  }
}

async function loadTrend() {
  try {
    const res: any = await alertApi.getTrend()
    renderTrendChart(res)
  } catch {
  }
}

function renderTrendChart(data: any[]) {
  nextTick(() => {
    if (!trendChartRef.value) return

    if (!trendChart) {
      trendChart = echarts.init(trendChartRef.value)
    }

    const dates = data.map((item: any) => item.date)
    const critical = data.map((item: any) => item.critical ?? 0)
    const warning = data.map((item: any) => item.warning ?? 0)
    const info = data.map((item: any) => item.info ?? 0)

    const option = {
      tooltip: {
        trigger: 'axis',
      },
      legend: {
        data: ['严重 (critical)', '警告 (warning)', '信息 (info)'],
      },
      grid: {
        left: '3%',
        right: '4%',
        bottom: '3%',
        containLabel: true,
      },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: dates.length > 0 ? dates : [dayjs().format('YYYY-MM-DD')],
      },
      yAxis: {
        type: 'value',
      },
      series: [
        {
          name: '严重 (critical)',
          type: 'line',
          data: dates.length > 0 ? critical : [0],
          smooth: true,
          itemStyle: { color: '#f56c6c' },
        },
        {
          name: '警告 (warning)',
          type: 'line',
          data: dates.length > 0 ? warning : [0],
          smooth: true,
          itemStyle: { color: '#e6a23c' },
        },
        {
          name: '信息 (info)',
          type: 'line',
          data: dates.length > 0 ? info : [0],
          smooth: true,
          itemStyle: { color: '#409eff' },
        },
      ],
    }

    trendChart.setOption(option)
  })
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
  }
}

onMounted(() => {
  updateCurrentTime()
  timeTimer = window.setInterval(updateCurrentTime, 1000)
  refreshAll()
  startRefreshTimer()
  startScrollAnimation()
  window.addEventListener('resize', () => {
    trendChart?.resize()
  })
})

onUnmounted(() => {
  if (refreshTimer) {
    window.clearInterval(refreshTimer)
  }
  if (timeTimer) {
    window.clearInterval(timeTimer)
  }
  if (scrollTimer) {
    window.clearInterval(scrollTimer)
  }
  window.removeEventListener('resize', () => {
    trendChart?.resize()
  })
  trendChart?.dispose()
})
</script>

<style scoped lang="scss">
.alert-dashboard {
  padding: 20px;
  display: flex;
  flex-direction: column;
  min-height: 100vh;
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

  .header-right {
    display: flex;
    gap: 12px;
    align-items: center;
  }

  .current-time {
    font-size: 14px;
    color: #606266;
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

.middle-section {
  margin-top: 16px;
}

.realtime-alerts-card {
  height: 100%;

  .realtime-alerts-scroll {
    height: 400px;
    overflow-y: auto;
  }

  .realtime-alert-item {
    display: flex;
    gap: 12px;
    padding: 12px 0;
    border-bottom: 1px solid #f5f7fa;

    &:last-child {
      border-bottom: none;
    }

    .alert-tag {
      flex-shrink: 0;
    }

    .alert-info {
      flex: 1;
      min-width: 0;

      .alert-name {
        font-size: 14px;
        font-weight: 500;
        color: #303133;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }

      .alert-host {
        font-size: 12px;
        color: #909399;
        margin-top: 2px;
      }

      .alert-time {
        font-size: 12px;
        color: #c0c4cc;
        margin-top: 2px;
      }
    }
  }
}

.alert-list-card {
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
}

.trend-card {
  margin-top: 16px;

  .trend-content {
    height: 300px;
  }
  
  .trend-chart {
    width: 100%;
    height: 100%;
  }
}

.page-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: auto;
  padding-top: 20px;
  padding-bottom: 10px;
  color: #909399;
  font-size: 13px;

  .footer-left {
    display: flex;
    align-items: center;
  }

  .system-status {
    display: flex;
    align-items: center;
    gap: 4px;
    color: #67c23a;

    .status-icon {
      font-size: 14px;
    }
  }
}

.diag {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 13px;
  font-family: inherit;
}
</style>
