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
 * 身份设计原则：所有登录用户同时具备买家和卖家身份（闲鱼模式），
 *   不通过 isMerchant 标志控制功能展示。
 *   "我卖出的"页面若无商家资料则展示空列表，由用户自行决定是否填写资料。
 */
export const useUserStore = defineStore('user', () => {
  // 初始值从 localStorage 读取，支持刷新后恢复登录态
  const token = ref<string>(localStorage.getItem('token') ?? '')
  const userInfo = ref<UserInfo | null>(
    JSON.parse(localStorage.getItem('userInfo') ?? 'null')
  )

  /** 是否已登录（有 token 即视为登录，实际有效性由后端验证） */
  const isLoggedIn = computed(() => !!token.value)

  /** 登录/注册成功后调用，同时更新内存和 localStorage */
  function setLoginInfo(newToken: string, info: UserInfo) {
    token.value = newToken
    userInfo.value = info
    localStorage.setItem('token', newToken)
    localStorage.setItem('userInfo', JSON.stringify(info))
  }

  /** 退出登录：清空内存和 localStorage，WebSocket 连接由调用方断开 */
  function logout() {
    token.value = ''
    userInfo.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('userInfo')
  }

  return { token, userInfo, isLoggedIn, setLoginInfo, logout }
})
