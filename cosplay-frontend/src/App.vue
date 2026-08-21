<template>
  <!-- AppHeader 始终显示（登录页除外，由 Header 内部判断） -->
  <AppHeader />

  <!-- router-view 是当前路由对应组件的渲染占位符 -->
  <!-- Transition 给页面切换加淡入淡出动画 -->
  <router-view v-slot="{ Component }">
    <transition name="fade" mode="out-in">
      <component :is="Component" />
    </transition>
  </router-view>
</template>

<script setup lang="ts">
import AppHeader from '@/components/AppHeader.vue'
</script>

<style>
* {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
}

body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  background-color: #f5f7fa;
  color: #303133;
}

/* 页面内容区域统一最大宽度和居中 */
.page-container {
  max-width: 1200px;
  margin: 0 auto;
  padding: 24px 16px;
}

/* 路由切换淡入淡出 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
