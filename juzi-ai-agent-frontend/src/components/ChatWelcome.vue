<script setup lang="ts">
import { computed } from 'vue'
import { ArrowUpRight, Heart, Zap } from 'lucide-vue-next'
const props = defineProps<{ kind: 'love' | 'manus' }>()
const emit = defineEmits<{ suggest: [text: string] }>()
const isLove = computed(() => props.kind === 'love')
const suggestions = computed(() => isLove.value ? [
  { title: '最近有些心事想聊聊', description: '一起梳理关系里的小困惑', text: '我最近和喜欢的人聊天总是很紧张，怎么办？' },
  { title: '更自然地表达喜欢', description: '找到适合自己的表达方式', text: '怎样才能更自然地表达自己的喜欢？' },
] : [
  { title: '帮我规划一个任务', description: '理清目标，拆解接下来的行动', text: '帮我整理一下最近需要完成的事情' },
  { title: '开始一次主题调研', description: '从问题出发，探索一个新主题', text: '我想了解一个新主题，帮我开始调研' },
])
</script>

<template>
  <div class="welcome-block">
    <div class="welcome-icon"><component :is="isLove ? Heart : Zap" :size="26" :stroke-width="1.7" /></div>
    <p class="welcome-label">{{ isLove ? '情感陪伴 · 认真倾听' : 'Manus · 你的任务助手' }}</p>
    <h1>{{ isLove ? '关于感情，我们慢慢聊。' : '把想完成的事，交给我。' }}</h1>
    <p class="welcome-description">{{ isLove ? '无论是难以开口的心意，还是关系中的困惑，都可以从这里说起。' : '告诉我你的目标、背景和要求，一起梳理复杂问题，探索解决方法。' }}</p>
    <div class="suggestions">
      <button v-for="suggestion in suggestions" :key="suggestion.title" class="suggestion" type="button" @click="emit('suggest', suggestion.text)">
        <span><strong>{{ suggestion.title }}</strong><small>{{ suggestion.description }}</small></span><ArrowUpRight :size="17" />
      </button>
    </div>
  </div>
</template>

<style scoped>
.welcome-block { max-width: 660px; margin: auto; padding: clamp(32px, 8vh, 88px) 0 32px; text-align: center; }
.welcome-icon { display: grid; place-items: center; width: 60px; height: 60px; margin: 0 auto 20px; border-radius: 18px; color: var(--brand); background: var(--brand-soft); }
.welcome-label { margin: 0; font-size: 13px; font-weight: 500; color: var(--brand); }
h1 { margin: 14px 0; font-size: clamp(26px, 3.2vw, 34px); font-weight: 600; line-height: 1.5; letter-spacing: -.6px; }
.welcome-description { max-width: 500px; margin: auto; color: var(--muted); font-size: 14px; line-height: 1.9; }
.suggestions { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 32px; }
.suggestion { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 18px; border: 1px solid var(--line); border-radius: 12px; background: var(--surface); text-align: left; transition: border-color .16s, background .16s; }
.suggestion:hover { border-color: var(--brand); background: var(--brand-soft); }
.suggestion strong { display: block; font-size: 14px; font-weight: 500; line-height: 1.6; }
.suggestion small { display: block; margin-top: 5px; font-size: 12px; color: var(--muted); line-height: 1.6; }
.suggestion svg { color: var(--brand); }
@media (max-width: 600px) { .welcome-block { padding: 32px 0 24px; } .welcome-icon { width: 50px; height: 50px; margin-bottom: 16px; } h1 { font-size: 25px; } .suggestions { grid-template-columns: 1fr; margin-top: 24px; gap: 10px; } .suggestion { padding: 14px 16px; } }
@media (max-height: 600px) { .welcome-block { padding-top: 24px; } }
</style>
