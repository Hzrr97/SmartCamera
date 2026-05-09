<template>
  <div class="video-player-container">
    <video ref="videoRef" class="video-element" controls autoplay muted />
    <div v-if="error" class="video-error">{{ error }}</div>
    <div v-if="loading" class="video-loading">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>加载中...</span>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import flvjs from 'flv.js'

const props = defineProps({
  src: { type: String, required: true },
  autoplay: { type: Boolean, default: true }
})

const emit = defineEmits(['error', 'loaded'])

const videoRef = ref(null)
const error = ref('')
const loading = ref(true)
let player = null

onMounted(() => {
  initPlayer()
})

onUnmounted(() => {
  destroyPlayer()
})

watch(() => props.src, () => {
  destroyPlayer()
  initPlayer()
})

function initPlayer() {
  if (!flvjs.isSupported()) {
    error.value = '当前浏览器不支持 FLV 播放'
    loading.value = false
    emit('error', error.value)
    return
  }

  loading.value = true
  error.value = ''

  try {
    player = flvjs.createPlayer(
      { type: 'flv', url: props.src },
      {
        enableWorker: true,
        enableStashBuffer: false,
        stashInitialSize: 128,
        isLive: true
      }
    )

    player.attachMediaElement(videoRef.value)
    player.load()
    player.play().then(() => {
      loading.value = false
      emit('loaded')
    }).catch(e => {
      error.value = '播放失败: ' + e.message
      loading.value = false
      emit('error', e.message)
    })

    player.on(flvjs.Events.ERROR, (errType, errDetail) => {
      error.value = `播放错误: ${errType} - ${errDetail}`
      loading.value = false
      emit('error', error.value)
    })
  } catch (e) {
    error.value = '播放器初始化失败: ' + e.message
    loading.value = false
    emit('error', error.value)
  }
}

function destroyPlayer() {
  if (player) {
    player.pause()
    player.unload()
    player.detachMediaElement()
    player.destroy()
    player = null
  }
}
</script>

<style scoped>
.video-player-container {
  position: relative;
  width: 100%;
  background: #000;
  border-radius: 8px;
  overflow: hidden;
}

.video-element {
  width: 100%;
  height: 100%;
  display: block;
}

.video-error {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  color: #f56c6c;
  background: rgba(0, 0, 0, 0.8);
  padding: 12px 20px;
  border-radius: 4px;
  text-align: center;
}

.video-loading {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  color: #fff;
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
