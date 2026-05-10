<template>
  <div class="role-management">
    <div class="page-header">
      <h2>角色权限</h2>
      <el-button type="primary" @click="showCreateDialog">
        <el-icon><Plus /></el-icon>
        新建角色
      </el-button>
    </div>

    <el-row :gutter="16">
      <!-- 左：角色列表 -->
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>
            <span>角色列表</span>
          </template>
          <el-table
            :data="roles"
            v-loading="loading"
            highlight-current-row
            @current-change="handleRoleSelect"
          >
            <el-table-column prop="roleCode" label="角色编码" width="140" />
            <el-table-column prop="roleName" label="名称" />
            <el-table-column label="状态" width="80">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
                  {{ row.status === 1 ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140">
              <template #default="{ row }">
                <el-button size="small" @click.stop="showEditDialog(row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <!-- 右：权限配置 -->
      <el-col :span="14">
        <el-card shadow="never">
          <template #header>
            <div class="perm-header">
              <span>权限分配 <em v-if="selectedRole">- {{ selectedRole.roleName }}</em></span>
              <el-button
                v-if="selectedRole"
                type="primary"
                size="small"
                :loading="savingPerms"
                @click="handleSavePermissions"
              >
                保存权限
              </el-button>
            </div>
          </template>

          <div v-if="!selectedRole" class="empty-tip">
            <el-empty description="请先在左侧选择角色" :image-size="80" />
          </div>

          <el-tree
            v-else
            ref="permTreeRef"
            :data="permissionTree"
            show-checkbox
            node-key="id"
            :default-checked-keys="checkedPermissionIds"
            :props="{ label: 'label', children: 'children' }"
          >
            <template #default="{ data }">
              <span class="tree-node">
                <span>{{ data.label }}</span>
                <el-tag
                  v-if="data.riskLevel"
                  :type="riskTagType(data.riskLevel)"
                  size="small"
                  effect="plain"
                >
                  {{ data.riskLevel }}
                </el-tag>
              </span>
            </template>
          </el-tree>
        </el-card>
      </el-col>
    </el-row>

    <!-- 角色创建/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑角色' : '新建角色'" width="480px">
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="90px">
        <el-form-item label="角色编码" prop="roleCode">
          <el-input v-model="form.roleCode" :disabled="isEdit" placeholder="如 OPERATOR" />
        </el-form-item>
        <el-form-item label="角色名称" prop="roleName">
          <el-input v-model="form.roleName" placeholder="展示名称" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="状态" v-if="isEdit">
          <el-switch v-model="form.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules, ElTree } from 'element-plus'
import { roleApi } from '@/api'
import type { SysRoleDTO, SysPermissionDTO } from '@/types'

const loading = ref(false)
const submitting = ref(false)
const savingPerms = ref(false)
const roles = ref<SysRoleDTO[]>([])
const allPermissions = ref<SysPermissionDTO[]>([])
const selectedRole = ref<SysRoleDTO | null>(null)
const checkedPermissionIds = ref<number[]>([])
const permTreeRef = ref<InstanceType<typeof ElTree>>()

const dialogVisible = ref(false)
const isEdit = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({
  id: 0,
  roleCode: '',
  roleName: '',
  description: '',
  status: 1,
})
const formRules: FormRules = {
  roleCode: [{ required: true, message: '请输入角色编码', trigger: 'blur' }],
  roleName: [{ required: true, message: '请输入角色名称', trigger: 'blur' }],
}

// 按 module 分组构造树
const permissionTree = computed(() => {
  const moduleMap = new Map<string, any>()
  for (const p of allPermissions.value) {
    const moduleKey = p.module || 'other'
    if (!moduleMap.has(moduleKey)) {
      moduleMap.set(moduleKey, {
        id: `mod-${moduleKey}`,
        label: moduleLabel(moduleKey),
        children: [],
      })
    }
    moduleMap.get(moduleKey).children.push({
      id: p.id,
      label: `${p.permissionName} (${p.permissionCode})`,
      riskLevel: p.riskLevel,
    })
  }
  return Array.from(moduleMap.values())
})

function moduleLabel(m: string) {
  const map: Record<string, string> = {
    system: '系统管理',
    user: '用户管理',
    role: '角色管理',
    knowledge: '知识库',
    alert: '告警',
    execution: '命令执行',
    approval: '审批',
    audit: '审计',
  }
  return map[m] || m
}

function riskTagType(level: string): 'success' | 'warning' | 'danger' | 'info' {
  if (level === 'high') return 'danger'
  if (level === 'medium') return 'warning'
  if (level === 'low') return 'success'
  return 'info'
}

onMounted(async () => {
  await loadAll()
})

async function loadAll() {
  loading.value = true
  try {
    const [r, p] = await Promise.all([
      roleApi.getRoles(),
      roleApi.getAllPermissions(),
    ])
    roles.value = (r as SysRoleDTO[]) || []
    allPermissions.value = (p as SysPermissionDTO[]) || []
  } finally {
    loading.value = false
  }
}

async function handleRoleSelect(row: SysRoleDTO | null) {
  if (!row) return
  selectedRole.value = row
  try {
    const list = await roleApi.getRolePermissions(row.id)
    checkedPermissionIds.value = (list as SysPermissionDTO[]).map((p) => p.id)
    // 等树渲染后同步选中
    setTimeout(() => {
      permTreeRef.value?.setCheckedKeys(checkedPermissionIds.value)
    }, 0)
  } catch { /* ignore */ }
}

async function handleSavePermissions() {
  if (!selectedRole.value || !permTreeRef.value) return
  const checked = permTreeRef.value.getCheckedKeys(true) as (number | string)[]
  const ids = checked.filter((k) => typeof k === 'number') as number[]
  savingPerms.value = true
  try {
    await roleApi.assignPermissions(selectedRole.value.id, ids)
    ElMessage.success('权限已保存')
  } finally {
    savingPerms.value = false
  }
}

function showCreateDialog() {
  isEdit.value = false
  Object.assign(form, { id: 0, roleCode: '', roleName: '', description: '', status: 1 })
  dialogVisible.value = true
}

function showEditDialog(row: SysRoleDTO) {
  isEdit.value = true
  Object.assign(form, {
    id: row.id,
    roleCode: row.roleCode,
    roleName: row.roleName,
    description: row.description || '',
    status: row.status,
  })
  dialogVisible.value = true
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    if (isEdit.value) {
      await roleApi.updateRole(form.id, {
        roleName: form.roleName,
        description: form.description,
        status: form.status,
      })
      ElMessage.success('已更新')
    } else {
      await roleApi.createRole({
        roleCode: form.roleCode,
        roleName: form.roleName,
        description: form.description,
      })
      ElMessage.success('已创建')
    }
    dialogVisible.value = false
    loadAll()
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped lang="scss">
.role-management {
  padding: 20px;
}
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
  h2 { margin: 0; font-size: 20px; }
}
.perm-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  em { font-style: normal; color: #409eff; }
}
.empty-tip {
  padding: 32px 0;
}
.tree-node {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
