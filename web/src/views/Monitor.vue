<template>
  <div class="monitor">
    <div class="monitor-controls">
      <el-select v-model="selectedCamera" placeholder="选择摄像头" style="width: 200px;">
        <el-option
          v-for="cam in cameras"
          :key="cam.id"
          :label="cam.cameraName"
          :value="cam.cameraId"
        />
      </el-select>
      <el-button type="primary" @click="startPreview">
        <el-icon><VideoPlay /></el-icon> 开始预览
      </el-button>
      <el-button @click="stopPreview">
        <el-icon><VideoPause /></el-icon> 停止
      </el-button>
      <el-button @click="takeSnapshot">
        <el-icon><Camera /></el-icon> 截图
      </el-button>
    </div>

    <div class="video-wrapper">
      <VideoPlayer
        v-if="streamUrl"
        :src="streamUrl"
        @error="onError"
        @loaded="onLoaded"
        class="live-player"
      />
      <div v-else class="video-placeholder">
        <el-icon :size="64" color="#909399"><VideoCamera /></el-icon>
        <p>选择摄像头并点击开始预览</p>
      </div>

      <!-- Status overlay -->
      <div v-if="streamUrl" class="status-overlay">
        <el-tag type="success" size="small">在线</el-tag>
      </div>
    </div>

    <!-- Snapshot canvas (hidden) -->
    <canvas ref="canvasRef" style="display: none;" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { listCameras } from '@/api/camera'
import VideoPlayer from '@/components/VideoPlayer.vue'
import { ElMessage } from 'element-plus'

const cameras = ref([])
const selectedCamera = ref('')
const streamUrl = ref('')
const canvasRef = ref(null)

onMounted(async () => {
  try {
    cameras.value = await listCameras()
    if (cameras.value.length > 0) {
      selectedCamera.value = cameras.value[0].cameraId
    }
  } catch (e) {
    ElMessage.error('加载摄像头列表失败')
  }
})

function startPreview() {
  if (!selectedCamera.value) {
    ElMessage.warning('请先选择摄像头')
    return
  }
  streamUrl.value = `/api/v1/streams/${selectedCamera.value}/live.flv`
}

function stopPreview() {
  streamUrl.value = ''
}

function onLoaded() {
  ElMessage.success('实时流加载成功')
}

function onError(err) {
  ElMessage.error('实时流加载失败: ' + err)
}

function takeSnapshot() {
  if (!canvasRef.value) return

  const video = document.querySelector('.video-element')
  if (!video) {
    ElMessage.warning('没有可截图的视频流')
    return
  }

  const canvas = canvasRef.value
  canvas.width = video.videoWidth
  canvas.height = video.videoHeight
  const ctx = canvas.getContext('2d')
  ctx.drawImage(video, 0, 0)

  canvas.toBlob(blob => {
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `snapshot_${Date.now()}.png`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('截图已保存')
  })
}
</script>

<style scoped>
.monitor {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.monitor-controls {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}

.video-wrapper {
  flex: 1;
  position: relative;
  background: #000;
  border-radius: 8px;
  overflow: hidden;
  min-height: 400px;
}

.live-player {
  width: 100%;
  height: 100%;
}

.video-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #909399;
}

.video-placeholder p {
  margin-top: 16px;
  font-size: 16px;
}

.status-overlay {
  position: absolute;
  top: 12px;
  left: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
