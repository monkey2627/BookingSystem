<template>
  <div class="page-container">
    <div class="feed-header">
      <h2 class="page-title">动态广场</h2>
      <el-button v-if="userStore.isLoggedIn" type="primary" @click="publishDialogVisible = true">
        发布动态
      </el-button>
    </div>

    <!-- 筛选 Tab -->
    <el-tabs v-model="feedTab" @tab-change="handleTabChange">
      <el-tab-pane label="全部动态" name="all" />
      <el-tab-pane v-if="userStore.isLoggedIn" label="我的关注" name="follows" />
    </el-tabs>

    <div class="post-list" v-loading="loading && posts.length === 0">
      <el-empty v-if="posts.length === 0 && !loading" description="暂无动态" />

      <el-card v-for="post in posts" :key="post.id" class="post-card">
        <!-- 商家信息头 -->
        <div class="post-header" @click="goMerchant(post.merchantId)">
          <el-avatar :size="44" :src="post.merchantAvatar" />
          <div class="post-meta">
            <div class="merchant-name">{{ post.merchantNickname }}</div>
            <div class="post-time">{{ formatTime(post.createTime) }}</div>
          </div>
        </div>

        <!-- 正文 -->
        <p class="post-content">{{ post.content }}</p>

        <!-- 图片九宫格 -->
        <div v-if="post.images?.length" class="image-grid" :class="`grid-${Math.min(post.images.length, 3)}`">
          <el-image v-for="(img, i) in post.images.slice(0, 9)" :key="i"
            :src="img" :preview-src-list="post.images" fit="cover" class="post-img" />
        </div>

        <!-- 底部操作 -->
        <div class="post-footer">
          <el-button text :type="post.liked ? 'primary' : ''" :icon="post.liked ? StarFilled : Star"
            @click="toggleLike(post)">
            {{ post.likeCount }}
          </el-button>
          <el-button text @click="goMerchant(post.merchantId)">查看主页</el-button>
          <!-- 已登录用户可尝试删除，后端校验是否是自己发布的 -->
          <el-button v-if="userStore.isLoggedIn" text type="danger" @click="handleDelete(post.id)">
            删除
          </el-button>
        </div>
      </el-card>
    </div>

    <!-- 加载更多 -->
    <div class="load-more" v-if="hasMore">
      <el-button :loading="loading" @click="loadMore">加载更多</el-button>
    </div>
    <div class="no-more" v-if="!hasMore && posts.length > 0">没有更多了</div>

    <!-- ── 发布动态弹窗 ── -->
    <el-dialog v-model="publishDialogVisible" title="发布动态" width="520px" @close="resetPublish">
      <el-form :model="publishForm" ref="publishFormRef" label-width="0">
        <el-form-item prop="content"
          :rules="[{ required: true, message: '请填写内容' }, { min: 5, message: '至少 5 个字' }]">
          <el-input v-model="publishForm.content" type="textarea" :rows="5"
            placeholder="分享你的工作日常、作品照片、接单须知…" maxlength="500" show-word-limit />
        </el-form-item>

        <!-- 图片上传 -->
        <el-upload
          v-model:file-list="uploadFileList"
          list-type="picture-card"
          :http-request="handleUpload"
          :on-exceed="() => ElMessage.warning('最多上传 9 张')"
          :limit="9"
          accept="image/jpeg,image/png,image/webp">
          <el-icon><Plus /></el-icon>
        </el-upload>
      </el-form>
      <template #footer>
        <el-button @click="publishDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="publishLoading" @click="handlePublish">发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, Star, StarFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, UploadUserFile } from 'element-plus'
import { postApi, followApi, uploadApi } from '@/api'
import { useUserStore } from '@/stores/user'
import type { PostVO } from '@/types'

const userStore = useUserStore()
const router = useRouter()

const feedTab = ref('all')
const posts = ref<PostVO[]>([])
const loading = ref(false)
const hasMore = ref(false)
const nextCursor = ref<number | null>(null)

function formatTime(str: string) {
  return str?.slice(0, 16).replace('T', ' ') ?? ''
}
function goMerchant(merchantId: number) {
  router.push(`/merchant/${merchantId}`)
}

async function fetchPosts(cursor: number | null = null) {
  loading.value = true
  try {
    const data = feedTab.value === 'follows'
      ? await postApi.followedFeed({ lastId: cursor, size: 12 })
      : await postApi.feed({ lastId: cursor, size: 12 })
    if (cursor === null) {
      posts.value = data.list
    } else {
      posts.value.push(...data.list)
    }
    hasMore.value = data.hasMore
    nextCursor.value = data.nextCursor
  } finally {
    loading.value = false
  }
}

function handleTabChange() {
  nextCursor.value = null
  fetchPosts(null)
}
function loadMore() { fetchPosts(nextCursor.value) }

async function toggleLike(post: PostVO) {
  if (!userStore.isLoggedIn) { ElMessage.warning('请先登录'); return }
  await postApi.toggleLike(post.id)
  if (post.liked) {
    post.liked = false
    post.likeCount--
  } else {
    post.liked = true
    post.likeCount++
  }
}

async function handleDelete(id: number) {
  await ElMessageBox.confirm('确定删除该动态吗？', '提示', { type: 'warning' })
  await postApi.delete(id)
  posts.value = posts.value.filter(p => p.id !== id)
  ElMessage.success('已删除')
}

// ── 发布 ──────────────────────────────────────────────────
const publishDialogVisible = ref(false)
const publishLoading = ref(false)
const publishFormRef = ref<FormInstance>()
const publishForm = ref({ content: '' })
const uploadFileList = ref<UploadUserFile[]>([])
const uploadedUrls = ref<string[]>([])

function resetPublish() {
  publishForm.value = { content: '' }
  uploadFileList.value = []
  uploadedUrls.value = []
}

async function handleUpload({ file }: { file: File }) {
  const url = await uploadApi.upload(file)
  uploadedUrls.value.push(url)
}

async function handlePublish() {
  if (!await publishFormRef.value?.validate().catch(() => false)) return
  publishLoading.value = true
  try {
    await postApi.create({ content: publishForm.value.content, images: uploadedUrls.value })
    ElMessage.success('发布成功！')
    publishDialogVisible.value = false
    fetchPosts(null)
  } finally {
    publishLoading.value = false
  }
}

onMounted(() => fetchPosts())
</script>

<style scoped>
.feed-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.page-title { font-size: 20px; font-weight: 600; margin: 0; }

.post-list { display: flex; flex-direction: column; gap: 12px; margin-top: 8px; }
.post-card { transition: box-shadow 0.2s; }
.post-card:hover { box-shadow: 0 2px 12px rgba(0,0,0,0.08); }

.post-header { display: flex; gap: 12px; align-items: center; margin-bottom: 12px; cursor: pointer; }
.post-header:hover .merchant-name { color: #409eff; }
.merchant-name { font-weight: 600; font-size: 15px; }
.post-time { font-size: 12px; color: #909399; margin-top: 2px; }

.post-content { font-size: 14px; color: #303133; line-height: 1.7; margin-bottom: 12px; white-space: pre-wrap; }

.image-grid { display: grid; gap: 4px; margin-bottom: 12px; }
.grid-1 { grid-template-columns: 1fr; max-width: 300px; }
.grid-2 { grid-template-columns: repeat(2, 1fr); }
.grid-3 { grid-template-columns: repeat(3, 1fr); }
.post-img { width: 100%; aspect-ratio: 1; object-fit: cover; border-radius: 4px; cursor: pointer; }

.post-footer { display: flex; gap: 8px; padding-top: 8px; border-top: 1px solid #f0f0f0; }

.load-more { text-align: center; margin-top: 24px; }
.no-more { text-align: center; color: #c0c4cc; font-size: 13px; margin-top: 24px; }
</style>
