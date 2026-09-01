<template>
  <div v-if="merchantId === -1" class="no-merchant-tip">
    <el-empty description="您还没有开通店铺">
      <el-button type="primary" @click="$router.push('/merchant/profile')">去开通店铺</el-button>
    </el-empty>
  </div>
  <div class="page-container" v-else-if="merchantId !== null">
    <div class="page-header">
      <h2 class="page-title">问卷管理</h2>
      <el-button type="primary" @click="openCreate">新建问卷</el-button>
    </div>

    <div v-loading="loading">
      <el-empty v-if="templates.length === 0 && !loading" description="还没有问卷模板" />

      <el-card v-for="t in templates" :key="t.id" class="tpl-card">
        <div class="tpl-header">
          <span class="tpl-title">{{ t.title }}</span>
          <el-tag :type="t.isRequired ? 'danger' : 'info'" size="small">
            {{ t.isRequired ? '预约必填' : '选填' }}
          </el-tag>
          <el-button type="danger" text size="small" :loading="deletingId === t.id"
            @click="handleDelete(t.id)" style="margin-left: auto">删除</el-button>
        </div>
        <el-descriptions :column="1" border size="small" style="margin-top: 12px">
          <el-descriptions-item v-for="q in t.questions" :key="q.id" :label="q.label">
            <el-tag size="small" type="success">{{ TYPE_LABEL[q.type] }}</el-tag>
            <span v-if="q.options?.length" style="margin-left: 8px; color: #909399; font-size: 12px">
              {{ q.options.join(' / ') }}
            </span>
            <el-tag v-if="q.required" size="small" type="warning" style="margin-left: 8px">必填</el-tag>
          </el-descriptions-item>
        </el-descriptions>
      </el-card>
    </div>

    <!-- ── 新建问卷弹窗 ── -->
    <el-dialog v-model="dialogVisible" title="新建问卷" width="560px" @close="resetForm">
      <el-form :model="form" ref="formRef" label-width="90px">
        <el-form-item label="问卷标题" prop="title"
          :rules="[{ required: true, message: '请填写标题' }]">
          <el-input v-model="form.title" placeholder="如：预约须知确认 / 风格喜好调查" />
        </el-form-item>

        <el-form-item label="预约必填">
          <el-switch v-model="form.isRequired" />
          <span style="margin-left: 8px; font-size: 12px; color: #909399">
            开启后客人预约时必须填写
          </span>
        </el-form-item>

        <el-divider>题目列表</el-divider>

        <div v-for="(q, idx) in form.questions" :key="q.id" class="q-item">
          <div class="q-row">
            <el-input v-model="q.label" placeholder="题目内容" style="flex:1" />
            <el-select v-model="q.type" style="width: 110px; margin: 0 8px" @change="onTypeChange(q)">
              <el-option label="填空题" value="text" />
              <el-option label="单选题" value="radio" />
              <el-option label="多选题" value="checkbox" />
            </el-select>
            <el-switch v-model="q.required" active-text="必填" inactive-text="" />
            <el-button text type="danger" :icon="Delete" @click="removeQuestion(idx)" />
          </div>
          <!-- radio/checkbox 选项 -->
          <div v-if="q.type !== 'text'" class="q-options">
            <el-tag
              v-for="(opt, oi) in q.options" :key="oi"
              closable @close="q.options!.splice(oi, 1)"
              style="margin: 4px">
              {{ opt }}
            </el-tag>
            <el-input
              v-if="optionInputIndex === idx"
              ref="optionInputRef"
              v-model="optionInputVal"
              size="small"
              style="width: 100px; margin: 4px"
              @keyup.enter="confirmOption(q)"
              @blur="confirmOption(q)" />
            <el-button v-else size="small" text @click="showOptionInput(idx)">
              + 添加选项
            </el-button>
          </div>
        </div>

        <el-button type="primary" plain :icon="Plus" @click="addQuestion" style="margin-top: 8px">
          添加题目
        </el-button>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, onMounted } from 'vue'
import { Delete, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance } from 'element-plus'
import { questionnaireApi, merchantApi } from '@/api'
import type { QuestionnaireVO, QuestionItem } from '@/types'

const TYPE_LABEL: Record<string, string> = { text: '填空题', radio: '单选题', checkbox: '多选题' }

const merchantId = ref<number | null>(null)
const loading = ref(false)
const templates = ref<QuestionnaireVO[]>([])

async function fetchTemplates() {
  loading.value = true
  try {
    // getByMerchant 需要 merchantId，这里调 getMyTemplates（后端 /api/questionnaire/my）
    templates.value = await questionnaireApi.getMyTemplates()
  } finally {
    loading.value = false
  }
}

// ── 删除 ─────────────────────────────────────────────────
const deletingId = ref<number | null>(null)
async function handleDelete(id: number) {
  await ElMessageBox.confirm('确定删除该问卷模板吗？', '提示', { type: 'warning' })
  deletingId.value = id
  try {
    await questionnaireApi.delete(id)
    templates.value = templates.value.filter(t => t.id !== id)
    ElMessage.success('已删除')
  } finally {
    deletingId.value = null
  }
}

// ── 新建 ─────────────────────────────────────────────────
const dialogVisible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()

const form = ref<{
  title: string
  isRequired: boolean
  questions: (QuestionItem & { options?: string[] })[]
}>({ title: '', isRequired: false, questions: [] })

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function resetForm() {
  form.value = { title: '', isRequired: false, questions: [] }
  optionInputIndex.value = -1
  optionInputVal.value = ''
}

function addQuestion() {
  form.value.questions.push({
    id: Date.now().toString(),
    label: '',
    type: 'text',
    required: false,
    options: []
  })
}

function removeQuestion(idx: number) {
  form.value.questions.splice(idx, 1)
}

function onTypeChange(q: QuestionItem & { options?: string[] }) {
  if (q.type !== 'text' && !q.options) q.options = []
}

// 动态添加选项
const optionInputIndex = ref(-1)
const optionInputVal = ref('')
const optionInputRef = ref<any>()

function showOptionInput(idx: number) {
  optionInputIndex.value = idx
  optionInputVal.value = ''
  nextTick(() => optionInputRef.value?.focus())
}

function confirmOption(q: QuestionItem & { options?: string[] }) {
  const val = optionInputVal.value.trim()
  if (val && !q.options?.includes(val)) {
    q.options = q.options ?? []
    q.options.push(val)
  }
  optionInputIndex.value = -1
  optionInputVal.value = ''
}

async function handleSave() {
  if (!await formRef.value?.validate().catch(() => false)) return
  if (form.value.questions.length === 0) {
    ElMessage.warning('请至少添加一道题目')
    return
  }
  saving.value = true
  try {
    await questionnaireApi.create({
      title: form.value.title,
      questions: form.value.questions,
      isRequired: form.value.isRequired
    })
    ElMessage.success('问卷已保存')
    dialogVisible.value = false
    fetchTemplates()
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  try {
    const info = await merchantApi.getMyInfo()
    merchantId.value = info.id
  } catch {
    merchantId.value = -1
    return
  }
  fetchTemplates()
})
</script>

<style scoped>
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.page-title  { font-size: 20px; font-weight: 600; margin: 0; }

.tpl-card { margin-bottom: 12px; }
.tpl-header { display: flex; align-items: center; gap: 8px; }
.tpl-title  { font-size: 15px; font-weight: 600; }

.q-item { background: #f9f9f9; border-radius: 6px; padding: 10px 12px; margin-bottom: 10px; }
.q-row  { display: flex; align-items: center; gap: 4px; }
.q-options { margin-top: 8px; padding-left: 4px; }
.no-merchant-tip { display: flex; justify-content: center; align-items: center; min-height: 60vh; }
</style>
