import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import type { UserInfo } from '@/types'

export const useUserStore = defineStore('user', () => {
  const token = ref<string>(localStorage.getItem('token') ?? '')
  const userInfo = ref<UserInfo | null>(
    JSON.parse(localStorage.getItem('userInfo') ?? 'null')
  )

  const isLoggedIn = computed(() => !!token.value)
  const isMerchant = computed(() => !!userInfo.value?.hasMerchantProfile)

  function setLoginInfo(newToken: string, info: UserInfo) {
    token.value = newToken
    userInfo.value = info
    localStorage.setItem('token', newToken)
    localStorage.setItem('userInfo', JSON.stringify(info))
  }

  function setHasMerchantProfile(val: boolean) {
    if (userInfo.value) {
      userInfo.value.hasMerchantProfile = val
      localStorage.setItem('userInfo', JSON.stringify(userInfo.value))
    }
  }

  function logout() {
    token.value = ''
    userInfo.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('userInfo')
  }

  return { token, userInfo, isLoggedIn, isMerchant, setLoginInfo, setHasMerchantProfile, logout }
})
