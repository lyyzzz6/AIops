<template>
  <div class="approval-center">
    <div class="page-header">
      <h2>审批中心</h2>
      <el-button @click="refreshAll"><el-icon><Refresh /></el-icon>刷新</el-button>
    </div>

    <!-- 统计卡片（合并两端） -->
    <el-row :gutter="12" class="stat-row">
      <el-col :span="6">
        <el-card shadow="never" class="stat-card warning">
          <div class="stat-label">权限待审批</div>
          <div class="stat-num">{{ permStats.pending ?? '-' }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">权限已审批</div>
          <div class="stat-num">{{ permStats.approved ?? '-' }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card warning">
          <div class="stat-label">命令待审批</div>
          <div class="stat-num">{{ execStats.pending ?? '-' }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" class="stat-card">
          <div class="stat-label">命令已执行</div>
          <div class="stat-num">{{ execStats.completed ?? '-' }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <!-- ========== 权限审批 ========== -->
        <el-tab-pane label="权限审批" name="permission">
          <el-radio-group v-model="permScope" size="small" @change="loadPermList">
            <el-radio-button label="pending">我待审批</el-radio-button>
            <el-radio-button label="my">我的申请</el-radio-button>
            <el-radio-button label="all">全部记录</el-radio-button>
          </el-radio-group>

          <el-table :data="permList" v-loading="permLoading" stripe style="margin-top: 12px">
            <el-table-column prop="requestNo" label="申请编号" width="140" />
            <el-table-column label="类型" width="130">
              <template #default="{ row }">{{ permTypeLabel(row.requestType) }}</template>
            </el-table-column>
            <el-table-column prop="reason" label="理由" min-width="220" show-overflow-tooltip />
            <el-table-column label="风险" width="90">
              <template #default="{ row }">
                <el-tag :type="riskTag(row.riskLevel)" size="small">{{ row.riskLevel }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="permStatusTag(row.status)" size="small">
                  {{ permStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="提交时间" width="170" />
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <template v-if="permScope === 'pending' && row.status === 'PENDING'">
                  <el-button type="primary" size="small" @click="permApprove(row)">通过</el-button>
                  <el-button type="danger" size="small" @click="permReject(row)">拒绝</el-button>
                </template>
                <el-button text type="primary" size="small" @click="permDetail(row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="pagination-wrapper">
            <el-pagination
              v-model:current-page="permPage.current"
              v-model:page-size="permPage.size"
              :total="permPage.total"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next"
              @size-change="loadPermList"
              @current-change="loadPermList"
            />
          </div>
        </el-tab-pane>

        <!-- ========== 命令审批 ========== -->
        <el-tab-pane label="命令审批" name="execution">
          <el-radio-group v-model="execFilter" size="small" @change="loadExecList">
            <el-radio-button label="">全部</el-radio-button>
            <el-radio-button label="pending">待审批</el-radio-button>
            <el-radio-button label="approved">已通过</el-radio-button>
            <el-radio-button label="rejected">已拒绝</el-radio-button>
            <el-radio-button label="completed">已完成</el-radio-button>
            <el-radio-button label="failed">失败</el-radio-button>
          </el-radio-group>

          <el-table :data="execList" v-loading="execLoading" stripe style="margin-top: 12px">
            <el-table-column prop="requestId" label="申请号" width="140" />
            <el-table-column label="命令" min-width="240">
              <template #default="{ row }">
                <code class="code-inline">{{ row.command }}</code>
              </template>
            </el-table-column>
            <el-table-column prop="targetHost" label="目标主机" width="140" />
            <el-table-column label="风险" width="100">
              <template #default="{ row }">
                <el-tag :type="riskTag(row.riskLevel)" size="small">{{ row.riskLevel }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="风险分" width="90" prop="riskScore" />
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="execStatusTag(row.status)" size="small">
                  {{ execStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="提交时间" width="170" />
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <template v-if="row.status === 'pending'">
                  <el-button type="primary" size="small" @click="execApprove(row)">通过</el-button>
                  <el-button type="danger" size="small" @click="execReject(row)">拒绝</el-button>
                </template>
                <el-button text type="primary" size="small" @click="execDetail(row)">详情</el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="pagination-wrapper">
            <el-pagination
              v-model:current-page="execPage.current"
              v-model:page-size="execPage.size"
              :total="execPage.total"
              :page-sizes="[10, 20, 50]"
              layout="total, sizes, prev, pager, next"
              @size-change="loadExecList"
              @current-change="loadExecList"
            />
          </div>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 权限详情 -->
    <el-dialog v-model="permDetailVisible" title="权限申请详情" width="640px">
      <el-descriptions v-if="currentPerm" :column="2" border>
        <el-descriptions-item label="申请编号">{{ currentPerm.requestNo }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ permTypeLabel(currentPerm.requestType) }}</el-descriptions-item>
        <el-descriptions-item label="申请人ID">{{ currentPerm.requesterId }}</el-descriptions-item>
        <el-descriptions-item label="目标用户ID">{{ currentPerm.targetUserId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="目标角色ID">{{ currentPerm.targetRoleId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="权限ID">{{ currentPerm.targetPermissionIds || '-' }}</el-descriptions-item>
        <el-descriptions-item label="有效时长(h)">{{ currentPerm.durationHours || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ permStatusLabel(currentPerm.status) }}</el-descriptions-item>
        <el-descriptions-item label="申请理由" :span="2">{{ currentPerm.reason }}</el-descriptions-item>
        <el-descriptions-item v-if="currentPerm.rejectReason" label="拒绝原因" :span="2">
          {{ currentPerm.rejectReason }}
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>

    <!-- 执行详情 -->
    <el-dialog v-model="execDetailVisible" title="命令执行详情" width="640px">
      <el-descriptions v-if="currentExec" :column="2" border>
        <el-descriptions-item label="申请号">{{ currentExec.requestId }}</el-descriptions-item>
        <el-descriptions-item label="命令类型">{{ currentExec.commandType || '-' }}</el-descriptions-item>
        <el-descriptions-item label="目标主机">{{ currentExec.targetHost || '-' }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ execStatusLabel(currentExec.status) }}</el-descriptions-item>
        <el-descriptions-item label="风险等级">{{ currentExec.riskLevel }}</el-descriptions-item>
        <el-descriptions-item label="风险分">{{ currentExec.riskScore }}</el-descriptions-item>
        <el-descriptions-item label="命令" :span="2">
          <pre class="code-block">{{ currentExec.command }}</pre>
        </el-descriptions-item>
        <el-descriptions-item v-if="currentExec.executionResult" label="执行结果" :span="2">
          <pre class="code-block">{{ currentExec.executionResult }}</pre>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { approvalApi, executionApi } from '@/api'
import type { PermissionRequestDTO, ExecutionAuditDTO } from '@/types'

const route = useRoute()
const activeTab = ref<'permission' | 'execution'>('permission')

// ===== 权限审批 =====
const permScope = ref<'pending' | 'my' | 'all'>('pending')
const permLoading = ref(false)
const permList = ref<PermissionRequestDTO[]>([])
const permPage = reactive({ current: 1, size: 10, total: 0 })
const permStats = ref<Record<string, any>>({})
const permDetailVisible = ref(false)
const currentPerm = ref<PermissionRequestDTO | null>(null)

// ===== 命令审批 =====
const execFilter = ref<'' | 'pending' | 'approved' | 'rejected' | 'completed' | 'failed'>('pending')
const execLoading = ref(false)
const execList = ref<ExecutionAuditDTO[]>([])
const execPage = reactive({ current: 1, size: 10, total: 0 })
const execStats = ref<Record<string, any>>({})
const execDetailVisible = ref(false)
const currentExec = ref<ExecutionAuditDTO | null>(null)

onMounted(() => {
  // 支持从 URL 带参：?tab=execution 直接定位到命令审批 Tab
  const tabQuery = (route.query.tab as string | undefined) || ''
  if (tabQuery === 'execution' || tabQuery === 'permission') {
    activeTab.value = tabQuery
  }
  refreshAll()
})

async function refreshAll() {
  await Promise.all([loadPermList(), loadExecList(), loadStats()])
}

async function loadStats() {
  try {
    const [p, e] = await Promise.all([approvalApi.getStats(), executionApi.getStats()])
    permStats.value = {
      pending: p?.pendingCount ?? 0,
      approved: p?.approvedCount ?? 0,
      rejected: p?.rejectedCount ?? 0,
      total: p?.totalCount ?? 0,
    }
    execStats.value = {
      pending: e?.pendingCount ?? 0,
      completed: e?.executedCount ?? 0,
      rejected: e?.rejectedCount ?? 0,
      failed: e?.failedCount ?? 0,
      total: e?.totalCount ?? 0,
    }
  } catch { /* ignore */ }
}

function onTabChange() {
  if (activeTab.value === 'permission') loadPermList()
  else loadExecList()
}

// ---------- 权限 ----------
async function loadPermList() {
  permLoading.value = true
  try {
    let res: any
    const params = { current: permPage.current, size: permPage.size }
    if (permScope.value === 'pending') res = await approvalApi.getPending(params)
    else if (permScope.value === 'my') res = await approvalApi.getMyRequests(params)
    else res = await approvalApi.getAll(params)
    permList.value = res?.records || []
    permPage.total = res?.total || 0
  } finally {
    permLoading.value = false
  }
}

async function permApprove(row: PermissionRequestDTO) {
  try {
    const { value } = await ElMessageBox.prompt('审批意见（可选）', '审批通过', {
      inputPlaceholder: '备注说明',
      inputValue: '同意',
    })
    await approvalApi.approve(row.id, value || '')
    ElMessage.success('已通过')
    loadPermList()
    loadStats()
  } catch { /* cancelled */ }
}

async function permReject(row: PermissionRequestDTO) {
  try {
    const { value } = await ElMessageBox.prompt('请输入拒绝原因', '审批拒绝', {
      inputPattern: /\S+/,
      inputErrorMessage: '请输入拒绝原因',
    })
    await approvalApi.reject(row.id, value)
    ElMessage.success('已拒绝')
    loadPermList()
    loadStats()
  } catch { /* cancelled */ }
}

async function permDetail(row: PermissionRequestDTO) {
  currentPerm.value = row
  permDetailVisible.value = true
}

function permTypeLabel(t: string) {
  return ({ ROLE_ASSIGN: '角色分配', PERMISSION_GRANT: '权限授权', TEMP_ELEVATION: '临时提权' } as any)[t] || t
}

function permStatusLabel(s: string) {
  return ({ PENDING: '待审批', REVIEWING: '审批中', APPROVED: '已通过', REJECTED: '已拒绝', EXPIRED: '已过期' } as any)[s] || s
}

function permStatusTag(s: string): 'success' | 'warning' | 'danger' | 'info' {
  if (s === 'APPROVED') return 'success'
  if (s === 'REJECTED') return 'danger'
  if (s === 'PENDING' || s === 'REVIEWING') return 'warning'
  return 'info'
}

// ---------- 执行 ----------
async function loadExecList() {
  execLoading.value = true
  try {
    const res = await executionApi.list({
      current: execPage.current,
      size: execPage.size,
      status: execFilter.value || undefined,
    })
    execList.value = res?.records || []
    execPage.total = res?.total || 0
  } finally {
    execLoading.value = false
  }
}

async function execApprove(row: ExecutionAuditDTO) {
  try {
    await ElMessageBox.confirm(`确认通过命令：\n${row.command}`, '审批确认', { type: 'warning' })
    await executionApi.approve(row.id)
    ElMessage.success('审批通过')
    loadExecList()
    loadStats()
  } catch { /* cancelled */ }
}

async function execReject(row: ExecutionAuditDTO) {
  try {
    const { value } = await ElMessageBox.prompt('请输入拒绝原因', '拒绝', {
      inputPattern: /\S+/,
      inputErrorMessage: '请输入拒绝原因',
    })
    await executionApi.reject(row.id, value)
    ElMessage.success('已拒绝')
    loadExecList()
    loadStats()
  } catch { /* cancelled */ }
}

function execDetail(row: ExecutionAuditDTO) {
  currentExec.value = row
  execDetailVisible.value = true
}

function execStatusLabel(s: string) {
  return ({
    pending: '待审批',
    approved: '已通过',
    rejected: '已拒绝',
    executing: '执行中',
    completed: '已完成',
    failed: '失败',
  } as any)[s] || s
}

function execStatusTag(s: string): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (s === 'completed' || s === 'approved') return 'success'
  if (s === 'rejected' || s === 'failed') return 'danger'
  if (s === 'pending') return 'warning'
  if (s === 'executing') return 'primary'
  return 'info'
}

function riskTag(level: string): 'success' | 'warning' | 'danger' | 'info' {
  if (level === 'high' || level === 'critical') return 'danger'
  if (level === 'medium') return 'warning'
  if (level === 'low') return 'success'
  return 'info'
}
</script>

<style scoped lang="scss">
.approval-center { padding: 20px; }
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
  &.warning .stat-num { color: #e6a23c; }
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
