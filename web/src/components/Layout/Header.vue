<template>
  <div class="header">
    <div class="header-left">
      <span class="page-title">{{ pageTitle }}</span>
    </div>
    <div class="header-right">
      <span class="current-time">{{ currentTime }}</span>
      <el-tag v-if="status.onlineCameras > 0" type="success" size="small">
        {{ status.onlineCameras }} 个在线
      </el-tag>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { getSystemStatus } from '@/api/system'

const route = useRoute()
const currentTime = ref('')
const status = ref({ onlineCameras: 0 })

const pageTitles = {
  '/dashboard': '仪表盘',
  '/monitor': '实时监控',
  '/playback': '历史回放',
  '/cameras': '摄像头管理',
  '/storage': '存储管理'
}

const pageTitle = computed(() => pageTitles[route.path] || '')

let timer = null

onMounted(() => {
  // Update time
  updateTime()
  timer = setInterval(updateTime, 1000)

  // Fetch status
  fetchStatus()
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

function updateTime() {
  const now = new Date()
  currentTime.value = now.toLocaleString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
    hour12: false
  })
}

async function fetchStatus() {
  try {
    status.value = await getSystemStatus()
  } catch (e) {
    // Ignore errors on initial load
  }
}
</script>

<style scoped>
.header {
  height: 60px;
  background: #ffffff;
  border-bottom: 1px solid #e4e7ed;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.current-time {
  color: #909399;
  font-size: 13px;
}
</style>
