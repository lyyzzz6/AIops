<template>
  <div class="alert-dashboard">
    <el-row :gutter="20">
      <!-- 统计卡片 -->
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-value">{{ stats.total }}</div>
          <div class="stat-label">总告警数</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card firing">
          <div class="stat-value">{{ stats.firing }}</div>
          <div class="stat-label">正在告警</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card resolved">
          <div class="stat-value">{{ stats.resolved }}</div>
          <div class="stat-label">已恢复</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card critical">
          <div class="stat-value">{{ stats.critical }}</div>
          <div class="stat-label">严重告警</div>
        </el-card>
      </el-col>
    </el-row>
    
    <!-- 告警列表 -->
    <el-card class="alert-list-card">
      <template #header>
        <div class="card-header">
          <span>告警列表</span>
          <div class="filter-actions">
            <el-select v-model="filterStatus" placeholder="状态筛选" size="small" style="width: 120px">
              <el-option label="全部" value="" />
              <el-option label="告警中" value="firing" />
              <el-option label="已恢复" value="resolved" />
            </el-select>
            <el-select v-model="filterSeverity" placeholder="级别筛选" size="small" style="width: 120px">
              <el-option label="全部" value="" />
              <el-option label="严重" value="critical" />
              <el-option label="警告" value="warning" />
              <el-option label="信息" value="info" />
            </el-select>
          </div>
        </div>
      </template>
      
      <el-table :data="filteredAlerts" stripe>
        <el-table-column prop="alertName" label="告警名称" min-width="200" />
        <el-table-column prop="host" label="主机" width="150" />
        <el-table-column prop="metricName" label="指标" width="150" />
        <el-table-column label="级别" width="100">
          <template #default="{ row }">
            <el-tag :type="getSeverityType(row.severity)" size="small">
              {{ row.severity }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'firing' ? 'danger' : 'success'" size="small">
              {{ row.status === 'firing' ? '告警中' : '已恢复' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" @click="diagnoseAlert(row)">
              诊断
            </el-button>
            <el-button text type="primary" @click="viewDetail(row)">
              详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import type { Alert } from '@/types'

const router = useRouter()

// 统计数据
const stats = ref({
  total: 128,
  firing: 23,
  resolved: 105,
  critical: 8,
})

// 筛选条件
const filterStatus = ref('')
const filterSeverity = ref('')

// 模拟告警数据
const alerts = ref<Alert[]>([
  {
    id: '1',
    alertId: 'alert-001',
    source: 'netdata',
    severity: 'critical',
    alertName: 'CPU使用率过高',
    message: 'CPU使用率超过 90%',
    host: 'server-01',
    metricName: 'system.cpu',
    metricValue: '95%',
    threshold: '80%',
    status: 'firing',
    createdAt: new Date(),
  },
  {
    id: '2',
    alertId: 'alert-002',
    source: 'netdata',
    severity: 'warning',
    alertName: '内存使用率过高',
    message: '内存使用率超过 85%',
    host: 'server-02',
    metricName: 'system.ram',
    metricValue: '87%',
    threshold: '80%',
    status: 'firing',
    createdAt: new Date(Date.now() - 3600000),
  },
])

// 过滤后的告警列表
const filteredAlerts = computed(() => {
  return alerts.value.filter(alert => {
    if (filterStatus.value && alert.status !== filterStatus.value) return false
    if (filterSeverity.value && alert.severity !== filterSeverity.value) return false
    return true
  })
})

// 获取严重程度类型
function getSeverityType(severity: string): 'danger' | 'warning' | 'info' {
  switch (severity) {
    case 'critical': return 'danger'
    case 'warning': return 'warning'
    default: return 'info'
  }
}

// 格式化时间
function formatTime(time: Date): string {
  return dayjs(time).format('YYYY-MM-DD HH:mm:ss')
}

// 诊断告警
function diagnoseAlert(alert: Alert) {
  router.push({
    path: '/chat',
    query: { q: `诊断告警：${alert.alertName}，主机：${alert.host}` },
  })
}

// 查看详情
function viewDetail(alert: Alert) {
  console.log('查看详情:', alert)
}

onMounted(() => {
  // 加载告警数据
})
</script>

<style scoped lang="scss">
.alert-dashboard {
  padding: 20px;
}

.stat-card {
  text-align: center;
  
  :deep(.el-card__body) {
    padding: 20px;
  }
  
  .stat-value {
    font-size: 32px;
    font-weight: 600;
    color: #303133;
  }
  
  .stat-label {
    font-size: 14px;
    color: #909399;
    margin-top: 8px;
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
  margin-top: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.filter-actions {
  display: flex;
  gap: 12px;
}
</style>
