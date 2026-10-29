<script setup lang="ts">
import type { Component } from 'vue'
import { ArrowUpRight } from 'lucide-vue-next'

defineProps<{
  title: string
  description: string
  icon: Component
  route: string
  status: string
  tone: 'love' | 'manus'
  number: string
}>()
</script>

<template>
  <RouterLink class="mode-card" :class="`mode-card-${tone}`" :to="route">
    <div class="mode-card-top">
      <span class="mode-number">{{ number }}</span>
      <span class="mode-status">{{ status }}</span>
    </div>
    <div class="mode-icon"><component :is="icon" :size="26" :stroke-width="1.7" /></div>
    <div class="mode-card-copy">
      <h2>{{ title }}</h2>
      <p>{{ description }}</p>
    </div>
    <span class="mode-card-action" aria-label="打开模式"><ArrowUpRight :size="19" :stroke-width="1.8" /></span>
  </RouterLink>
</template>

<style scoped>
.mode-card {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 286px;
  padding: 24px;
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--surface);
  color: var(--ink);
  transition: border-color 160ms ease, background 160ms ease;
}
.mode-card:hover {
  border-color: var(--brand);
  background: #fffcf9;
}
.mode-card-top { position: relative; z-index: 1; display: flex; align-items: center; justify-content: space-between; }
.mode-number { color: var(--brand); font-size: 12px; font-weight: 700; letter-spacing: .08em; }
.mode-status { padding: 5px 9px; border: 1px solid var(--line); border-radius: 999px; color: var(--muted); font-size: 12px; line-height: 1; }
.mode-icon { position: relative; z-index: 1; display: grid; place-items: center; width: 54px; height: 54px; margin-top: 42px; border-radius: 15px; background: var(--brand-soft); color: var(--brand); }
.mode-card-copy { position: relative; z-index: 1; margin-top: 19px; padding-right: 44px; }
.mode-card h2 { margin: 0; font-size: 21px; font-weight: 680; letter-spacing: 0; line-height: 1.3; }
.mode-card p { max-width: 31em; margin: 9px 0 0; color: var(--muted); font-size: 14px; line-height: 1.7; }
.mode-card-action { position: absolute; z-index: 1; right: 22px; bottom: 21px; display: grid; place-items: center; width: 35px; height: 35px; border: 1px solid var(--line); border-radius: 50%; background: var(--surface); color: var(--brand); transition: background 180ms ease, color 180ms ease; }
.mode-card:hover .mode-card-action { background: var(--brand); color: white; }
.mode-card-manus .mode-icon { background: #f1eee7; color: #765b3f; }
@media (max-width: 600px) {
  .mode-card { min-height: 254px; padding: 20px; }
  .mode-icon { margin-top: 32px; }
  .mode-card h2 { font-size: 19px; }
}
</style>
