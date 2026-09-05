<template>
  <div class="login-page">
    <div class="login-card">
      <h2 class="title">档期预约平台</h2>

      <!-- el-tabs：点击"登录"/"注册"切换表单 -->
      <el-tabs v-model="activeTab" stretch>
        <!-- ── 登录表单 ── -->
        <el-tab-pane label="登录" name="login">
          <el-form :model="loginForm" :rules="loginRules" ref="loginFormRef" label-width="0">
            <el-form-item prop="phone">
              <el-input v-model="loginForm.phone" placeholder="手机号" prefix-icon="Phone" maxlength="11" />
            </el-form-item>
            <el-form-item prop="password">
              <el-input v-model="loginForm.password" placeholder="密码" type="password" prefix-icon="Lock"
                show-password @keyup.enter="handleLogin" />
            </el-form-item>
            <el-button type="primary" style="width: 100%" :loading="loading" @click="handleLogin">
              登录
            </el-button>
          </el-form>
        </el-tab-pane>

        <!-- ── 注册表单 ── -->
        <el-tab-pane label="注册" name="register">
          <el-form :model="regForm" :rules="regRules" ref="regFormRef" label-width="0">
            <el-form-item prop="phone">
              <el-input v-model="regForm.phone" placeholder="手机号" prefix-icon="Phone" maxlength="11" />
            </el-form-item>
            <el-form-item prop="nickname">
              <el-input v-model="regForm.nickname" placeholder="昵称" prefix-icon="User" />
            </el-form-item>
            <el-form-item prop="password">
              <el-input v-model="regForm.password" placeholder="密码（6位以上）" type="password"
                prefix-icon="Lock" show-password />
            </el-form-item>
            <el-form-item prop="confirmPassword">
              <el-input v-model="regForm.confirmPassword" placeholder="确认密码" type="password"
                prefix-icon="Lock" show-password />
            </el-form-item>
            <el-button type="primary" style="width: 100%" :loading="loading" @click="handleRegister">
              注册
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { userApi } from '@/api'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const activeTab = ref<'login' | 'register'>('login')
const loading = ref(false)

// ── 登录 ──────────────────────────────────────────────────
const loginFormRef = ref<FormInstance>()
const loginForm = ref({ phone: '', password: '' })
const loginRules: FormRules = {
  phone: [
    { required: true, message: '请输入手机号' },
    { pattern: /^1\d{10}$/, message: '请输入正确的手机号' }
  ],
  password: [{ required: true, message: '请输入密码' }]
}

async function handleLogin() {
  if (!await loginFormRef.value?.validate().catch(() => false)) return
  loading.value = true
  try {
    const data = await userApi.login(loginForm.value)
    userStore.setLoginInfo(data.accessToken, data.refreshToken, data.userInfo)
    ElMessage.success('登录成功')
    // 登录后跳回登录前想去的页面，没有则去首页
    const redirect = (route.query.redirect as string) || '/home'
    router.push(redirect)
  } finally {
    loading.value = false
  }
}

// ── 注册 ──────────────────────────────────────────────────
const regFormRef = ref<FormInstance>()
const regForm = ref({ phone: '', nickname: '', password: '', confirmPassword: '' })

// 自定义校验：确认密码必须和密码一致
function validateConfirmPassword(_rule: any, value: string, callback: Function) {
  if (value !== regForm.value.password) {
    callback(new Error('两次输入的密码不一致'))
  } else {
    callback()
  }
}

const regRules: FormRules = {
  phone: [
    { required: true, message: '请输入手机号' },
    { pattern: /^1\d{10}$/, message: '请输入正确的手机号' }
  ],
  nickname: [{ required: true, message: '请输入昵称' }],
  password: [
    { required: true, message: '请输入密码' },
    { min: 6, message: '密码至少 6 位' }
  ],
  confirmPassword: [
    { required: true, message: '请确认密码' },
    { validator: validateConfirmPassword }
  ]
}

async function handleRegister() {
  if (!await regFormRef.value?.validate().catch(() => false)) return
  loading.value = true
  try {
    await userApi.register({
      phone: regForm.value.phone,
      nickname: regForm.value.nickname,
      password: regForm.value.password
    })
    ElMessage.success('注册成功，请登录')
    activeTab.value = 'login'
    loginForm.value.phone = regForm.value.phone
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}

.login-card {
  background: #fff;
  border-radius: 12px;
  padding: 40px 36px;
  width: 400px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.15);
}

.title {
  text-align: center;
  margin-bottom: 24px;
  color: #303133;
  font-size: 22px;
}
</style>
