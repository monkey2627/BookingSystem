<template>
  <div class="page-container">
    <el-card style="max-width: 640px; margin: 0 auto">
      <template #header>
        <span style="font-size: 16px; font-weight: 600">{{ hasMerchant ? '编辑店铺信息' : '开通我的店铺' }}</span>
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

        <el-divider content-position="left">档期设置</el-divider>

        <!-- 档期可见度 -->
        <el-form-item label="档期可见度">
          <el-radio-group v-model="form.scheduleVisibility">
            <el-radio :value="0">全公开</el-radio>
            <el-radio :value="1">仅忙闲</el-radio>
            <el-radio :value="2">完全私密</el-radio>
          </el-radio-group>
          <div class="field-tip">
            <span v-if="form.scheduleVisibility === 0">客人可查看你的完整档期和时间段</span>
            <span v-else-if="form.scheduleVisibility === 1">客人只能看到哪天有档期，看不到时间段和服务类型</span>
            <span v-else>客人看不到你的任何档期，适合仅接受私下联系的情况</span>
          </div>
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

      <!-- 店铺状态管理（仅已开通店铺时显示） -->
      <template v-if="hasMerchant">
        <el-divider />
        <div class="shop-status-row">
          <div>
            <div class="shop-status-label">店铺状态</div>
            <div class="shop-status-desc" v-if="shopActive">
              店铺营业中，客人可以在发现商家处搜索并预约你
            </div>
            <div class="shop-status-desc closed" v-else>
              店铺已关闭，客人无法搜索或预约你（主页和动态仍可查看）
            </div>
          </div>
          <el-button
            :type="shopActive ? 'danger' : 'success'"
            plain
            :loading="statusLoading"
            @click="handleToggleStatus">
            {{ shopActive ? '关闭店铺' : '重新开张' }}
          </el-button>
        </div>
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { merchantApi } from '@/api'
import { SERVICE_TYPE_MAP } from '@/types'
const router = useRouter()

const shopActive = ref(true)
const statusLoading = ref(false)

const CITIES = ['北京', '上海', '广州', '深圳', '成都', '杭州', '武汉', '南京', '西安', '重庆']

const formRef = ref<FormInstance>()
const pageLoading = ref(false)
const saving = ref(false)
const hasMerchant = ref(false)

const form = ref({
  serviceTypes: [] as number[],
  city: '',
  intro: '',
  alipayLink: '',
  xianyuLink: '',
  xiaohongshuLink: '',
  weiboLink: '',
  scheduleVisibility: 0 as 0 | 1 | 2
})

const rules: FormRules = {
  serviceTypes: [{ type: 'array', min: 1, message: '至少选择一项服务类型', trigger: 'change' }]
}

// 进入页面时加载已有信息（回显）
onMounted(async () => {
  pageLoading.value = true
  try {
    const data = await merchantApi.getMyInfo()
    hasMerchant.value = true
    form.value.serviceTypes = data.serviceTypes ?? []
    form.value.city = data.city ?? ''
    form.value.intro = data.intro ?? ''
    form.value.alipayLink = data.alipayLink ?? ''
    form.value.xianyuLink = data.xianyuLink ?? ''
    form.value.xiaohongshuLink = data.xiaohongshuLink ?? ''
    form.value.weiboLink = data.weiboLink ?? ''
    shopActive.value = data.status !== 0
    form.value.scheduleVisibility = (data.scheduleVisibility ?? 0) as 0 | 1 | 2
  } catch {
    hasMerchant.value = false
  } finally {
    pageLoading.value = false
  }
})

async function handleToggleStatus() {
  const closing = shopActive.value
  const msg = closing
    ? '确定要关闭店铺吗？关闭后客人将无法在发现商家处搜索到你，也无法新建预约。动态和主页仍可正常查看。'
    : '确定要重新开张吗？开张后客人可以重新搜索并预约你。'
  await ElMessageBox.confirm(msg, closing ? '关闭店铺' : '重新开张', {
    confirmButtonText: closing ? '确认关闭' : '确认开张',
    cancelButtonText: '取消',
    type: closing ? 'warning' : 'success'
  })
  statusLoading.value = true
  try {
    await merchantApi.setShopStatus(closing ? 0 : 1)
    shopActive.value = !closing
    ElMessage.success(closing ? '店铺已关闭' : '已重新开张')
  } catch (e: any) {
    if (e !== 'cancel') throw e
  } finally {
    statusLoading.value = false
  }
}

async function handleSave() {
  if (!await formRef.value?.validate().catch(() => false)) return
  saving.value = true
  try {
    await merchantApi.updateInfo(form.value)
    if (!hasMerchant.value) {
      hasMerchant.value = true
      await ElMessageBox.confirm(
        '店铺开通成功！现在可以去创建档期，接受用户预约了。',
        '🎉 欢迎成为商家',
        { confirmButtonText: '去管理档期', cancelButtonText: '留在这里', type: 'success' }
      )
      router.push('/schedule/manage')
    } else {
      ElMessage.success('保存成功')
    }
  } catch (e: any) {
    if (e !== 'cancel') throw e
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.shop-status-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 4px 0;
}
.shop-status-label { font-size: 14px; font-weight: 500; color: #303133; margin-bottom: 4px; }
.shop-status-desc { font-size: 13px; color: #909399; }
.shop-status-desc.closed { color: #f56c6c; }
.field-tip { font-size: 12px; color: #909399; margin-top: 4px; }
</style>
