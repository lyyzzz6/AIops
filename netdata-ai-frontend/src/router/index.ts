import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { title: '登录', public: true },
    },
    {
      path: '/',
      redirect: '/chat',
    },
    {
      path: '/chat',
      name: 'chat',
      component: () => import('@/views/ChatView.vue'),
      meta: { title: '智能问答' },
    },
    {
      path: '/alerts',
      name: 'alerts',
      component: () => import('@/views/AlertDashboardView.vue'),
      meta: { title: '告警仪表板' },
    },
    {
      path: '/knowledge',
      name: 'knowledge',
      component: () => import('@/views/KnowledgeBaseView.vue'),
      meta: { title: '知识库管理' },
    },
    {
      path: '/executions',
      name: 'executions',
      component: () => import('@/views/ExecutionRequestView.vue'),
      meta: { title: '命令执行' },
    },
    {
      path: '/approvals',
      name: 'approvals',
      component: () => import('@/views/ApprovalCenterView.vue'),
      meta: { title: '审批中心' },
    },
    {
      path: '/operation-logs',
      name: 'operation-logs',
      component: () => import('@/views/OperationLogView.vue'),
      meta: { title: '操作审计' },
    },
    {
      path: '/roles',
      name: 'roles',
      component: () => import('@/views/RoleManagementView.vue'),
      meta: { title: '角色权限', permission: 'system:config' },
    },
    {
      path: '/users',
      name: 'users',
      component: () => import('@/views/UserManagementView.vue'),
      meta: { title: '用户管理', permission: 'user:read' },
    },
    {
      path: '/403',
      name: 'forbidden',
      component: () => import('@/views/ChatView.vue'),
      meta: { title: '权限不足', public: true },
    },
  ],
})

// 路由守卫：认证检查 + 权限检查 + 页面标题
router.beforeEach(async (to, _from, next) => {
  document.title = `${to.meta.title || '智能运维'} - NetData Ops`

  // 公开页面无需认证
  if (to.meta.public) {
    next()
    return
  }

  // 检查认证状态
  const token = localStorage.getItem('access_token')
  if (!token) {
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }

  // 权限检查：若路由声明了 permission，则校验当前用户权限
  const required = to.meta.permission as string | undefined
  if (required) {
    const authStore = useAuthStore()
    // 若尚未加载用户信息，先拉取
    if (!authStore.user) {
      try {
        await authStore.fetchUserInfo()
      } catch {
        /* ignore */
      }
    }
    if (!authStore.hasPermission(required)) {
      next({ path: '/chat' })
      return
    }
  }

  next()
})

export default router
