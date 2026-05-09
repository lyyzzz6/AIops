<template>
  <div class="execution-approval">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>执行审批</span>
          <el-radio-group v-model="filterStatus" size="small">
            <el-radio-button label="">全部</el-radio-button>
            <el-radio-button label="pending">待审批</el-radio-button>
            <el-radio-button label="approved">已通过</el-radio-button>
            <el-radio-button label="rejected">已拒绝</el-radio-button>
          </el-radio-group>
        </div>
      </template>
      
      <el-table :data="filteredApprovals" stripe>
        <el-table-column prop="requestId" label="审批ID" width="120" />
        <el-table-column prop="command" label="命令" min-width="250">
          <template #default="{ row }">
            <code class="command-code">{{ row.command }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" width="200" />
        <el-table-column label="风险等级" width="100">
          <template #default="{ row }">
            <el-tag :type="getRiskType(row.riskLevel)" size="small">
              {{ row.riskLevel }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="riskScore" label="风险分数" width="100">
          <template #default="{ row }">
            <el-progress
              :percentage="row.riskScore"
              :color="getScoreColor(row.riskScore)"
              :stroke-width="8"
            />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)">
              {{ getStatusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="requester" label="申请人" width="100" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'pending'">
              <el-button type="primary" size="small" @click="approveRequest(row)">
                通过
              </el-button>
              <el-button type="danger" size="small" @click="rejectRequest(row)">
                拒绝
              </el-button>
            </template>
            <template v-else>
              <el-button text type="primary" size="small">详情</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ApprovalRequest } from '@/types'

const filterStatus = ref('')

// 模拟审批数据
const approvals = ref<ApprovalRequest[]>([
  {
    id: '1',
    requestId: 'REQ-001',
    command: 'systemctl restart nginx',
    description: '重启 Nginx 服务',
    riskLevel: 'MEDIUM',
    riskScore: 45,
    status: 'pending',
    requester: 'admin',
    createdAt: new Date(),
  },
  {
    id: '2',
    requestId: 'REQ-002',
    command: 'docker restart container_name',
    description: '重启 Docker 容器',
    riskLevel: 'HIGH',
    riskScore: 72,
    status: 'pending',
    requester: 'operator',
    createdAt: new Date(Date.now() - 1800000),
  },
])

// 过滤后的审批列表
const filteredApprovals = computed(() => {
  if (!filterStatus.value) return approvals.value
  return approvals.value.filter(a => a.status === filterStatus.value)
})

// 获取风险类型
function getRiskType(level: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (level) {
    case 'LOW': return 'success'
    case 'MEDIUM': return 'warning'
    case 'HIGH': return 'danger'
    default: return 'info'
  }
}

// 获取分数颜色
function getScoreColor(score: number): string {
  if (score >= 80) return '#f56c6c'
  if (score >= 60) return '#e6a23c'
  if (score >= 30) return '#409eff'
  return '#67c23a'
}

// 获取状态类型
function getStatusType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  switch (status) {
    case 'approved':
    case 'executed': return 'success'
    case 'rejected': return 'danger'
    case 'pending': return 'warning'
    default: return 'info'
  }
}

// 获取状态文本
function getStatusText(status: string): string {
  switch (status) {
    case 'approved': return '已通过'
    case 'rejected': return '已拒绝'
    case 'pending': return '待审批'
    case 'executed': return '已执行'
    case 'failed': return '执行失败'
    default: return status
  }
}

// 审批通过
async function approveRequest(request: ApprovalRequest) {
  try {
    await ElMessageBox.confirm(
      `确定要通过命令「${request.command}」的执行申请吗？`,
      '审批确认',
      { type: 'warning' }
    )
    
    request.status = 'approved'
    ElMessage.success('审批通过，命令将执行')
  } catch {
    // 取消
  }
}

// 审批拒绝
async function rejectRequest(request: ApprovalRequest) {
  try {
    const { value } = await ElMessageBox.prompt('请输入拒绝原因', '拒绝审批', {
      inputPattern: /\S+/,
      inputErrorMessage: '请输入拒绝原因',
    })
    
    request.status = 'rejected'
    console.log('拒绝原因:', value)
    ElMessage.success('已拒绝该执行申请')
  } catch {
    // 取消
  }
}
</script>

<style scoped lang="scss">
.execution-approval {
  padding: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.command-code {
  font-family: 'Fira Code', monospace;
  font-size: 13px;
  background: #f5f7fa;
  padding: 4px 8px;
  border-radius: 4px;
}
</style>
