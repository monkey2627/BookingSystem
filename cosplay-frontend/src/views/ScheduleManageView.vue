<template>
  <div v-if="merchantId === -1" class="no-merchant-tip">
    <el-empty description="您还没有开通店铺">
      <el-button type="primary" @click="$router.push('/merchant/profile')">去开通店铺</el-button>
    </el-empty>
  </div>
  <div class="page-container" v-else-if="merchantId !== null">
    <div class="page-header">
      <h2 class="page-title">档期管理</h2>
      <div class="header-actions">
        <span class="visibility-label">档期可见度：</span>
        <el-select v-model="scheduleVisibility" style="width:110px" @change="updateVisibility">
          <el-option :value="0" label="全公开" />
          <el-option :value="1" label="仅忙闲" />
          <el-option :value="2" label="完全私密" />
        </el-select>
        <el-button @click="openBatchDialog">批量创建</el-button>
        <el-button type="primary" @click="openCreateDialog">创建档期</el-button>
      </div>
    </div>

    <!-- 月历 -->
    <el-card class="calendar-card">
      <div class="calendar-header">
        <el-button :icon="ArrowLeft" circle @click="prevMonth" />
        <span class="month-label">{{ currentMonth }}</span>
        <el-button :icon="ArrowRight" circle @click="nextMonth" />
      </div>

      <div class="legend">
        <span class="legend-item"><span class="dot" style="background:#67c23a"></span> 空闲可预约</span>
        <span class="legend-item"><span class="dot" style="background:#e6a23c"></span> 已有预约</span>
        <span class="legend-item"><span class="dot" style="background:#409eff"></span> 抢档期</span>
        <span class="legend-item"><span class="dot" style="background:#909399"></span> 不可用</span>
      </div>

      <div class="week-header">
        <span v-for="d in ['日','一','二','三','四','五','六']" :key="d">{{ d }}</span>
      </div>

      <div class="calendar-grid" v-loading="loading">
        <div v-for="(day, idx) in calendarDays" :key="idx"
          :class="['day-cell', day ? getDayClass(day.dateStr) : 'empty']"
          @click="day && handleDayClick(day.dateStr)">
          <template v-if="day">
            <span class="day-num">{{ day.num }}</span>
            <template v-if="scheduleMap[day.dateStr]?.length">
              <span class="day-slot">
                {{ scheduleMap[day.dateStr].length > 1
                  ? scheduleMap[day.dateStr].length + ' 个档期'
                  : (scheduleMap[day.dateStr][0].timeSlot || '全天') }}
              </span>
              <span v-if="scheduleMap[day.dateStr].some(s => s.bookType === 1)" class="rush-badge">抢</span>
            </template>
          </template>
        </div>
      </div>
    </el-card>

    <!-- ── 当天档期列表弹窗 ── -->
    <el-dialog v-model="dayDetailVisible" :title="`${selectedDate} 的档期`" width="520px">
      <div v-if="selectedDaySchedules.length === 0" class="empty-day">
        <el-empty description="该日暂无档期" :image-size="60" />
      </div>
      <div v-for="s in selectedDaySchedules" :key="s.id" class="day-schedule-row">
        <div class="day-schedule-info">
          <span class="time-slot-text">{{ s.timeSlot || '全天' }}</span>
          <el-tag :type="s.bookType === 1 ? 'primary' : ''" size="small" style="margin-left:6px">
            {{ s.bookType === 1 ? '抢档期' : '直接预约' }}
          </el-tag>
          <el-tag :type="statusType(s.status)" size="small" style="margin-left:4px">
            {{ ['空闲','已预约','不可用'][s.status] }}
          </el-tag>
        </div>
        <div class="day-schedule-actions">
          <el-button v-if="s.bookType === 1" size="small" plain
            @click="openQueueDrawer(s); dayDetailVisible = false">
            查看排队
          </el-button>
          <el-button v-if="s.bookType === 1" size="small" plain
            @click="handleShare(s)">
            分享
          </el-button>
          <el-button v-if="s.status === 0" size="small" type="danger" plain
            :loading="!!deleteLoadingMap[s.id]" @click="handleDeleteFromDay(s.id)">
            删除
          </el-button>
        </div>
      </div>
      <template #footer>
        <el-button @click="dayDetailVisible = false">关闭</el-button>
        <el-button type="primary" @click="openCreateFromDay">+ 在该天添加档期</el-button>
      </template>
    </el-dialog>

    <!-- ── 单个创建弹窗 ── -->
    <el-dialog v-model="createDialogVisible"
      :title="selectedDate ? `创建档期 — ${selectedDate}` : '创建档期'"
      width="480px" @close="resetCreate">
      <el-form :model="createForm" :rules="createRules" ref="createFormRef" label-width="90px">
        <!-- 未从日历进入时显示日期选择 -->
        <el-form-item v-if="!selectedDate" label="日期" prop="date">
          <el-date-picker v-model="createForm.date" type="date"
            value-format="YYYY-MM-DD" placeholder="选择日期" style="width:100%" />
        </el-form-item>
        <el-form-item label="服务类型" prop="serviceType">
          <el-select v-model="createForm.serviceType" placeholder="请选择服务类型" style="width:100%">
            <el-option v-for="(name, val) in SERVICE_TYPE_MAP" :key="val" :label="name" :value="Number(val)" />
          </el-select>
        </el-form-item>
        <el-form-item label="时间段">
          <el-checkbox v-model="createForm.fullDay">全天（不指定时间段）</el-checkbox>
        </el-form-item>
        <el-form-item v-if="!createForm.fullDay" label=" " class="time-row">
          <el-time-picker v-model="createForm.startTime" format="HH:mm" value-format="HH:mm"
            placeholder="开始时间" style="width:130px" />
          <span class="time-sep">至</span>
          <el-time-picker v-model="createForm.endTime" format="HH:mm" value-format="HH:mm"
            placeholder="结束时间" style="width:130px" />
        </el-form-item>
        <el-form-item label="预约模式" prop="bookType">
          <el-radio-group v-model="createForm.bookType">
            <el-radio :value="0">直接预约</el-radio>
            <el-radio :value="1">抢档期</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="createForm.bookType === 1">
          <el-form-item label="开放时间" prop="rushOpenTime">
            <el-date-picker v-model="createForm.rushOpenTime" type="datetime"
              placeholder="抢档期开放时间" style="width:100%" />
          </el-form-item>
          <el-form-item label="最大排队" prop="maxQueueSize">
            <el-input-number v-model="createForm.maxQueueSize" :min="1" :max="100" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="createLoading" @click="handleCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- ── 批量创建弹窗 ── -->
    <el-dialog v-model="batchDialogVisible" title="批量创建档期" width="540px" @close="resetBatch">
      <el-form :model="batchForm" :rules="batchRules" ref="batchFormRef" label-width="90px">
        <el-form-item label="服务类型" prop="serviceType">
          <el-select v-model="batchForm.serviceType" placeholder="请选择服务类型" style="width:100%">
            <el-option v-for="(name, val) in SERVICE_TYPE_MAP" :key="val" :label="name" :value="Number(val)" />
          </el-select>
        </el-form-item>
        <el-form-item label="日期范围" prop="dateRange">
          <el-date-picker v-model="batchForm.dateRange" type="daterange"
            start-placeholder="开始日期" end-placeholder="结束日期" style="width:100%" />
        </el-form-item>
        <el-form-item label="限定星期">
          <el-checkbox-group v-model="batchForm.weekdays">
            <el-checkbox v-for="(w, i) in weekNames" :key="i+1" :value="i+1">{{ w }}</el-checkbox>
          </el-checkbox-group>
          <div class="form-tip">不选表示范围内每天都创建</div>
        </el-form-item>

        <!-- 时间段模式切换 -->
        <el-form-item label="时间段模式">
          <el-radio-group v-model="batchForm.batchMode">
            <el-radio value="manual">手动指定</el-radio>
            <el-radio value="interval">固定间隔自动生成</el-radio>
          </el-radio-group>
        </el-form-item>

        <!-- 手动多时间段 -->
        <el-form-item v-if="batchForm.batchMode === 'manual'" label="时间段" required>
          <div v-for="(slot, i) in batchForm.timeSlots" :key="i" class="batch-slot-row">
            <el-checkbox v-model="slot.fullDay">全天</el-checkbox>
            <template v-if="!slot.fullDay">
              <el-time-picker v-model="slot.startTime" format="HH:mm" value-format="HH:mm"
                placeholder="开始" style="width:110px" />
              <span class="time-sep">至</span>
              <el-time-picker v-model="slot.endTime" format="HH:mm" value-format="HH:mm"
                placeholder="结束" style="width:110px" />
            </template>
            <el-button v-if="batchForm.timeSlots.length > 1"
              text type="danger" :icon="Delete"
              @click="batchForm.timeSlots.splice(i, 1)" />
          </div>
          <el-button text type="primary"
            @click="batchForm.timeSlots.push({ fullDay: false, startTime: '', endTime: '' })">
            + 添加时间段
          </el-button>
          <div class="form-tip">每行对应每天的一个档期</div>
        </el-form-item>

        <!-- 固定间隔模式 -->
        <template v-if="batchForm.batchMode === 'interval'">
          <el-form-item label="每天开始" required>
            <el-time-picker v-model="batchForm.intervalStart" format="HH:mm" value-format="HH:mm"
              placeholder="如 02:00" />
          </el-form-item>
          <el-form-item label="每天结束" required>
            <el-time-picker v-model="batchForm.intervalEnd" format="HH:mm" value-format="HH:mm"
              placeholder="如 22:00" />
          </el-form-item>
          <el-form-item label="间隔（小时）" required>
            <el-input-number v-model="batchForm.intervalHours"
              :min="0.5" :max="24" :step="0.5" :precision="1" style="width:130px" />
            <span class="form-tip" style="margin-left:8px">每个档期时长 = 间隔时长</span>
          </el-form-item>
          <el-form-item label=" ">
            <div v-if="intervalPreview.length" class="interval-preview">
              <el-tag v-for="(s, i) in intervalPreview" :key="i" size="small" style="margin:2px">{{ s }}</el-tag>
              <div class="form-tip">共 {{ intervalPreview.length }} 个档期 / 天</div>
            </div>
            <div v-else class="form-tip">填写开始、结束和间隔后，此处显示预览</div>
          </el-form-item>
        </template>

        <el-form-item label="预约模式" prop="bookType">
          <el-radio-group v-model="batchForm.bookType">
            <el-radio :value="0">直接预约</el-radio>
            <el-radio :value="1">抢档期</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="batchForm.bookType === 1">
          <el-form-item label="开放时间" prop="rushOpenTime">
            <el-date-picker v-model="batchForm.rushOpenTime" type="datetime"
              placeholder="抢档期统一开放时间" style="width:100%" />
          </el-form-item>
          <el-form-item label="最大排队">
            <el-input-number v-model="batchForm.maxQueueSize" :min="1" :max="100" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="batchDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="batchLoading" @click="handleBatch">批量创建</el-button>
      </template>
    </el-dialog>

    <!-- ── 排队名单抽屉 ── -->
    <el-drawer v-model="queueDrawerVisible" :title="`排队名单 — ${queueSchedule?.date}`"
      size="400px" direction="rtl">
      <div v-loading="queueLoading">
        <el-empty v-if="queue.length === 0 && !queueLoading" description="暂无排队记录" />
        <div v-for="r in queue" :key="r.id" class="queue-item">
          <div class="queue-rank">#{{ r.rankNo }}</div>
          <div class="queue-info">
            <div class="queue-name">{{ r.userNickname }}</div>
            <div class="queue-time">{{ r.rushTime?.slice(0, 16) }}</div>
          </div>
          <el-select v-model="r.status" size="small" style="width:100px"
            @change="(val: number) => handleStatusChange(r.id, val)">
            <el-option v-for="(s, k) in RUSH_STATUS_MAP" :key="k" :label="s.label" :value="Number(k)" />
          </el-select>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { ArrowLeft, ArrowRight, Delete } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { scheduleApi, merchantApi } from '@/api'
import { RUSH_STATUS_MAP, SERVICE_TYPE_MAP, type ScheduleVO, type RushRecordVO } from '@/types'

interface TimeSlotEntry {
  fullDay: boolean
  startTime: string
  endTime: string
}

// ── 当前商家 ID & 可见度 ───────────────────────────────────
const merchantId = ref<number | null>(null)
const scheduleVisibility = ref(0)

onMounted(async () => {
  try {
    const myInfo = await merchantApi.getMyInfo()
    merchantId.value = myInfo.id
    scheduleVisibility.value = myInfo.scheduleVisibility ?? 0
  } catch {
    merchantId.value = -1
  }
  await fetchSchedules()
})

async function updateVisibility() {
  try {
    await merchantApi.updateInfo({ scheduleVisibility: scheduleVisibility.value })
    ElMessage.success('可见度已更新')
  } catch { /* 由拦截器处理 */ }
}

// ── 月历 ──────────────────────────────────────────────────
const now = new Date()
const currentMonth = ref(`${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`)
const loading = ref(false)
const schedules = ref<ScheduleVO[]>([])

// 每天可有多个档期
const scheduleMap = computed(() => {
  const map: Record<string, ScheduleVO[]> = {}
  schedules.value.forEach(s => {
    if (!map[s.date]) map[s.date] = []
    map[s.date].push(s)
  })
  return map
})

const calendarDays = computed(() => {
  const [year, month] = currentMonth.value.split('-').map(Number)
  const firstDay = new Date(year, month - 1, 1).getDay()
  const daysInMonth = new Date(year, month, 0).getDate()
  const days: (null | { num: number; dateStr: string })[] = []
  for (let i = 0; i < firstDay; i++) days.push(null)
  for (let d = 1; d <= daysInMonth; d++) {
    const dateStr = `${year}-${String(month).padStart(2, '0')}-${String(d).padStart(2, '0')}`
    days.push({ num: d, dateStr })
  }
  return days
})

function getDayClass(dateStr: string) {
  const list = scheduleMap.value[dateStr]
  if (!list || list.length === 0) return 'day-empty'
  if (list.some(s => s.status === 0 && s.bookType === 1)) return 'day-rush'
  if (list.some(s => s.status === 0)) return 'day-free'
  if (list.some(s => s.status === 1)) return 'day-booked'
  return 'day-unavailable'
}

function statusType(status: number) {
  return (['success', 'warning', 'info'] as const)[status]
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

watch(currentMonth, fetchSchedules)

async function fetchSchedules() {
  if (!merchantId.value || merchantId.value < 0) return
  loading.value = true
  try {
    schedules.value = await scheduleApi.listByMonth(merchantId.value, currentMonth.value)
  } finally {
    loading.value = false
  }
}

// ── 当天档期列表弹窗 ─────────────────────────────────────
const dayDetailVisible = ref(false)
const selectedDate = ref('')
const selectedDaySchedules = computed(() =>
  selectedDate.value ? (scheduleMap.value[selectedDate.value] ?? []) : []
)
const deleteLoadingMap = ref<Record<number, boolean>>({})

function handleDayClick(dateStr: string) {
  selectedDate.value = dateStr
  dayDetailVisible.value = true
}

function openCreateFromDay() {
  dayDetailVisible.value = false
  createDialogVisible.value = true
}

async function handleDeleteFromDay(id: number) {
  await ElMessageBox.confirm('确定删除该档期吗？', '提示', { type: 'warning' })
  deleteLoadingMap.value[id] = true
  try {
    await scheduleApi.deleteSchedule(id)
    ElMessage.success('已删除')
    await fetchSchedules()
  } finally {
    delete deleteLoadingMap.value[id]
  }
}

// ── 单个创建 ─────────────────────────────────────────────
const createDialogVisible = ref(false)
const createLoading = ref(false)
const createForm = ref({
  date: '',
  serviceType: null as number | null,
  fullDay: false,
  startTime: '',
  endTime: '',
  bookType: 0 as 0 | 1,
  rushOpenTime: null as any,
  maxQueueSize: 10
})

const createRules: FormRules = {
  date: [{ required: true, message: '请选择日期' }],
  serviceType: [{ required: true, message: '请选择服务类型' }],
  bookType: [{ required: true }]
}

function openCreateDialog() {
  selectedDate.value = ''
  createDialogVisible.value = true
}

function resetCreate() {
  createForm.value = { date: '', serviceType: null, fullDay: false, startTime: '', endTime: '', bookType: 0, rushOpenTime: null, maxQueueSize: 10 }
}

async function handleCreate() {
  const date = selectedDate.value || createForm.value.date
  if (!date) { ElMessage.warning('请选择日期'); return }
  if (!createForm.value.fullDay && (!createForm.value.startTime || !createForm.value.endTime)) {
    ElMessage.warning('请选择时间段，或勾选"全天"')
    return
  }
  if (!createForm.value.serviceType) { ElMessage.warning('请选择服务类型'); return }
  const timeSlot = createForm.value.fullDay ? null : `${createForm.value.startTime}-${createForm.value.endTime}`
  createLoading.value = true
  try {
    await scheduleApi.create({
      date,
      timeSlot,
      bookType: createForm.value.bookType,
      serviceType: createForm.value.serviceType,
      rushOpenTime: createForm.value.rushOpenTime
        ? new Date(createForm.value.rushOpenTime).toISOString().slice(0, 19)
        : undefined,
      maxQueueSize: createForm.value.maxQueueSize
    })
    ElMessage.success('创建成功')
    createDialogVisible.value = false
    await fetchSchedules()
    // 创建后打开当天列表
    selectedDate.value = date
    dayDetailVisible.value = true
  } finally {
    createLoading.value = false
  }
}

// ── 批量创建 ─────────────────────────────────────────────
const batchDialogVisible = ref(false)
const batchLoading = ref(false)
const batchFormRef = ref<FormInstance>()
const weekNames = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const batchForm = ref({
  serviceType: null as number | null,
  dateRange: null as any,
  weekdays: [] as number[],
  batchMode: 'manual' as 'manual' | 'interval',
  timeSlots: [{ fullDay: false, startTime: '', endTime: '' }] as TimeSlotEntry[],
  intervalStart: '',
  intervalEnd: '',
  intervalHours: 2,
  bookType: 0 as 0 | 1,
  rushOpenTime: null as any,
  maxQueueSize: 10
})
const batchRules: FormRules = {
  serviceType: [{ required: true, message: '请选择服务类型' }],
  dateRange: [{ required: true, message: '请选择日期范围' }],
  bookType: [{ required: true }]
}

// 固定间隔模式：根据开始/结束/间隔计算时间段预览
const intervalPreview = computed(() => {
  const { intervalStart, intervalEnd, intervalHours } = batchForm.value
  if (!intervalStart || !intervalEnd || !intervalHours) return []
  const [sh, sm] = intervalStart.split(':').map(Number)
  const [eh, em] = intervalEnd.split(':').map(Number)
  const startMins = sh * 60 + sm
  const endMins = eh * 60 + em
  const stepMins = Math.round(intervalHours * 60)
  if (stepMins <= 0 || startMins >= endMins) return []
  const slots: string[] = []
  for (let cur = startMins; cur + stepMins <= endMins; cur += stepMins) {
    const fmt = (m: number) => `${String(Math.floor(m / 60)).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`
    slots.push(`${fmt(cur)}-${fmt(cur + stepMins)}`)
  }
  return slots
})

function generateIntervalSlots(): TimeSlotEntry[] {
  return intervalPreview.value.map(s => {
    const [startTime, endTime] = s.split('-')
    return { fullDay: false, startTime, endTime }
  })
}

function openBatchDialog() { batchDialogVisible.value = true }
function resetBatch() {
  batchForm.value = {
    serviceType: null, dateRange: null, weekdays: [],
    batchMode: 'manual',
    timeSlots: [{ fullDay: false, startTime: '', endTime: '' }],
    intervalStart: '', intervalEnd: '', intervalHours: 2,
    bookType: 0, rushOpenTime: null, maxQueueSize: 10
  }
}

async function handleBatch() {
  if (!await batchFormRef.value?.validate().catch(() => false)) return

  const slots = batchForm.value.batchMode === 'interval'
    ? generateIntervalSlots()
    : batchForm.value.timeSlots

  if (batchForm.value.batchMode === 'interval') {
    if (slots.length === 0) {
      ElMessage.warning('间隔设置无法生成任何时间段，请检查开始/结束/间隔')
      return
    }
  } else {
    for (const slot of slots) {
      if (!slot.fullDay && (!slot.startTime || !slot.endTime)) {
        ElMessage.warning('请完善时间段，或勾选"全天"')
        return
      }
    }
  }

  batchLoading.value = true
  try {
    const [start, end] = batchForm.value.dateRange
    const rushOpenTime = batchForm.value.rushOpenTime
      ? new Date(batchForm.value.rushOpenTime).toISOString().slice(0, 19)
      : undefined

    for (const slot of slots) {
      const timeSlot = slot.fullDay ? null : `${slot.startTime}-${slot.endTime}`
      await scheduleApi.batchCreate({
        startDate: start.toISOString().slice(0, 10),
        endDate: end.toISOString().slice(0, 10),
        weekdays: batchForm.value.weekdays,
        timeSlot,
        bookType: batchForm.value.bookType,
        serviceType: batchForm.value.serviceType!,
        rushOpenTime,
        maxQueueSize: batchForm.value.maxQueueSize
      })
    }
    ElMessage.success(`批量创建完成，共 ${slots.length} 个时间段`)
    batchDialogVisible.value = false
    fetchSchedules()
  } finally {
    batchLoading.value = false
  }
}

// ── 排队名单 ─────────────────────────────────────────────
const queueDrawerVisible = ref(false)
const queueLoading = ref(false)
const queueSchedule = ref<ScheduleVO | null>(null)
const queue = ref<RushRecordVO[]>([])

async function openQueueDrawer(s: ScheduleVO) {
  queueSchedule.value = s
  queueDrawerVisible.value = true
  queueLoading.value = true
  try {
    queue.value = await scheduleApi.getQueue(s.id)
  } finally {
    queueLoading.value = false
  }
}

function handleShare(s: ScheduleVO) {
  if (!merchantId.value || merchantId.value < 0) return
  const url = `${window.location.origin}/merchant/${merchantId.value}?rushDate=${s.date}`
  navigator.clipboard.writeText(url).then(() => {
    ElMessage.success('链接已复制，快分享给想抢档的朋友吧！')
  }).catch(() => {
    ElMessage.info('请手动复制：' + url)
  })
}

async function handleStatusChange(rushId: number, status: number) {
  try {
    await scheduleApi.updateRushStatus(rushId, status)
    ElMessage.success('状态已更新')
  } catch { /* 错误由 axios 拦截器弹出 */ }
}
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; flex-wrap: wrap; gap: 8px; }
.page-title { font-size: 20px; font-weight: 600; margin: 0; }
.header-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.visibility-label { font-size: 13px; color: #606266; white-space: nowrap; }

.calendar-header { display: flex; align-items: center; justify-content: center; gap: 16px; margin-bottom: 12px; }
.month-label { font-size: 16px; font-weight: 600; min-width: 100px; text-align: center; }

.legend { display: flex; gap: 16px; flex-wrap: wrap; margin-bottom: 12px; font-size: 13px; color: #606266; }
.legend-item { display: flex; align-items: center; gap: 4px; }
.dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }

.week-header { display: grid; grid-template-columns: repeat(7, 1fr); text-align: center; font-size: 13px; color: #909399; margin-bottom: 4px; }

.calendar-grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 4px; }
.day-cell {
  min-height: 70px;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  border-radius: 8px; font-size: 13px; cursor: pointer; position: relative;
  border: 1px solid transparent; transition: all 0.15s;
}
.day-cell:hover { border-color: #409eff; }
.empty { cursor: default; pointer-events: none; }
.day-num { font-size: 14px; font-weight: 500; }
.day-slot { font-size: 11px; color: #909399; margin-top: 2px; }
.rush-badge {
  position: absolute; top: 4px; right: 4px;
  background: #409eff; color: #fff; font-size: 10px;
  padding: 1px 4px; border-radius: 4px;
}
.day-free { background: #f0f9eb; color: #67c23a; }
.day-rush { background: #ecf5ff; color: #409eff; }
.day-booked { background: #fdf6ec; color: #e6a23c; }
.day-unavailable { background: #f4f4f5; color: #909399; text-decoration: line-through; }
.day-empty { color: #c0c4cc; }

/* 当天档期列表 */
.empty-day { padding: 16px 0; }
.day-schedule-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10px 0; border-bottom: 1px solid #f0f0f0;
  gap: 8px;
}
.day-schedule-info { display: flex; align-items: center; flex: 1; }
.time-slot-text { font-weight: 500; font-size: 14px; }
.day-schedule-actions { display: flex; gap: 6px; }

/* 时间选择 */
.time-row :deep(.el-form-item__content) { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; }
.time-sep { color: #606266; margin: 0 4px; }

/* 批量时间段行 */
.batch-slot-row {
  display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap;
}

.form-tip { font-size: 12px; color: #909399; margin-top: 4px; }

.interval-preview { display: flex; flex-wrap: wrap; gap: 4px; align-items: center; }

.queue-item { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-bottom: 1px solid #f0f0f0; }
.queue-rank { font-size: 20px; font-weight: 700; color: #409eff; min-width: 36px; text-align: center; }
.queue-info { flex: 1; }
.queue-name { font-weight: 500; font-size: 14px; }
.queue-time { font-size: 12px; color: #909399; }
.no-merchant-tip { display: flex; justify-content: center; align-items: center; min-height: 60vh; }
</style>
