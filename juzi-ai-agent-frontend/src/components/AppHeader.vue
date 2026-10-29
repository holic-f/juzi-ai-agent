<script setup lang="ts">
import AppBrand from './AppBrand.vue'
</script>

<template>
  <header class="app-header">
    <div class="header-leading"><slot name="leading" /><AppBrand /></div>
    <div class="header-actions"><slot name="actions" /></div>
  </header>
</template>

<style scoped>
.app-header { height: var(--header-height); flex-shrink: 0; display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 0 clamp(24px, 5vw, 80px); background: var(--surface); border-bottom: 1px solid var(--line); }
.header-leading, .header-actions { display: flex; align-items: center; gap: 16px; min-width: 0; }
@media (max-width: 600px) { .app-header { padding: 0 16px; gap: 8px; } .header-leading, .header-actions { gap: 8px; } }
</style>
