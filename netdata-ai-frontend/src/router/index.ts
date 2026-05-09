import { createRouter, createWebHistory } from 'vue-router'

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
      meta: { title: '知识库管理', permission: 'knowledge:read' },
    },
    {
      path: '/approval',
      name: 'approval',
      component: () => import('@/views/ExecutionApprovalView.vue'),
      meta: { title: '执行审批', permission: 'approval:approve' },
    },
    {
      path: '/users',
      name: 'users',
      component: () => import('@/views/UserManagementView.vue'),
      meta: { title: '用户管理', permission: 'user:read' },
    },
  ],
})

// 路由守卫：认证检查 + 页面标题
router.beforeEach((to, _from, next) => {
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

  next()
})

export default router
