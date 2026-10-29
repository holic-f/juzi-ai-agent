<script setup lang="ts">
import { CircleAlert, Heart, Zap } from 'lucide-vue-next'
defineProps<{
  kind: 'love' | 'manus'
  message: { role: 'assistant' | 'user'; content: string; time: string; pending?: boolean; error?: boolean }
}>()
</script>

<template>
  <article class="message-row" :class="`message-${message.role}`" :aria-label="message.role === 'user' ? '你的消息' : '助手回复'">
    <div v-if="message.role === 'assistant'" class="avatar assistant-avatar" aria-hidden="true"><component :is="kind === 'love' ? Heart : Zap" :size="18" /></div>
    <div class="message-content">
      <div class="message-meta"><span>{{ message.role === 'user' ? '你' : kind === 'love' ? '恋爱大师' : 'Manus 超级智能体' }}</span><time>{{ message.time }}</time></div>
      <div class="message-body" :class="{ 'message-error': message.error }">
        <div v-if="message.error" class="error-title"><CircleAlert :size="17" /> 本次回复异常</div>
        <template v-if="message.content">{{ message.content }}<span v-if="message.pending" class="typing-cursor" aria-hidden="true"></span></template>
        <span v-else-if="message.pending" class="thinking"><span class="thinking-dots" aria-hidden="true"><i></i><i></i><i></i></span>{{ kind === 'love' ? '正在思考…' : '正在处理任务…' }}</span>
        <span v-else class="empty-response">本次回复已结束，暂无内容。</span>
      </div>
    </div>
  </article>
</template>

<style scoped>
.message-row { display: flex; align-items: flex-start; gap: 12px; min-width: 0; }
.avatar { display: grid; place-items: center; flex: 0 0 34px; height: 34px; border-radius: 11px; color: var(--brand); background: var(--brand-soft); }
.message-content { min-width: 0; flex: 1; }
.message-meta { display: flex; align-items: center; gap: 12px; min-height: 28px; margin-bottom: 7px; font-size: 13px; font-weight: 500; }
.message-meta time { color: var(--subtle); font-size: 11px; font-weight: 400; }
.message-body { color: var(--ink); font-size: 15px; line-height: 1.85; white-space: pre-wrap; overflow-wrap: anywhere; }
.message-user { justify-content: flex-end; }
.message-user .message-content { flex: 0 1 auto; max-width: 85%; }
.message-user .message-meta { justify-content: flex-end; }
.message-user .message-body { padding: 12px 18px; background: #f1f3f6; border-radius: 16px 4px 16px 16px; }
.message-error { padding: 14px 16px; color: var(--error); border: 1px solid #f3d1ca; background: var(--error-soft); border-radius: 12px; }
.error-title { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; font-weight: 600; font-size: 14px; }
.empty-response, .thinking { color: var(--muted); font-size: 14px; }
.thinking { display: inline-flex; align-items: center; gap: 10px; }
.thinking-dots { display: inline-flex; gap: 4px; }
.thinking i { width: 5px; height: 5px; border-radius: 50%; background: var(--brand); animation: pulse 1.2s infinite; }
.thinking i:nth-child(2) { animation-delay: .15s; } .thinking i:nth-child(3) { animation-delay: .3s; }
.typing-cursor { display: inline-block; width: 2px; height: 16px; margin-left: 4px; vertical-align: -2px; background: var(--brand); animation: blink 1s steps(2, start) infinite; }
@keyframes pulse { 0%, 70%, 100% { opacity: .35; } 35% { opacity: 1; } }
@keyframes blink { to { visibility: hidden; } }
@media (max-width: 600px) { .message-row { gap: 10px; } .avatar { flex-basis: 30px; height: 30px; border-radius: 9px; } .message-body { font-size: 16px; } .message-user .message-content { max-width: 90%; } .message-user .message-body { padding: 11px 14px; } }
</style>
