<script setup lang="ts">
import { Heart, Zap } from 'lucide-vue-next'
import AppHeader from '../components/AppHeader.vue'
import ModeCard from '../components/ModeCard.vue'

const modes = [
  {
    title: 'AI 恋爱大师',
    description: '聊聊关系中的困惑，找到更温柔、清晰的表达。',
    icon: Heart,
    route: '/love',
    status: '情感陪伴',
    tone: 'love' as const,
  },
  {
    title: 'AI 超级智能体',
    description: '把复杂任务交给智能体，探索、规划并逐步执行。',
    icon: Zap,
    route: '/manus',
    status: '任务执行',
    tone: 'manus' as const,
  },
]
</script>

<template>
  <main class="home-shell">
    <AppHeader>
      <template #actions>
        <span class="workspace-state">你的 AI 工作空间</span>
      </template>
    </AppHeader>

    <section class="home-content">
      <div class="home-heading">
        <p class="eyebrow">JUZI AI <span class="eyebrow-separator">/</span> 智能工作台</p>
        <h1>今天，想和 AI<br class="desktop-break" /> 一起完成什么？</h1>
        <p class="home-subtitle">选择一个模式，开始一场新的对话。</p>
      </div>

      <div class="mode-grid">
        <ModeCard
          v-for="(mode, index) in modes"
          :key="mode.route"
          :title="mode.title"
          :description="mode.description"
          :icon="mode.icon"
          :route="mode.route"
          :status="mode.status"
          :tone="mode.tone"
          :number="`0${index + 1}`"
        />
      </div>

      <footer class="home-footer"><span>JUZI AI</span><span>让想法，自然发生。</span></footer>
    </section>
  </main>
</template>

<style scoped>
.home-shell { min-height: 100vh; background: var(--page); color: var(--ink); }
.workspace-state { display: inline-flex; align-items: center; gap: 8px; color: var(--muted); font-size: 13px; white-space: nowrap; }
.home-content { width: min(100% - 48px, 1024px); margin: 0 auto; padding: clamp(68px, 10vh, 112px) 0 34px; }
.home-heading { max-width: 680px; }
.eyebrow { display: flex; align-items: center; gap: 10px; margin: 0 0 22px; color: var(--brand); font-size: 12px; font-weight: 700; letter-spacing: .11em; }
.eyebrow-separator { color: var(--line); font-weight: 400; }
h1 { margin: 0; font-size: clamp(34px, 5vw, 55px); font-weight: 700; letter-spacing: -.02em; line-height: 1.14; }
.home-subtitle { margin: 19px 0 0; color: var(--muted); font-size: 16px; line-height: 1.6; }
.mode-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; margin-top: 54px; }
.home-footer { display: flex; justify-content: space-between; gap: 20px; margin-top: 74px; padding-top: 17px; border-top: 1px solid var(--line); color: var(--muted); font-size: 12px; letter-spacing: .04em; }
.home-footer span:first-child { color: var(--brand); font-weight: 700; letter-spacing: .1em; }
@media (max-width: 600px) {
  .home-content { width: min(100% - 32px, 520px); padding-top: 52px; }
  .workspace-state { font-size: 12px; }
  .desktop-break { display: none; }
  h1 { font-size: 35px; }
  .home-subtitle { margin-top: 15px; font-size: 14px; }
  .mode-grid { grid-template-columns: 1fr; gap: 13px; margin-top: 37px; }
  .home-footer { margin-top: 46px; }
}
</style>
