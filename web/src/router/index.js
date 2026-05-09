import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('../views/Dashboard.vue')
  },
  {
    path: '/monitor',
    name: 'Monitor',
    component: () => import('../views/Monitor.vue')
  },
  {
    path: '/playback',
    name: 'Playback',
    component: () => import('../views/Playback.vue')
  },
  {
    path: '/cameras',
    name: 'Cameras',
    component: () => import('../views/CameraManage.vue')
  },
  {
    path: '/storage',
    name: 'Storage',
    component: () => import('../views/StorageManage.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
