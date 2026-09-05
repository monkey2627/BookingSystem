import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import type { UserInfo } from '@/types'

/**
 * 用户状态 Store（Pinia）
 *
 * 持久化策略：token 和 userInfo 同步存入 localStorage，
 *   页面刷新后 Store 重新初始化时从 localStorage 读取，
 *   实现"刷新不掉登录态"。
 *
 * 双令牌设计：
 *   accessToken：JWT，2小时有效期，每次请求放入 Header "token"。
 *   refreshToken：{userId}:{UUID}，7天有效期，accessToken 过期时由 request.ts 自动换签，
 *     用户无感知。主动注销调用 /api/user/logout 删除服务端 Redis 记录后清除本地存储。
 *
 * 身份设计原则：所有登录用户同时具备买家和卖家身份（闲鱼模式），
 *   不通过 isMerchant 标志控制功能展示。
 */
export const useUserStore = defineStore('user', () => {
  // 初始值从 localStorage 读取，支持刷新后恢复登录态
  const token = ref<string>(localStorage.getItem('token') ?? '')
  const refreshToken = ref<string>(localStorage.getItem('refreshToken') ?? '')
  const userInfo = ref<UserInfo | null>(
    JSON.parse(localStorage.getItem('userInfo') ?? 'null')
  )

  /** 是否已登录（有 accessToken 即视为登录，实际有效性由后端验证） */
  const isLoggedIn = computed(() => !!token.value)

  /** 登录/注册成功后调用，同时更新内存和 localStorage */
  function setLoginInfo(newAccessToken: string, newRefreshToken: string, info: UserInfo) {
    token.value = newAccessToken
    refreshToken.value = newRefreshToken
    userInfo.value = info
    localStorage.setItem('token', newAccessToken)
    localStorage.setItem('refreshToken', newRefreshToken)
    localStorage.setItem('userInfo', JSON.stringify(info))
  }

  /** 退出登录：清空内存和 localStorage，WebSocket 连接由调用方断开 */
  function logout() {
    token.value = ''
    refreshToken.value = ''
    userInfo.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('refreshToken')
    localStorage.removeItem('userInfo')
  }

  return { token, refreshToken, userInfo, isLoggedIn, setLoginInfo, logout }
})
