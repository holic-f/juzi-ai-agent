<script setup lang="ts">
import { Sparkles } from 'lucide-vue-next'
</script>

<template>
  <RouterLink class="brand" to="/" aria-label="橘子 AI 工作台首页">
    <span class="brand-mark"><Sparkles :size="20" :stroke-width="1.8" /></span>
    <span>橘子 <span class="brand-caption">AI 工作台</span></span>
  </RouterLink>
</template>

<style scoped>
.brand { display: inline-flex; align-items: center; gap: 10px; flex-shrink: 0; font-size: 16px; font-weight: 650; }
.brand-mark { display: grid; place-items: center; width: 36px; height: 36px; border-radius: 11px; background: var(--brand); color: white; }
.brand-caption { color: var(--muted); font-size: 14px; font-weight: 400; margin-left: 4px; }
@media (max-width: 600px) { .brand-caption { display: none; } .brand { font-size: 15px; gap: 8px; } .brand-mark { width: 32px; height: 32px; } }
</style>
