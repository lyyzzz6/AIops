<template>
  <div class="user-management">
    <div class="page-header">
      <h2>用户管理</h2>
      <el-button type="primary" @click="showCreateDialog">
        <el-icon><Plus /></el-icon>
        新建用户
      </el-button>
    </div>

    <!-- 搜索栏 -->
    <el-card class="search-card" shadow="never">
      <el-input
        v-model="searchKeyword"
        placeholder="搜索用户名/昵称"
        prefix-icon="Search"
        clearable
        style="width: 300px"
        @input="handleSearch"
      />
    </el-card>

    <!-- 用户列表 -->
    <el-card shadow="never">
      <el-table :data="users" v-loading="loading" stripe>
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="username" label="用户名" width="120" />
        <el-table-column prop="nickname" label="昵称" width="120" />
        <el-table-column prop="email" label="邮箱" width="200" />
        <el-table-column label="角色" width="180">
          <template #default="{ row }">
            <el-tag
              v-for="role in row.roles"
              :key="role"
              :type="getRoleTagType(role)"
              size="small"
              class="role-tag"
            >
              {{ role }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
              {{ row.status === 1 ? '正常' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginAt" label="最后登录" width="170" />
        <el-table-column label="操作" fixed="right" width="240">
          <template #default="{ row }">
            <el-button size="small" @click="showEditDialog(row)">编辑</el-button>
            <el-button size="small" type="warning" @click="showRoleDialog(row)">角色</el-button>
            <el-button size="small" type="info" @click="handleResetPassword(row)">重置密码</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="pagination.current"
          v-model:page-size="pagination.size"
          :total="pagination.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @size-change="loadUsers"
          @current-change="loadUsers"
        />
      </div>
    </el-card>

    <!-- 创建/编辑用户对话框 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑用户' : '新建用户'" width="480px">
      <el-form ref="userFormRef" :model="userForm" :rules="userFormRules" label-width="80px">
        <el-form-item label="用户名" prop="username" v-if="!isEdit">
          <el-input v-model="userForm.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码" prop="password" v-if="!isEdit">
          <el-input v-model="userForm.password" type="password" placeholder="请输入密码" show-password />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="userForm.nickname" placeholder="请输入昵称" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="userForm.email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-form-item label="状态" v-if="isEdit">
          <el-switch v-model="userForm.status" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>

    <!-- 角色分配对话框 -->
    <el-dialog v-model="roleDialogVisible" title="分配角色" width="400px">
      <p>用户: <strong>{{ currentUser?.username }}</strong></p>
      <el-checkbox-group v-model="selectedRoleIds">
        <el-checkbox
          v-for="role in allRoles"
          :key="role.id"
          :label="role.id"
          :value="role.id"
        >
          {{ role.roleName }} ({{ role.roleCode }})
        </el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleAssignRoles">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { userApi, roleApi } from '@/api'

const loading = ref(false)
const submitting = ref(false)
const users = ref<any[]>([])
const allRoles = ref<any[]>([])
const searchKeyword = ref('')
const dialogVisible = ref(false)
const roleDialogVisible = ref(false)
const isEdit = ref(false)
const currentUser = ref<any>(null)
const selectedRoleIds = ref<number[]>([])

const pagination = reactive({
  current: 1,
  size: 10,
  total: 0,
})

const userFormRef = ref<FormInstance>()
const userForm = reactive({
  id: 0,
  username: '',
  password: '',
  nickname: '',
  email: '',
  status: 1,
})

const userFormRules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }, { min: 6, message: '密码不少于6位', trigger: 'blur' }],
  nickname: [{ required: true, message: '请输入昵称', trigger: 'blur' }],
}

onMounted(() => {
  loadUsers()
  loadRoles()
})

async function loadUsers() {
  loading.value = true
  try {
    const res: any = await userApi.getUsers({
      current: pagination.current,
      size: pagination.size,
      keyword: searchKeyword.value || undefined,
    })
    users.value = res.data?.records || []
    pagination.total = res.data?.total || 0
  } catch (e) {
    // error handled by interceptor
  } finally {
    loading.value = false
  }
}

async function loadRoles() {
  try {
    const res: any = await roleApi.getRoles()
    allRoles.value = res.data || []
  } catch (e) {
    // ignore
  }
}

function handleSearch() {
  pagination.current = 1
  loadUsers()
}

function showCreateDialog() {
  isEdit.value = false
  Object.assign(userForm, { id: 0, username: '', password: '', nickname: '', email: '', status: 1 })
  dialogVisible.value = true
}

function showEditDialog(row: any) {
  isEdit.value = true
  Object.assign(userForm, { id: row.id, username: row.username, nickname: row.nickname, email: row.email, status: row.status })
  dialogVisible.value = true
}

function showRoleDialog(row: any) {
  currentUser.value = row
  selectedRoleIds.value = row.roleIds || []
  roleDialogVisible.value = true
}

async function handleSubmit() {
  const valid = await userFormRef.value?.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    if (isEdit.value) {
      await userApi.updateUser(userForm.id, { nickname: userForm.nickname, email: userForm.email, status: userForm.status })
      ElMessage.success('用户更新成功')
    } else {
      await userApi.createUser({ username: userForm.username, password: userForm.password, nickname: userForm.nickname, email: userForm.email })
      ElMessage.success('用户创建成功')
    }
    dialogVisible.value = false
    loadUsers()
  } finally {
    submitting.value = false
  }
}

async function handleAssignRoles() {
  if (!currentUser.value) return
  submitting.value = true
  try {
    await userApi.assignRoles(currentUser.value.id, selectedRoleIds.value)
    ElMessage.success('角色分配成功')
    roleDialogVisible.value = false
    loadUsers()
  } finally {
    submitting.value = false
  }
}

async function handleResetPassword(row: any) {
  try {
    await ElMessageBox.confirm(`确定重置用户 "${row.username}" 的密码为 "123456"?`, '提示', { type: 'warning' })
    await userApi.resetPassword(row.id, '123456')
    ElMessage.success('密码已重置为: 123456')
  } catch (e) {
    // cancelled
  }
}

async function handleDelete(row: any) {
  try {
    await ElMessageBox.confirm(`确定删除用户 "${row.username}"?`, '警告', { type: 'warning' })
    await userApi.deleteUser(row.id)
    ElMessage.success('用户已删除')
    loadUsers()
  } catch (e) {
    // cancelled
  }
}

function getRoleTagType(role: string) {
  const map: Record<string, string> = { SUPER_ADMIN: 'danger', ADMIN: 'warning', OPERATOR: '', VIEWER: 'info' }
  return map[role] || 'info'
}
</script>

<style scoped lang="scss">
.user-management {
  padding: 20px;
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;

  h2 {
    margin: 0;
    font-size: 20px;
  }
}

.search-card {
  margin-bottom: 16px;
}

.role-tag {
  margin-right: 4px;
}

.pagination-wrapper {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
</style>
