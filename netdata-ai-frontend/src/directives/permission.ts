import type { App, Directive, DirectiveBinding } from 'vue'
import { useAuthStore } from '@/stores/auth'

/**
 * v-permission 指令
 * 用法: v-permission="'user:write'" 或 v-permission="['user:write', 'user:delete']"
 * 当用户没有对应权限时，元素会被移除
 */
const permissionDirective: Directive = {
  mounted(el: HTMLElement, binding: DirectiveBinding) {
    checkPermission(el, binding)
  },
  updated(el: HTMLElement, binding: DirectiveBinding) {
    checkPermission(el, binding)
  },
}

function checkPermission(el: HTMLElement, binding: DirectiveBinding) {
  const authStore = useAuthStore()
  const { value } = binding

  if (!value) return

  const permissions = Array.isArray(value) ? value : [value]
  const hasPermission = permissions.some((perm: string) => authStore.hasPermission(perm))

  if (!hasPermission) {
    el.parentNode?.removeChild(el)
  }
}

/**
 * v-role 指令
 * 用法: v-role="'ADMIN'" 或 v-role="['ADMIN', 'SUPER_ADMIN']"
 */
const roleDirective: Directive = {
  mounted(el: HTMLElement, binding: DirectiveBinding) {
    checkRole(el, binding)
  },
  updated(el: HTMLElement, binding: DirectiveBinding) {
    checkRole(el, binding)
  },
}

function checkRole(el: HTMLElement, binding: DirectiveBinding) {
  const authStore = useAuthStore()
  const { value } = binding

  if (!value) return

  const requiredRoles = Array.isArray(value) ? value : [value]
  const hasRole = requiredRoles.some((role: string) => authStore.hasRole(role))

  if (!hasRole) {
    el.parentNode?.removeChild(el)
  }
}

export function setupPermissionDirectives(app: App) {
  app.directive('permission', permissionDirective)
  app.directive('role', roleDirective)
}
