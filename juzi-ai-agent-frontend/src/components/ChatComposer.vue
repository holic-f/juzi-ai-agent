<script setup lang="ts">
import { nextTick, onMounted, ref, watch } from 'vue'
import { ArrowUp, Square } from 'lucide-vue-next'
const props = defineProps<{ modelValue: string; sending: boolean; kind: 'love' | 'manus' }>()
const emit = defineEmits<{ 'update:modelValue': [value: string]; send: []; stop: [] }>()
const textarea = ref<HTMLTextAreaElement | null>(null)
function resize() {
  const element = textarea.value
  if (!element) return
  element.style.height = 'auto'
  element.style.height = `${Math.min(element.scrollHeight, 180)}px`
}
function update(event: Event) {
  emit('update:modelValue', (event.target as HTMLTextAreaElement).value)
  resize()
}
function handleEnter(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229 || event.shiftKey) return
  event.preventDefault()
  if (!props.sending) emit('send')
}
function focus() { textarea.value?.focus() }
watch(() => props.modelValue, () => nextTick(resize))
onMounted(resize)
defineExpose({ focus })
</script>

<template>
  <footer class="composer-footer">
    <div class="composer">
      <label class="sr-only" for="chat-input">输入消息</label>
      <textarea id="chat-input" ref="textarea" :value="modelValue" rows="1" :placeholder="kind === 'love' ? '说说你的心事，或是想问的问题…' : '描述你的目标、背景，以及想要的结果…'" aria-describedby="composer-hint" @input="update" @keydown.enter="handleEnter"></textarea>
      <div class="composer-tools">
        <span id="composer-hint" class="composer-hint"><span class="desktop-hint">Enter 发送 · Shift + Enter 换行</span><span class="mobile-hint">写下想法，开始对话</span></span>
        <button v-if="sending" class="send-button stop-button" type="button" aria-label="停止生成" title="停止生成" @click="emit('stop')"><Square :size="16" fill="currentColor" /></button>
        <button v-else class="send-button" type="button" :disabled="!modelValue.trim()" aria-label="发送消息" title="发送消息" @click="emit('send')"><ArrowUp :size="21" /></button>
      </div>
    </div>
    <p class="composer-disclaimer">AI 生成的内容仅供参考，请结合实际情况判断</p>
  </footer>
</template>

<style scoped>
.composer-footer { flex-shrink: 0; padding: 14px 0 max(12px, env(safe-area-inset-bottom)); background: var(--surface); }
.composer { padding: 14px 14px 10px 18px; border: 1px solid #cdd2da; border-radius: 16px; background: var(--surface); box-shadow: 0 4px 16px #20242b05; transition: border-color .16s, box-shadow .16s; }
.composer:focus-within { border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-soft); }
textarea { display: block; width: 100%; min-height: 32px; max-height: min(180px, 25dvh); padding: 0; resize: none; border: 0; outline: none; color: var(--ink); background: transparent; font-size: 16px; line-height: 1.75; overflow-y: auto; }
textarea:focus-visible { outline: none; }
textarea::placeholder { color: var(--subtle); }
.composer-tools { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 8px; }
.composer-hint { font-size: 12px; color: var(--subtle); }
.mobile-hint { display: none; }
.send-button { width: 44px; height: 44px; display: grid; place-items: center; flex-shrink: 0; border: 0; border-radius: 12px; color: white; background: var(--brand); transition: background .16s; }
.send-button:hover:not(:disabled) { background: var(--brand-hover); }
.send-button:disabled { color: #8a929f; background: #edf0f3; }
.stop-button { color: var(--brand); background: var(--brand-soft); }
.stop-button:hover:not(:disabled) { color: white; }
.composer-disclaimer { margin: 10px 0 0; color: var(--subtle); font-size: 11px; line-height: 1.6; text-align: center; }
@media (max-width: 600px) { .composer-footer { padding-top: 10px; } .composer { padding: 12px 12px 8px 14px; border-radius: 14px; } .desktop-hint { display: none; } .mobile-hint { display: inline; } .composer-disclaimer { font-size: 10px; } }
@media (max-height: 500px) { .composer-disclaimer { display: none; } }
</style>
