<template>
  <header class="app-header" v-if="!isLoginPage">
    <div class="header-inner">
      <!-- Logo -->
      <router-link to="/home" class="logo">档期预约平台</router-link>

      <!-- 导航链接 -->
      <nav class="nav-links">
        <router-link to="/home">发现商家</router-link>
        <router-link to="/feed">动态广场</router-link>

        <!-- 登录后所有用户可见 -->
        <template v-if="userStore.isLoggedIn">
          <router-link to="/orders/my">我预定的</router-link>
          <router-link to="/orders/received">我卖出的</router-link>
          <router-link to="/follows">我的关注</router-link>
          <router-link to="/merchant/profile">我的店铺</router-link>
          <router-link to="/schedule/manage">档期管理</router-link>
          <router-link to="/dashboard">数据看板</router-link>
          <router-link to="/questionnaire/manage">问卷管理</router-link>
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
import { Bell } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { ElMessageBox } from 'element-plus'
import { unreadCount, useWebSocket } from '@/composables/useWebSocket'

const { connect } = useWebSocket()

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const isLoginPage = computed(() => route.name === 'login')

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
