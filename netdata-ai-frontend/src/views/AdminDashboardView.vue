<template>
  <div class="admin-dashboard">
    <div class="dashboard-header">
      <h2>🛡️ 超管控制台</h2>
      <p class="subtitle">仅 SUPER_ADMIN 角色可见的管理界面</p>
    </div>

    <el-row :gutter="20" class="stats-row">
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-card">
            <el-icon size="32" color="#409EFF"><User /></el-icon>
            <div class="stat-info">
              <div class="stat-value">{{ stats.users }}</div>
              <div class="stat-label">用户总数</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-card">
            <el-icon size="32" color="#67C23A"><Key /></el-icon>
            <div class="stat-info">
              <div class="stat-value">{{ stats.roles }}</div>
              <div class="stat-label">角色总数</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-card">
            <el-icon size="32" color="#E6A23C"><Document /></el-icon>
            <div class="stat-info">
              <div class="stat-value">{{ stats.permissions }}</div>
              <div class="stat-label">权限总数</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-card">
            <el-icon size="32" color="#F56C6C"><Warning /></el-icon>
            <div class="stat-info">
              <div class="stat-value">{{ stats.pendingApprovals }}</div>
              <div class="stat-label">待审批</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" class="quick-actions">
      <el-col :span="24">
        <el-card>
          <template #header>
            <span>⚡ 快捷操作</span>
          </template>
          <div class="action-grid">
            <el-button type="primary" @click="router.push('/users')">
              <el-icon><User /></el-icon>
              用户管理
            </el-button>
            <el-button type="success" @click="router.push('/roles')">
              <el-icon><Key /></el-icon>
              角色权限
            </el-button>
            <el-button type="warning" @click="router.push('/operation-logs')">
              <el-icon><Document /></el-icon>
              操作审计
            </el-button>
            <el-button type="danger" @click="router.push('/approvals')">
              <el-icon><CircleCheck /></el-icon>
              审批管理
            </el-button>
            <el-button @click="router.push('/executions')">
              <el-icon><Operation /></el-icon>
              执行审计
            </el-button>
            <el-button @click="loadStats">
              <el-icon><Refresh /></el-icon>
              刷新数据
            </el-button>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" class="system-info">
      <el-col :span="12">
        <el-card>
          <template #header>
            <span>👤 当前管理员信息</span>
          </template>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="用户名">
              {{ authStore.user?.username }}
            </el-descriptions-item>
            <el-descriptions-item label="昵称">
              {{ authStore.user?.nickname }}
            </el-descriptions-item>
            <el-descriptions-item label="邮箱">
              {{ authStore.user?.email }}
            </el-descriptions-item>
            <el-descriptions-item label="角色">
              <el-tag type="danger" size="small">{{ authStore.roles.join(', ') }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="权限数量">
              {{ authStore.permissions.length }} 个
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header>
            <span>📋 系统权限列表</span>
          </template>
          <div class="permission-tags">
            <el-tag
              v-for="perm in authStore.permissions"
              :key="perm"
              size="small"
              class="perm-tag"
            >
              {{ perm }}
            </el-tag>
            <span v-if="authStore.permissions.length === 0" class="no-perm">
              暂无直接分配的权限（通过角色继承）
            </span>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import apiClient from '@/api'
import { useAuthStore } from '@/stores/auth'
import { User, Key, Document, Warning, CircleCheck, Operation, Refresh } from '@element-plus/icons-vue'

const router = useRouter()
const authStore = useAuthStore()

const stats = ref({
  users: 0,
  roles: 0,
  permissions: 0,
  pendingApprovals: 0,
})

async function loadStats() {
  try {
    const [usersRes, rolesRes, permsRes] = await Promise.all([
      apiClient.get('/users', { params: { current: 1, size: 1 } }),
      apiClient.get('/roles'),
      apiClient.get('/roles/permissions/all'),
    ])

    stats.value.users = usersRes.data?.total || 0
    stats.value.roles = rolesRes.data?.length || 0
    stats.value.permissions = permsRes.data?.length || 0
  } catch (e) {
    console.error('加载统计失败', e)
  }

  try {
    const approvalsRes = await apiClient.get('/approvals/pending', { params: { current: 1, size: 1 } })
    stats.value.pendingApprovals = approvalsRes.data?.total || 0
  } catch (e) {
    console.error('加载待审批数失败', e)
  }
}

onMounted(() => {
  if (!authStore.isSuperAdmin) {
    ElMessage.error('您没有权限访问此页面')
    router.push('/chat')
    return
  }
  loadStats()
})
</script>

<style scoped lang="scss">
.admin-dashboard {
  padding: 24px;
  max-width: 1400px;
  margin: 0 auto;

  .dashboard-header {
    margin-bottom: 24px;

    h2 {
      margin: 0 0 8px 0;
      font-size: 24px;
      font-weight: 600;
      color: #303133;
    }

    .subtitle {
      margin: 0;
      color: #909399;
      font-size: 14px;
    }
  }

  .stats-row {
    margin-bottom: 20px;

    .stat-card {
      display: flex;
      align-items: center;
      gap: 16px;
      padding: 8px 0;

      .stat-info {
        .stat-value {
          font-size: 28px;
          font-weight: 600;
          color: #303133;
          line-height: 1.2;
        }

        .stat-label {
          font-size: 14px;
          color: #909399;
          margin-top: 4px;
        }
      }
    }
  }

  .quick-actions {
    margin-bottom: 20px;

    .action-grid {
      display: flex;
      flex-wrap: wrap;
      gap: 12px;

      .el-button {
        min-width: 120px;
      }
    }
  }

  .system-info {
    .permission-tags {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;

      .perm-tag {
        font-family: monospace;
      }

      .no-perm {
        color: #909399;
        font-size: 14px;
      }
    }
  }
}
</style>
