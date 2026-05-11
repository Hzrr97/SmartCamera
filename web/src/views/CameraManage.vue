<template>
  <div class="camera-manage">
    <div class="page-header">
      <h3>摄像头管理</h3>
      <el-button type="primary" @click="showAddDialog">
        <el-icon><Plus /></el-icon> 新增摄像头
      </el-button>
    </div>

    <el-table :data="cameras" v-loading="loading" style="width: 100%;">
      <el-table-column prop="cameraId" label="摄像头ID" width="150" />
      <el-table-column prop="cameraName" label="名称" width="150" />
      <el-table-column prop="resolution" label="分辨率" width="120" />
      <el-table-column label="帧率" width="80">
        <template #default="{ row }">{{ row.framerate }}fps</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ONLINE' ? 'success' : 'danger'" size="small">
            {{ row.status === 'ONLINE' ? '在线' : '离线' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" fixed="right" width="220">
        <template #default="{ row }">
          <el-button size="small" type="success" @click="btnStartStream(row)" v-if="row.status !== 'ONLINE'">
            启动
          </el-button>
          <el-button size="small" type="danger" @click="btnStopStream(row)" v-else>
            停止
          </el-button>
          <el-button size="small" @click="editCamera(row)">编辑</el-button>
          <el-popconfirm title="确认删除该摄像头？" @confirm="confirmDeleteCamera(row.id)">
            <template #reference>
              <el-button size="small" type="danger">删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <!-- Add/Edit Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑摄像头' : '新增摄像头'"
      width="500px"
    >
      <el-form :model="form" label-width="100px" label-position="right">
        <el-form-item label="摄像头ID">
          <el-input v-model="form.cameraId" :disabled="isEdit" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.cameraName" />
        </el-form-item>
        <el-form-item label="设备路径">
          <el-input v-model="form.devicePath" placeholder="Linux: /dev/video0, Windows: 设备名" />
        </el-form-item>
        <el-form-item label="分辨率">
          <el-select v-model="form.resolution" style="width: 100%;">
            <el-option label="1920x1080" value="1920x1080" />
            <el-option label="1280x720" value="1280x720" />
            <el-option label="640x480" value="640x480" />
          </el-select>
        </el-form-item>
        <el-form-item label="帧率">
          <el-input-number v-model="form.framerate" :min="1" :max="60" />
        </el-form-item>
        <el-form-item label="码率(kbps)">
          <el-input-number v-model="form.bitrateKbps" :min="500" :max="10000" :step="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveCamera">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { listCameras, createCamera, updateCamera, deleteCamera, startStream, stopStream } from '@/api/camera'
import { ElMessage } from 'element-plus'

const cameras = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref(null)

const form = ref({
  cameraId: '',
  cameraName: '',
  devicePath: '',
  resolution: '1920x1080',
  framerate: 25,
  bitrateKbps: 2000
})

onMounted(() => {
  fetchCameras()
})

async function fetchCameras() {
  loading.value = true
  try {
    cameras.value = await listCameras()
  } catch (e) {
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

function showAddDialog() {
  isEdit.value = false
  editId.value = null
  form.value = {
    cameraId: '',
    cameraName: '',
    devicePath: '',
    resolution: '1920x1080',
    framerate: 25,
    bitrateKbps: 2000
  }
  dialogVisible.value = true
}

function editCamera(row) {
  isEdit.value = true
  editId.value = row.id
  form.value = { ...row }
  dialogVisible.value = true
}

async function saveCamera() {
  try {
    if (isEdit.value) {
      await updateCamera(editId.value, form.value)
      ElMessage.success('更新成功')
    } else {
      await createCamera(form.value)
      ElMessage.success('创建成功')
    }
    dialogVisible.value = false
    fetchCameras()
  } catch (e) {
    ElMessage.error('保存失败: ' + e.message)
  }
}

async function confirmDeleteCamera(id) {
  try {
    await deleteCamera(id)
    ElMessage.success('删除成功')
    fetchCameras()
  } catch (e) {
    ElMessage.error('删除失败: ' + e.message)
  }
}

async function btnStartStream(row) {
  try {
    await startStream(row.id)
    ElMessage.success('推流已启动')
    fetchCameras()
  } catch (e) {
    ElMessage.error('启动失败: ' + e.message)
  }
}

async function btnStopStream(row) {
  try {
    await stopStream(row.id)
    ElMessage.success('推流已停止')
    fetchCameras()
  } catch (e) {
    ElMessage.error('停止失败: ' + e.message)
  }
}
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.page-header h3 {
  font-size: 18px;
  font-weight: 600;
}
</style>
