import { createRouter, createWebHistory } from 'vue-router'
import HomeView from './views/HomeView.vue'
import ChatView from './views/ChatView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    {
      path: '/love',
      name: 'love',
      component: ChatView,
      props: { kind: 'love' },
    },
    {
      path: '/manus',
      name: 'manus',
      component: ChatView,
      props: { kind: 'manus' },
    },
  ],
})

export default router
