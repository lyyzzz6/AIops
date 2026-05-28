<template>
  <el-config-provider :locale="zhCn">
    <!-- 登录页等公开路由：无布局 -->
    <router-view v-if="isPublicRoute" />

    <!-- 已登录业务页：顶部导航 + 主体内容 -->
    <el-container v-else class="app-shell">
      <el-header class="app-header" height="56px">
        <div class="brand" @click="router.push('/chat')">
          <el-icon :size="20"><Monitor /></el-icon>
          <span class="brand-text">NetData 智能运维</span>
        </div>

        <el-menu
          class="app-menu"
          :default-active="activeMenu"
          mode="horizontal"
          :ellipsis="false"
          router
        >
          <template v-for="item in visibleMenus" :key="item.path">
            <el-menu-item :index="item.path">
              <el-icon><component :is="item.icon" /></el-icon>
              <span>{{ item.title }}</span>
            </el-menu-item>
          </template>
        </el-menu>

        <div class="user-area">
          <el-dropdown trigger="click" @command="handleUserCommand">
            <div class="user-info">
              <el-avatar :size="28">{{ userInitial }}</el-avatar>
              <span class="user-name">{{ authStore.user?.nickname || authStore.username || '未登录' }}</span>
              <el-icon><ArrowDown /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile" disabled>
                  <el-icon><UserFilled /></el-icon>
                  角色：{{ authStore.roles.join(', ') || '-' }}
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed, onMounted, markRaw } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElConfigProvider, ElMessageBox } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import {
  Monitor, ChatDotRound, Bell, Reading, Operation, Document,
  UserFilled, Key, ArrowDown, SwitchButton, Management, Setting,
} from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()

interface MenuItem {
  path: string
  title: string
  icon: any
  permission?: string
  requireSuperAdmin?: boolean
}

const menuItems: MenuItem[] = [
  { path: '/chat', title: '智能问答', icon: markRaw(ChatDotRound) },
  { path: '/alerts', title: '告警仪表板', icon: markRaw(Bell) },
  { path: '/knowledge', title: '知识库', icon: markRaw(Reading) },
  { path: '/executions', title: '命令执行', icon: markRaw(Operation) },
  { path: '/approvals', title: '审批中心', icon: markRaw(Management) },
  { path: '/admin', title: '超管控制台', icon: markRaw(Setting), requireSuperAdmin: true },
  { path: '/operation-logs', title: '操作审计', icon: markRaw(Document) },
  { path: '/roles', title: '角色权限', icon: markRaw(Key), permission: 'system:config' },
  { path: '/users', title: '用户管理', icon: markRaw(UserFilled), permission: 'user:read' },
]

const visibleMenus = computed(() =>
  menuItems.filter((m) => {
    if (m.requireSuperAdmin && !authStore.isSuperAdmin) return false
    if (m.permission && !authStore.hasPermission(m.permission)) return false
    return true
  })
)

const isPublicRoute = computed(() => !!route.meta?.public)

const activeMenu = computed(() => {
  // 高亮当前顶级路由
  const top = '/' + route.path.split('/').filter(Boolean)[0]
  return menuItems.find((m) => m.path === top)?.path || route.path
})

const userInitial = computed(() => {
  const name = authStore.user?.nickname || authStore.username || 'U'
  return name.charAt(0).toUpperCase()
})

onMounted(async () => {
  if (authStore.isAuthenticated && !authStore.user) {
    try { await authStore.fetchUserInfo() } catch { /* ignore */ }
  }
})

async function handleUserCommand(cmd: string) {
  if (cmd === 'logout') {
    try {
      await ElMessageBox.confirm('确定退出登录吗？', '提示', { type: 'warning' })
      await authStore.logout()
    } catch { /* cancelled */ }
  }
}
</script>

<style lang="scss">
html, body, #app {
  height: 100%;
  margin: 0;
  padding: 0;
}

.app-shell {
  height: 100vh;
  display: flex;
  flex-direction: column;
}

.app-header {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 0 24px;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  border-bottom: 1px solid #5a4a9a;
  box-shadow: 0 2px 8px rgba(102, 126, 234, 0.3);

  .brand {
    display: flex;
    align-items: center;
    gap: 8px;
    font-weight: 600;
    font-size: 16px;
    color: #303133;
    cursor: pointer;
    flex-shrink: 0;

    .brand-text {
      white-space: nowrap;
    }
  }

  .app-menu {
    flex: 1;
    border-bottom: none !important;
  }

  .user-area {
    flex-shrink: 0;

    .user-info {
      display: flex;
      align-items: center;
      gap: 8px;
      cursor: pointer;
      padding: 4px 8px;
      border-radius: 4px;

      &:hover {
        background: #f5f7fa;
      }

      .user-name {
        font-size: 14px;
        color: #606266;
      }
    }
  }
}

.app-main {
  flex: 1;
  padding: 0;
  overflow: auto;
  background: #f5f7fa;
}
</style>
