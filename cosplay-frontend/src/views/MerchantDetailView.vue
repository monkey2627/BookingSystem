<template>
  <div class="page-container" v-loading="pageLoading">
    <div v-if="merchant" class="detail-layout">
      <!-- ── 左列：商家信息 ── -->
      <el-card class="info-card">
        <div class="merchant-header">
          <el-avatar :size="80" :src="merchant.avatar" />
          <div style="flex:1">
            <div class="merchant-name">{{ merchant.nickname }}</div>
            <el-rate :model-value="merchant.avgScore" disabled show-score score-template="{value}分"
              text-color="#ff9900" />
            <div class="review-count">{{ merchant.reviewCount }} 条评价</div>
          </div>
          <!-- 操作按钮组：已登录且非本人时显示 -->
          <div v-if="userStore.isLoggedIn && !isMerchantOwner" class="action-btns">
            <el-button
              :type="isFollowing ? 'default' : 'primary'"
              :plain="isFollowing"
              size="small"
              :loading="followLoading"
              @click="toggleFollow">
              {{ isFollowing ? '已关注' : '+ 关注' }}
            </el-button>
            <el-button size="small" @click="goToChat">发消息</el-button>
          </div>
        </div>

        <el-divider />

        <div class="info-row"><el-icon><Location /></el-icon>{{ merchant.city || '未填写城市' }}</div>
        <div class="service-types">
          <el-tag v-for="t in merchant.serviceTypes" :key="t" type="success" style="margin: 4px">
            {{ SERVICE_TYPE_MAP[t] }}
          </el-tag>
        </div>

        <el-divider />

        <div class="intro">{{ merchant.intro || '该商家暂未填写简介' }}</div>

        <!-- 店铺已关闭提醒 -->
        <el-alert v-if="merchant.status === 0" type="warning" :closable="false" show-icon
          title="该商家已暂停接单" description="店铺已关闭，暂时无法提交新预约" style="margin-top: 12px" />

        <!-- 外部联系方式 -->
        <el-divider />
        <div class="links">
          <el-link v-if="merchant.alipayLink" :href="merchant.alipayLink" target="_blank" type="primary">
            支付宝收款
          </el-link>
          <el-link v-if="merchant.xianyuLink" :href="merchant.xianyuLink" target="_blank" type="warning">
            闲鱼主页
          </el-link>
          <el-link v-if="merchant.xiaohongshuLink" :href="merchant.xiaohongshuLink" target="_blank" type="danger">
            小红书
          </el-link>
        </div>
      </el-card>

      <!-- ── 右列：档期日历 ── -->

      <!-- 完全私密：不展示日历 -->
      <el-card v-if="merchant.scheduleVisibility === 2" class="calendar-card calendar-private">
        <el-empty description="该商家档期不对外公开" :image-size="80">
          <template #description>
            <span style="color:#909399;font-size:13px">该商家档期不对外公开，请通过联系方式私下咨询</span>
          </template>
        </el-empty>
      </el-card>

      <!-- 全公开 / 仅忙闲：显示日历 -->
      <el-card v-else class="calendar-card">
        <!-- 月份导航 -->
        <div class="calendar-header">
          <el-button :icon="ArrowLeft" circle @click="prevMonth" />
          <span class="month-label">{{ currentMonth }}</span>
          <el-button :icon="ArrowRight" circle @click="nextMonth" />
        </div>

        <!-- 图例 -->
        <div class="legend">
          <span v-for="(cfg, status) in SCHEDULE_STATUS_MAP" :key="status" class="legend-item">
            <span class="legend-dot" :style="{ background: cfg.color }"></span>{{ cfg.label }}
          </span>
          <span class="legend-item">
            <span class="legend-dot" style="background: #c0c4cc"></span>无档期
          </span>
          <span v-if="merchant.scheduleVisibility === 1" class="legend-item privacy-tip">
            （商家已开启隐私模式，仅显示忙闲状态）
          </span>
        </div>

        <!-- 星期表头 -->
        <div class="week-header">
          <span v-for="d in ['日', '一', '二', '三', '四', '五', '六']" :key="d">{{ d }}</span>
        </div>

        <!-- 日历格子 -->
        <div class="calendar-grid">
          <div v-for="(day, idx) in calendarDays" :key="idx"
            :class="['day-cell', day ? getDayClass(day.dateStr) : 'empty']"
            @click="day && handleDayClick(day.dateStr)">
            <template v-if="day">
              <span class="day-num">{{ day.num }}</span>
              <!-- 多档期：显示数量或单个状态点 -->
              <template v-if="scheduleMap[day.dateStr]?.length">
                <span v-if="scheduleMap[day.dateStr].some(s => isRushPending(s))"
                  class="rush-countdown">{{ formatCountdown(scheduleMap[day.dateStr].find(s => isRushPending(s))!) }}</span>
                <span v-else class="day-dot"
                  :style="{ background: SCHEDULE_STATUS_MAP[scheduleMap[day.dateStr].find(s => s.status === 0)?.status ?? scheduleMap[day.dateStr][0].status]?.color }" />
                <span v-if="scheduleMap[day.dateStr].length > 1" class="multi-badge">{{ scheduleMap[day.dateStr].length }}</span>
              </template>
            </template>
          </div>
        </div>
      </el-card>
    </div>

    <!-- ── 评价列表 ── -->
    <el-card class="reviews-card" v-if="merchant">
      <template #header>
        <span class="reviews-title">用户评价</span>
        <span class="reviews-summary" v-if="reviewTotal > 0">
          共 {{ reviewTotal }} 条，综合评分 {{ merchant.avgScore.toFixed(1) }}
        </span>
      </template>

      <div v-if="reviews.length === 0 && !reviewLoading" class="review-empty">
        <el-empty description="暂无评价" :image-size="80" />
      </div>

      <div v-else class="review-list" v-loading="reviewLoading">
        <div v-for="r in reviews" :key="r.id" class="review-item">
          <!-- 评价人信息行 -->
          <div class="review-meta">
            <span class="reviewer-name">{{ r.userNickname }}</span>
            <el-rate :model-value="r.score" disabled size="small" style="margin: 0 8px" />
            <span class="review-time">{{ r.createTime?.slice(0, 10) }}</span>
          </div>

          <!-- 评价正文 -->
          <p class="review-content">{{ r.content }}</p>

          <!-- 商家回复区块 -->
          <div v-if="r.reply" class="merchant-reply">
            <span class="reply-label">商家回复：</span>{{ r.reply }}
          </div>

          <!-- 回复输入框：仅当前页商家且该条尚未有回复时显示 -->
          <template v-if="isMerchantOwner && !r.reply">
            <div v-if="replyingId === r.id" class="reply-input-row">
              <el-input v-model="replyText" type="textarea" :rows="2"
                placeholder="输入回复内容…" maxlength="200" show-word-limit />
              <div class="reply-actions">
                <el-button size="small" @click="replyingId = null; replyText = ''">取消</el-button>
                <el-button size="small" type="primary" :loading="replyLoading"
                  @click="submitReply(r.id)">提交回复</el-button>
              </div>
            </div>
            <el-button v-else size="small" plain @click="replyingId = r.id; replyText = ''">
              回复
            </el-button>
          </template>

          <el-divider style="margin: 12px 0" />
        </div>
      </div>

      <!-- 分页 -->
      <el-pagination
        v-if="reviewTotal > reviewPageSize"
        v-model:current-page="reviewPage"
        :page-size="reviewPageSize"
        :total="reviewTotal"
        layout="prev, pager, next"
        style="margin-top: 16px; justify-content: center"
        @current-change="fetchReviews" />
    </el-card>

    <!-- ── 商家动态 ── -->
    <el-card class="posts-card" v-if="merchant">
      <template #header>
        <span class="section-title">商家动态</span>
      </template>
      <div v-loading="postsLoading">
        <el-empty v-if="merchantPosts.length === 0 && !postsLoading" description="该商家暂未发布动态" :image-size="60" />
        <div v-for="post in merchantPosts" :key="post.id" class="post-item">
          <div class="post-time">{{ formatPostTime(post.createTime) }}</div>
          <p class="post-content">{{ post.content }}</p>
          <div v-if="post.images?.length" class="post-images">
            <el-image v-for="(img, i) in post.images.slice(0, 9)" :key="i"
              :src="img" :preview-src-list="post.images" fit="cover" class="post-thumb" />
          </div>
          <el-divider style="margin: 12px 0" />
        </div>
        <div v-if="postsHasMore" class="posts-more">
          <el-button text size="small" :loading="postsLoading" @click="loadMorePosts">
            加载更多动态
          </el-button>
        </div>
      </div>
    </el-card>

    <!-- ── 多档选择弹窗（同一天有多个档期时） ── -->
    <el-dialog v-model="dayPickerVisible" title="选择档期" width="400px">
      <div v-for="s in dayPickerSchedules" :key="s.id" class="picker-row"
        :class="{ 'picker-row--disabled': s.status !== 0 }"
        @click="openScheduleDialog(s)">
        <div class="picker-time">{{ s.timeSlot || '全天' }}</div>
        <el-tag :type="s.status === 0 ? 'success' : s.status === 1 ? 'warning' : 'info'" size="small">
          {{ s.status === 0 ? '可预约' : s.status === 1 ? '已预约' : '不可用' }}
        </el-tag>
      </div>
    </el-dialog>

    <!-- ── 预约对话框 ── -->
    <el-dialog v-model="bookDialogVisible"
      :title="selectedSchedule?.status !== 0 ? '档期信息' : selectedSchedule?.bookType === 1 ? '参与抢档期' : '预约档期'"
      width="480px">
      <div v-if="selectedSchedule" class="book-dialog-content">
        <p><strong>日期：</strong>{{ selectedSchedule.date }}</p>
        <p><strong>时段：</strong>{{ selectedSchedule.timeSlot || '全天' }}</p>

        <!-- 已预约/不可用：仅展示状态 -->
        <template v-if="selectedSchedule.status !== 0">
          <el-alert
            :type="selectedSchedule.status === 1 ? 'warning' : 'info'"
            :title="selectedSchedule.status === 1 ? '该档期已被预约，无法再次预约' : '该档期暂不开放预约'"
            :closable="false" show-icon style="margin-top: 12px" />
        </template>

        <!-- 直接预约：填写备注 + 问卷 -->
        <template v-else-if="selectedSchedule.bookType === 0">
          <el-input v-model="remark" type="textarea" placeholder="备注（可选）" :rows="3" style="margin-top: 12px" />

          <template v-if="questionnaire">
            <el-divider>{{ questionnaire.title }}</el-divider>
            <el-form :model="questionnaireAnswer" label-position="top">
              <el-form-item
                v-for="q in questionnaire.questions" :key="q.id"
                :label="q.label + (q.required ? ' *' : '')">
                <el-input v-if="q.type === 'text'" v-model="questionnaireAnswer[q.id]"
                  placeholder="请填写" />
                <el-radio-group v-else-if="q.type === 'radio'" v-model="questionnaireAnswer[q.id]">
                  <el-radio v-for="opt in q.options" :key="opt" :value="opt">{{ opt }}</el-radio>
                </el-radio-group>
                <el-checkbox-group v-else-if="q.type === 'checkbox'" v-model="questionnaireAnswer[q.id]">
                  <el-checkbox v-for="opt in q.options" :key="opt" :value="opt">{{ opt }}</el-checkbox>
                </el-checkbox-group>
              </el-form-item>
            </el-form>
          </template>
        </template>

        <!-- 抢档期：显示当前排队人数 -->
        <template v-else>
          <el-alert type="warning" :closable="false" show-icon style="margin-top: 12px">
            当前排队：{{ selectedSchedule.currentQueueSize ?? 0 }} / {{ selectedSchedule.maxQueueSize }} 人
          </el-alert>
        </template>
      </div>

      <template #footer>
        <el-button @click="bookDialogVisible = false">关闭</el-button>
        <el-button v-if="selectedSchedule?.status === 0" type="primary" :loading="bookLoading"
          @click="selectedSchedule?.bookType === 1 ? handleRush() : handleBook()">
          {{ selectedSchedule?.bookType === 1 ? '参与抢档期' : '确认预约' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, Location } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { merchantApi, scheduleApi, bookingApi, reviewApi, followApi, postApi, questionnaireApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { SERVICE_TYPE_MAP, SCHEDULE_STATUS_MAP, type MerchantVO, type ScheduleVO, type ReviewVO, type PostVO, type QuestionnaireVO } from '@/types'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const merchantId = Number(route.params.id)
const pageLoading = ref(false)
const merchant = ref<MerchantVO | null>(null)
const schedules = ref<ScheduleVO[]>([])

// 每秒刷新，驱动抢档期倒计时重新渲染
const nowTime = ref(Date.now())
let timerInterval: ReturnType<typeof setInterval> | null = null

// 当前显示的月份，格式 'YYYY-MM'
const now = new Date()
const currentMonth = ref(`${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`)

// 每天可有多个档期，按日期分组
const scheduleMap = computed<Record<string, ScheduleVO[]>>(() => {
  const map: Record<string, ScheduleVO[]> = {}
  schedules.value.forEach(s => {
    if (!map[s.date]) map[s.date] = []
    map[s.date].push(s)
  })
  return map
})

// 生成当月日历格子数组
// 返回 (null | {num, dateStr})[]，null 表示月初前的空白占位
const calendarDays = computed(() => {
  const [year, month] = currentMonth.value.split('-').map(Number)
  const firstDay = new Date(year, month - 1, 1).getDay()  // 0=周日
  const daysInMonth = new Date(year, month, 0).getDate()

  const days: (null | { num: number; dateStr: string })[] = []
  for (let i = 0; i < firstDay; i++) days.push(null)  // 空白占位
  for (let d = 1; d <= daysInMonth; d++) {
    const dateStr = `${year}-${String(month).padStart(2, '0')}-${String(d).padStart(2, '0')}`
    days.push({ num: d, dateStr })
  }
  return days
})

function isRushPending(s: ScheduleVO): boolean {
  return s.bookType === 1 && s.rushOpenTime != null && new Date(s.rushOpenTime).getTime() > nowTime.value
}

function formatCountdown(s: ScheduleVO): string {
  if (!s.rushOpenTime) return ''
  const ms = new Date(s.rushOpenTime).getTime() - nowTime.value
  if (ms <= 0) return '开放中'
  const totalSec = Math.floor(ms / 1000)
  const h = Math.floor(totalSec / 3600)
  const m = Math.floor((totalSec % 3600) / 60)
  const sec = totalSec % 60
  if (h >= 24) return `${Math.floor(h / 24)}天后`
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`
  return `${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`
}

function getDayClass(dateStr: string): string {
  const list = scheduleMap.value[dateStr]
  if (!list || list.length === 0) return 'day-empty'
  if (list.some(s => isRushPending(s))) return 'day-rush'
  if (list.some(s => s.status === 0)) return 'day-available'
  if (list.some(s => s.status === 1)) return 'day-booked'
  return 'day-unavailable'
}

function prevMonth() {
  const [y, m] = currentMonth.value.split('-').map(Number)
  const d = new Date(y, m - 2, 1)
  currentMonth.value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

function nextMonth() {
  const [y, m] = currentMonth.value.split('-').map(Number)
  const d = new Date(y, m, 1)
  currentMonth.value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

// 月份变化时重新拉取档期
watch(currentMonth, fetchSchedules)

// ── 关注 ─────────────────────────────────────────────────
const isFollowing = ref(false)
const followLoading = ref(false)

async function fetchFollowStatus() {
  if (!userStore.isLoggedIn || isMerchantOwner.value) return
  isFollowing.value = await followApi.isFollowing(merchantId)
}

function goToChat() {
  if (!merchant.value) return
  router.push({ path: '/messages', query: { userId: merchant.value.userId, nickname: merchant.value.nickname } })
}

async function toggleFollow() {
  followLoading.value = true
  try {
    if (isFollowing.value) {
      await followApi.unfollow(merchantId)
      isFollowing.value = false
      ElMessage.success('已取消关注')
    } else {
      await followApi.follow(merchantId)
      isFollowing.value = true
      ElMessage.success('关注成功')
    }
  } finally {
    followLoading.value = false
  }
}

// ── 问卷 ──────────────────────────────────────────────────
const questionnaire = ref<QuestionnaireVO | null>(null)
const questionnaireAnswer = ref<Record<string, any>>({})

async function fetchQuestionnaire() {
  try {
    const list = await questionnaireApi.getByMerchant(merchantId)
    questionnaire.value = list.find(q => q.isRequired) ?? list[0] ?? null
  } catch { /* ignore */ }
}

// ── 商家动态 ─────────────────────────────────────────────
const merchantPosts = ref<PostVO[]>([])
const postsLoading = ref(false)
const postsHasMore = ref(false)
const postsCursor = ref<number | null>(null)

function formatPostTime(str: string) {
  return str?.slice(0, 16).replace('T', ' ') ?? ''
}

async function fetchMerchantPosts(cursor: number | null = null) {
  postsLoading.value = true
  try {
    const data = await postApi.listByMerchant(merchantId, { lastId: cursor, size: 5 })
    if (cursor === null) {
      merchantPosts.value = data.list
    } else {
      merchantPosts.value.push(...data.list)
    }
    postsHasMore.value = data.hasMore
    postsCursor.value = data.nextCursor
  } finally {
    postsLoading.value = false
  }
}

function loadMorePosts() { fetchMerchantPosts(postsCursor.value) }

// ── 预约 / 抢档期 ──────────────────────────────────────────
const bookDialogVisible = ref(false)
const selectedSchedule = ref<ScheduleVO | null>(null)
const remark = ref('')
const bookLoading = ref(false)

// 当天多档选择弹窗
const dayPickerVisible = ref(false)
const dayPickerSchedules = ref<ScheduleVO[]>([])

function handleDayClick(dateStr: string) {
  const list = scheduleMap.value[dateStr]
  if (!list || list.length === 0) return
  if (merchant.value?.scheduleVisibility === 2) return

  if (list.length === 1) {
    openScheduleDialog(list[0])
  } else {
    dayPickerSchedules.value = list
    dayPickerVisible.value = true
  }
}

function openScheduleDialog(s: ScheduleVO) {
  dayPickerVisible.value = false

  if (s.status !== 0) {
    // 已预约或不可用：仅展示信息
    selectedSchedule.value = s
    bookDialogVisible.value = true
    return
  }

  if (isRushPending(s)) {
    ElMessage.info(`该抢档期尚未开放，还剩 ${formatCountdown(s)}`)
    return
  }
  if (merchant.value?.status === 0) {
    ElMessage.warning('该商家已暂停接单，无法预约')
    return
  }
  if (!userStore.isLoggedIn) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  selectedSchedule.value = s
  remark.value = ''
  questionnaireAnswer.value = {}
  if (questionnaire.value) {
    for (const q of questionnaire.value.questions) {
      if (q.type === 'checkbox') questionnaireAnswer.value[q.id] = []
    }
  }
  bookDialogVisible.value = true
}

async function handleBook() {
  if (!selectedSchedule.value) return
  // 验证必填问卷项
  if (questionnaire.value) {
    for (const q of questionnaire.value.questions) {
      if (q.required) {
        const ans = questionnaireAnswer.value[q.id]
        if (!ans || (Array.isArray(ans) && ans.length === 0)) {
          ElMessage.warning(`请完成问卷：${q.label}`)
          return
        }
      }
    }
  }
  bookLoading.value = true
  try {
    await bookingApi.create({
      scheduleId: selectedSchedule.value.id,
      remark: remark.value,
      questionnaireAnswer: questionnaire.value ? JSON.stringify(questionnaireAnswer.value) : undefined
    })
    ElMessage.success('预约成功！')
    bookDialogVisible.value = false
    fetchSchedules()
  } finally {
    bookLoading.value = false
  }
}

async function handleRush() {
  if (!selectedSchedule.value) return
  bookLoading.value = true
  try {
    const data = await scheduleApi.rush(selectedSchedule.value.id)
    ElMessage.success(data.message || `抢档成功！排队第 ${data.rankNo} 位`)
    bookDialogVisible.value = false
    fetchSchedules()
  } finally {
    bookLoading.value = false
  }
}

// ── 评价列表 ──────────────────────────────────────────────
const reviews = ref<ReviewVO[]>([])
const reviewTotal = ref(0)
const reviewPage = ref(1)
const reviewPageSize = 10
const reviewLoading = ref(false)

// 当前登录用户是否是本页商家（用于显示"回复"按钮）
const isMerchantOwner = computed(() => merchant.value?.userId === userStore.userInfo?.id)

// 正在回复的评价 id，null 表示没有展开回复框
const replyingId = ref<number | null>(null)
const replyText = ref('')
const replyLoading = ref(false)

async function fetchReviews() {
  reviewLoading.value = true
  try {
    const data = await reviewApi.listByMerchant(merchantId, { page: reviewPage.value, size: reviewPageSize })
    reviews.value = data.records
    reviewTotal.value = data.total
  } finally {
    reviewLoading.value = false
  }
}

async function submitReply(reviewId: number) {
  if (!replyText.value.trim()) return
  replyLoading.value = true
  try {
    await reviewApi.reply(reviewId, { reply: replyText.value })
    ElMessage.success('回复成功')
    // 直接更新本地数据，避免重新请求整页
    const r = reviews.value.find(rv => rv.id === reviewId)
    if (r) r.reply = replyText.value
    replyingId.value = null
    replyText.value = ''
  } finally {
    replyLoading.value = false
  }
}

// ── 数据加载 ──────────────────────────────────────────────
async function fetchMerchant() {
  merchant.value = await merchantApi.getById(merchantId)
}

async function fetchSchedules() {
  schedules.value = await scheduleApi.listByMonth(merchantId, currentMonth.value)
}

onMounted(async () => {
  // 分享链接跳转：?rushDate=YYYY-MM-DD，先跳到对应月份再自动打开弹窗
  const rushDate = route.query.rushDate as string | undefined
  if (rushDate) {
    currentMonth.value = rushDate.slice(0, 7)
  }

  pageLoading.value = true
  await Promise.all([
    fetchMerchant(),
    fetchSchedules(),
    fetchReviews(),
    fetchFollowStatus(),
    fetchQuestionnaire(),
    fetchMerchantPosts()
  ])
  pageLoading.value = false
  timerInterval = setInterval(() => { nowTime.value = Date.now() }, 1000)

  if (rushDate) {
    const s = scheduleMap.value[rushDate]
    if (s && s.length > 0) {
      selectedSchedule.value = s[0]
      bookDialogVisible.value = true
    }
  }
})

onUnmounted(() => {
  if (timerInterval) clearInterval(timerInterval)
})
</script>

<style scoped>
.detail-layout {
  display: grid;
  grid-template-columns: 320px 1fr;
  gap: 20px;
  align-items: start;
}

@media (max-width: 768px) {
  .detail-layout { grid-template-columns: 1fr; }
}

.merchant-header {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 16px;
}

.action-btns {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex-shrink: 0;
}

.merchant-name {
  font-size: 20px;
  font-weight: 600;
  margin-bottom: 8px;
}

.review-count { font-size: 12px; color: #909399; margin-top: 4px; }
.info-row { display: flex; align-items: center; gap: 6px; margin-bottom: 8px; color: #606266; }
.intro { color: #606266; font-size: 14px; line-height: 1.6; }
.links { display: flex; gap: 16px; flex-wrap: wrap; }

/* ── 日历 ── */
.calendar-header {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-bottom: 12px;
}

.month-label { font-size: 16px; font-weight: 600; min-width: 100px; text-align: center; }

.legend {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 12px;
  font-size: 13px;
  color: #606266;
}

.legend-item { display: flex; align-items: center; gap: 4px; }
.legend-dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }
.privacy-tip { color: #909399; font-size: 12px; }
.calendar-private { display: flex; align-items: center; justify-content: center; min-height: 200px; }

.week-header {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  text-align: center;
  font-size: 13px;
  color: #909399;
  margin-bottom: 4px;
}

.calendar-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 4px;
}

.day-cell {
  aspect-ratio: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  font-size: 14px;
  position: relative;
  transition: background 0.15s;
}

.day-num { line-height: 1; }
.day-dot { width: 6px; height: 6px; border-radius: 50%; margin-top: 3px; }

.empty { pointer-events: none; }
.day-empty { color: #c0c4cc; }
.day-available { cursor: pointer; background: #f0f9eb; color: #67c23a; font-weight: 500; }
.day-available:hover { background: #e1f3d8; }
.day-booked { color: #e6a23c; background: #fdf6ec; }
.day-unavailable { color: #909399; text-decoration: line-through; }
.day-rush { cursor: pointer; background: #fdf6ec; color: #e6a23c; font-weight: 500; }
.day-rush:hover { background: #faecd8; }
.rush-countdown { font-size: 9px; color: #e6a23c; line-height: 1; margin-top: 2px; font-weight: 600; white-space: nowrap; }
.multi-badge {
  position: absolute; bottom: 4px; right: 4px;
  background: #409eff; color: #fff; font-size: 9px;
  padding: 0 4px; border-radius: 8px; line-height: 16px;
}

.book-dialog-content p { margin-bottom: 8px; }

/* 多档选择弹窗 */
.picker-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 16px; border-radius: 6px; cursor: pointer;
  transition: background 0.15s; margin-bottom: 4px;
  border: 1px solid #ebeef5;
}
.picker-row:hover { background: #f5f7fa; }
.picker-row--disabled { opacity: 0.6; cursor: default; }
.picker-row--disabled:hover { background: transparent; }
.picker-time { font-size: 15px; font-weight: 500; }

/* ── 评价列表 ── */
.reviews-card { margin-top: 20px; }

/* ── 商家动态 ── */
.posts-card { margin-top: 20px; }
.section-title { font-size: 16px; font-weight: 600; }

.post-item { padding: 4px 0; }
.post-time { font-size: 12px; color: #c0c4cc; margin-bottom: 6px; }
.post-content { font-size: 14px; color: #606266; line-height: 1.6; white-space: pre-wrap; margin-bottom: 8px; }
.post-images { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.post-thumb { width: 80px; height: 80px; border-radius: 4px; object-fit: cover; cursor: pointer; }
.posts-more { text-align: center; }

.reviews-title { font-size: 16px; font-weight: 600; }
.reviews-summary { font-size: 13px; color: #909399; margin-left: 12px; }

.review-empty { padding: 20px 0; }

.review-item { padding: 4px 0; }

.review-meta {
  display: flex;
  align-items: center;
  margin-bottom: 6px;
}

.reviewer-name { font-weight: 500; font-size: 14px; color: #303133; }
.review-time { font-size: 12px; color: #c0c4cc; margin-left: auto; }

.review-content { font-size: 14px; color: #606266; line-height: 1.6; margin-bottom: 8px; }

/* 商家回复：带左边框的引用块风格 */
.merchant-reply {
  background: #f5f7fa;
  border-left: 3px solid #dcdfe6;
  padding: 8px 12px;
  border-radius: 0 4px 4px 0;
  font-size: 13px;
  color: #909399;
  margin-bottom: 8px;
}

.reply-label { font-weight: 500; color: #606266; }

.reply-input-row { margin-top: 8px; }
.reply-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; }
</style>
