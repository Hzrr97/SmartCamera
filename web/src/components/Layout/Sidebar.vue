<template>
  <div class="sidebar">
    <div class="sidebar-logo">
      <el-icon :size="24"><Monitor /></el-icon>
      <span class="logo-text">SmartCamera</span>
    </div>
    <el-menu
      :default-active="activeMenu"
      :default-openeds="['/']"
      router
      background-color="#ffffff"
      text-color="#606266"
      active-text-color="#409EFF"
      class="sidebar-menu"
    >
      <el-menu-item index="/dashboard">
        <el-icon><DataBoard /></el-icon>
        <span>仪表盘</span>
      </el-menu-item>
      <el-menu-item index="/monitor">
        <el-icon><VideoCamera /></el-icon>
        <span>实时监控</span>
      </el-menu-item>
      <el-menu-item index="/playback">
        <el-icon><Clock /></el-icon>
        <span>历史回放</span>
      </el-menu-item>
      <el-menu-item index="/cameras">
        <el-icon><Setting /></el-icon>
        <span>摄像头管理</span>
      </el-menu-item>
      <el-menu-item index="/storage">
        <el-icon><Files /></el-icon>
        <span>存储管理</span>
      </el-menu-item>
    </el-menu>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const activeMenu = computed(() => route.path)
</script>

<style scoped>
.sidebar {
  width: 220px;
  background: #ffffff;
  border-right: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
}

.sidebar-logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border-bottom: 1px solid #e4e7ed;
  color: #409EFF;
}

.logo-text {
  font-size: 18px;
  font-weight: 600;
}

.sidebar-menu {
  border-right: none;
  margin-top: 10px;
}
</style>
