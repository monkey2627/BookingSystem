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
 * token 和 userInfo 的关系：
 *   token  — 后端鉴权凭证，每个请求放在 Header，后端验证。
 *   userInfo — 前端展示用（昵称、头像、是否商家），无需每次请求。
 *
 * isMerchant 计算属性：
 *   hasMerchantProfile=true 时前端展示商家专属菜单（档期管理、数据看板）。
 *   登录接口返回此字段，首次填写商家资料后通过 setHasMerchantProfile 更新。
 */
export const useUserStore = defineStore('user', () => {
  // 初始值从 localStorage 读取，支持刷新后恢复登录态
  const token = ref<string>(localStorage.getItem('token') ?? '')
  const userInfo = ref<UserInfo | null>(
    JSON.parse(localStorage.getItem('userInfo') ?? 'null')
  )

  /** 是否已登录（有 token 即视为登录，实际有效性由后端验证） */
  const isLoggedIn = computed(() => !!token.value)

  /** 是否有商家资料（决定是否展示商家专属功能） */
  const isMerchant = computed(() => !!userInfo.value?.hasMerchantProfile)

  /** 登录/注册成功后调用，同时更新内存和 localStorage */
  function setLoginInfo(newToken: string, info: UserInfo) {
    token.value = newToken
    userInfo.value = info
    localStorage.setItem('token', newToken)
    localStorage.setItem('userInfo', JSON.stringify(info))
  }

  /** 商家首次填写资料后调用，更新 hasMerchantProfile 展示商家菜单 */
  function setHasMerchantProfile(val: boolean) {
    if (userInfo.value) {
      userInfo.value.hasMerchantProfile = val
      localStorage.setItem('userInfo', JSON.stringify(userInfo.value))
    }
  }

  /** 退出登录：清空内存和 localStorage，WebSocket 连接由调用方断开 */
  function logout() {
    token.value = ''
    userInfo.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('userInfo')
  }

  return { token, userInfo, isLoggedIn, isMerchant, setLoginInfo, setHasMerchantProfile, logout }
})
