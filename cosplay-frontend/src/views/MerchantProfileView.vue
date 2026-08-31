<template>
  <div class="page-container">
    <el-card style="max-width: 640px; margin: 0 auto">
      <template #header>
        <span style="font-size: 16px; font-weight: 600">商家资料编辑</span>
      </template>

      <el-form :model="form" :rules="rules" ref="formRef" label-width="100px" v-loading="pageLoading">

        <!-- 服务类型：多选 -->
        <el-form-item label="服务类型" prop="serviceTypes">
          <el-checkbox-group v-model="form.serviceTypes">
            <el-checkbox v-for="(label, val) in SERVICE_TYPE_MAP" :key="val" :value="Number(val)">
              {{ label }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>

        <!-- 城市 -->
        <el-form-item label="所在城市" prop="city">
          <el-select v-model="form.city" placeholder="选择城市" clearable style="width: 200px">
            <el-option v-for="c in CITIES" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>

        <!-- 个人简介 -->
        <el-form-item label="个人简介" prop="intro">
          <el-input v-model="form.intro" type="textarea" :rows="4" placeholder="介绍一下你的服务风格、经验、擅长领域..." maxlength="300" show-word-limit />
        </el-form-item>

        <el-divider content-position="left">联系方式（选填）</el-divider>

        <el-form-item label="支付宝链接">
          <el-input v-model="form.alipayLink" placeholder="粘贴支付宝收款页链接" clearable />
        </el-form-item>

        <el-form-item label="闲鱼主页">
          <el-input v-model="form.xianyuLink" placeholder="粘贴闲鱼主页链接" clearable />
        </el-form-item>

        <el-form-item label="小红书主页">
          <el-input v-model="form.xiaohongshuLink" placeholder="粘贴小红书主页链接" clearable />
        </el-form-item>

        <el-form-item label="微博主页">
          <el-input v-model="form.weiboLink" placeholder="粘贴微博主页链接" clearable />
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
          <el-button @click="router.back()">取消</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { merchantApi } from '@/api'
import { SERVICE_TYPE_MAP } from '@/types'
const router = useRouter()

const CITIES = ['北京', '上海', '广州', '深圳', '成都', '杭州', '武汉', '南京', '西安', '重庆']

const formRef = ref<FormInstance>()
const pageLoading = ref(false)
const saving = ref(false)

const form = ref({
  serviceTypes: [] as number[],
  city: '',
  intro: '',
  alipayLink: '',
  xianyuLink: '',
  xiaohongshuLink: '',
  weiboLink: ''
})

const rules: FormRules = {
  serviceTypes: [{ type: 'array', min: 1, message: '至少选择一项服务类型', trigger: 'change' }]
}

// 进入页面时加载已有信息（回显）
onMounted(async () => {
  pageLoading.value = true
  try {
    const data = await merchantApi.getMyInfo()
    form.value.serviceTypes = data.serviceTypes ?? []
    form.value.city = data.city ?? ''
    form.value.intro = data.intro ?? ''
    form.value.alipayLink = data.alipayLink ?? ''
    form.value.xianyuLink = data.xianyuLink ?? ''
    form.value.xiaohongshuLink = data.xiaohongshuLink ?? ''
    form.value.weiboLink = data.weiboLink ?? ''
  } catch {
    // 新商家还没填过资料，接口可能返回 404，catch 住不报错，让用户从空表单开始填
  } finally {
    pageLoading.value = false
  }
})

async function handleSave() {
  if (!await formRef.value?.validate().catch(() => false)) return
  saving.value = true
  try {
    await merchantApi.updateInfo(form.value)
    ElMessage.success('保存成功')
  } finally {
    saving.value = false
  }
}
</script>
