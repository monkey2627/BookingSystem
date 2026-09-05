<template>
  <header class="app-header" v-if="!isLoginPage">
    <div class="header-inner">
      <!-- Logo -->
      <router-link to="/home" class="logo">档期预约平台</router-link>

      <!-- 导航链接 -->
      <nav class="nav-links">
        <template v-if="!userStore.isLoggedIn">
          <router-link to="/home">发现商家</router-link>
          <router-link to="/feed">动态广场</router-link>
        </template>

        <template v-else>
          <!-- 我预订的（客户功能入口） -->
          <el-dropdown @command="router.push($event)">
            <span class="nav-dropdown-trigger" :class="{ active: isCustomerPage }">
              我预订的 <el-icon class="el-icon--right"><arrow-down /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="/home">发现商家</el-dropdown-item>
                <el-dropdown-item command="/orders/my">我的预约</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>

          <!-- 我卖出的（商家功能入口） -->
          <el-dropdown @command="router.push($event)">
            <span class="nav-dropdown-trigger" :class="{ active: isMerchantPage }">
              我卖出的 <el-icon class="el-icon--right"><arrow-down /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="/orders/received">收到的预约</el-dropdown-item>
                <el-dropdown-item divided command="/merchant/profile">我的店铺</el-dropdown-item>
                <el-dropdown-item command="/schedule/manage">档期管理</el-dropdown-item>
                <el-dropdown-item command="/dashboard">数据看板</el-dropdown-item>
                <el-dropdown-item command="/questionnaire/manage">问卷管理</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>

          <router-link to="/feed">动态广场</router-link>
          <router-link to="/follows">我的关注</router-link>
          <router-link to="/rush">抢档大厅</router-link>
          <router-link to="/ai">AI助手</router-link>
        </template>
      </nav>

      <!-- 右侧 -->
      <div class="header-right">
        <template v-if="!userStore.isLoggedIn">
          <el-button type="primary" @click="$router.push('/login')">登录 / 注册</el-button>
        </template>
        <template v-else>
          <!-- 消息通知铃 -->
          <el-badge :value="unreadCount || undefined" :max="99" class="bell-badge">
            <el-button :icon="Bell" circle text @click="$router.push('/messages')" />
          </el-badge>

          <el-dropdown @command="handleCommand" style="margin-left: 8px">
            <span class="user-info">
              <el-avatar :size="32" :src="userStore.userInfo?.avatar" icon="UserFilled" />
              <span class="nickname">{{ userStore.userInfo?.nickname }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="messages">消息中心</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </div>
    </div>
  </header>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bell, ArrowDown } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { ElMessageBox } from 'element-plus'
import { unreadCount, useWebSocket } from '@/composables/useWebSocket'
import { userApi } from '@/api'

const { connect } = useWebSocket()

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const isLoginPage = computed(() => route.name === 'login')

const isCustomerPage = computed(() =>
  ['/home', '/orders/my'].includes(route.path))

const isMerchantPage = computed(() =>
  ['/orders/received', '/merchant/profile', '/schedule/manage', '/dashboard', '/questionnaire/manage'].includes(route.path))

onMounted(async () => {
  if (userStore.isLoggedIn) {
    try {
      await connect()
    } catch { /* WebSocket 连接失败不阻断页面渲染 */ }
  }
})

async function handleCommand(command: string) {
  if (command === 'logout') {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
    await userApi.logout().catch(() => {})  // 失败静默：本地凭证仍会清除
    userStore.logout()
    router.push('/login')
  } else if (command === 'messages') {
    router.push('/messages')
  }
}
</script>

<style scoped>
.app-header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  position: sticky;
  top: 0;
  z-index: 100;
}

.header-inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 16px;
  height: 60px;
  display: flex;
  align-items: center;
  gap: 24px;
}

.logo {
  font-size: 18px;
  font-weight: 600;
  color: #409eff;
  text-decoration: none;
  white-space: nowrap;
}

.nav-links {
  display: flex;
  gap: 20px;
  flex: 1;
}

.nav-links a {
  text-decoration: none;
  color: #606266;
  font-size: 14px;
  transition: color 0.2s;
  white-space: nowrap;
}

.nav-dropdown-trigger {
  display: flex;
  align-items: center;
  gap: 4px;
  color: #606266;
  font-size: 14px;
  cursor: pointer;
  white-space: nowrap;
  transition: color 0.2s;
  outline: none;
}

.nav-dropdown-trigger:hover,
.nav-dropdown-trigger.active {
  color: #409eff;
}

.nav-dropdown-trigger.active {
  font-weight: 500;
}

.nav-links a.router-link-active {
  color: #409eff;
  font-weight: 500;
}

.header-right {
  margin-left: auto;
  display: flex;
  align-items: center;
}

.bell-badge :deep(.el-badge__content) {
  top: 4px;
  right: 4px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.nickname {
  font-size: 14px;
  color: #303133;
}
</style>
