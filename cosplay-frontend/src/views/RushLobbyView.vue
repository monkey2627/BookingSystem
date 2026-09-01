<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="lobby-header">
          <span class="lobby-title">抢档大厅</span>
          <span class="lobby-sub">来自你关注的商家</span>
        </div>
      </template>

      <div v-loading="loading">
        <el-empty v-if="!loading && items.length === 0"
          description="暂无抢档期，多关注几位商家试试" :image-size="100" />

        <template v-else>
          <!-- 已开放 -->
          <div v-if="openItems.length > 0">
            <div class="section-label open">
              <span class="dot open-dot" />已开放（{{ openItems.length }}）
            </div>
            <div class="rush-list">
              <el-card v-for="item in openItems" :key="item.scheduleId"
                class="rush-card" shadow="hover">
                <RushCardContent :item="item" :is-open="true" @rush="handleRush(item)" />
              </el-card>
            </div>
          </div>

          <!-- 即将开放 -->
          <div v-if="pendingItems.length > 0" :style="openItems.length ? 'margin-top: 24px' : ''">
            <div class="section-label pending">
              <span class="dot pending-dot" />即将开放（{{ pendingItems.length }}）
            </div>
            <div class="rush-list">
              <el-card v-for="item in pendingItems" :key="item.scheduleId"
                class="rush-card" shadow="hover">
                <RushCardContent :item="item" :is-open="false"
                  :countdown="formatCountdown(item)" @rush="handleRush(item)" />
              </el-card>
            </div>
          </div>
        </template>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, defineComponent, h } from 'vue'
import { useRouter } from 'vue-router'
import { ElAvatar, ElTag, ElButton, ElMessage } from 'element-plus'
import { rushApi, scheduleApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { SERVICE_TYPE_MAP, type RushLobbyItemVO } from '@/types'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const items = ref<RushLobbyItemVO[]>([])

const nowTime = ref(Date.now())
let timer: ReturnType<typeof setInterval> | null = null

const openItems = computed(() =>
  items.value.filter(i => i.open || (i.rushOpenTime != null && new Date(i.rushOpenTime).getTime() <= nowTime.value))
)
const pendingItems = computed(() =>
  items.value.filter(i => !i.open && (i.rushOpenTime == null || new Date(i.rushOpenTime).getTime() > nowTime.value))
)

function formatCountdown(item: RushLobbyItemVO): string {
  if (!item.rushOpenTime) return ''
  const ms = new Date(item.rushOpenTime).getTime() - nowTime.value
  if (ms <= 0) return '即将开放'
  const totalSec = Math.floor(ms / 1000)
  const hrs = Math.floor(totalSec / 3600)
  const mins = Math.floor((totalSec % 3600) / 60)
  const secs = totalSec % 60
  if (hrs >= 24) return `${Math.floor(hrs / 24)}天后开放`
  if (hrs > 0) return `${hrs}:${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')} 后开放`
  return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')} 后开放`
}

async function handleRush(item: RushLobbyItemVO) {
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  try {
    const data = await scheduleApi.rush(item.scheduleId)
    ElMessage.success(data.message || `抢档成功！排队第 ${data.rankNo} 位`)
    await load()
  } catch { /* errors shown by request interceptor */ }
}

async function load() {
  loading.value = true
  try {
    items.value = await rushApi.getLobby()
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  load()
  timer = setInterval(() => { nowTime.value = Date.now() }, 1000)
})
onUnmounted(() => { if (timer) clearInterval(timer) })

// 卡片内容子组件（避免在每个 v-for 格子里重复写相同的 DOM 结构）
const RushCardContent = defineComponent({
  props: {
    item: { type: Object as () => RushLobbyItemVO, required: true },
    isOpen: { type: Boolean, required: true },
    countdown: { type: String, default: '' },
  },
  emits: ['rush'],
  setup(props, { emit }) {
    return () => h('div', { class: 'rush-card-inner' }, [
      h(ElAvatar, { src: props.item.merchantAvatar, size: 48 }),
      h('div', { class: 'card-body' }, [
        h('div', { class: 'card-row' }, [
          h('span', { class: 'merchant-name' }, props.item.merchantNickname),
          h(ElTag, { size: 'small', type: 'warning' },
            { default: () => SERVICE_TYPE_MAP[props.item.serviceType] ?? '其他' }),
        ]),
        h('div', { class: 'card-row' }, [
          h('span', { class: 'date-label' }, props.item.date),
          props.item.timeSlot
            ? h('span', { class: 'timeslot' }, props.item.timeSlot)
            : null,
        ]),
        h('div', { class: 'card-row' }, [
          h('span', { class: 'queue-info' },
            `排队：${props.item.currentQueueSize ?? 0} / ${props.item.maxQueueSize}`),
          props.isOpen
            ? h(ElButton, { type: 'primary', size: 'small', onClick: () => emit('rush') },
                { default: () => '立即抢档' })
            : h('span', { class: 'countdown-label' }, props.countdown),
        ]),
      ]),
    ])
  },
})
</script>

<style scoped>
.lobby-header { display: flex; align-items: baseline; gap: 10px; }
.lobby-title { font-size: 16px; font-weight: 600; }
.lobby-sub { font-size: 13px; color: #909399; }

.section-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 500;
  margin-bottom: 12px;
}
.section-label.open { color: #67c23a; }
.section-label.pending { color: #e6a23c; }

.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.open-dot { background: #67c23a; }
.pending-dot { background: #e6a23c; }

.rush-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 12px;
}

:deep(.rush-card-inner) { display: flex; gap: 12px; align-items: flex-start; }
:deep(.card-body) { flex: 1; min-width: 0; }
:deep(.card-row) { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
:deep(.card-row:last-child) { margin-bottom: 0; }
:deep(.merchant-name) { font-weight: 600; font-size: 14px; color: #303133; }
:deep(.date-label) { font-size: 16px; font-weight: 600; color: #303133; }
:deep(.timeslot) { font-size: 13px; color: #606266; }
:deep(.queue-info) { font-size: 13px; color: #909399; flex: 1; }
:deep(.countdown-label) { font-size: 13px; color: #e6a23c; font-weight: 600; }
</style>
