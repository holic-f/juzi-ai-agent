<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ArrowLeft, Heart, Plus, Zap } from 'lucide-vue-next'
import AppHeader from '../components/AppHeader.vue'
import ChatWelcome from '../components/ChatWelcome.vue'
import ChatMessage from '../components/ChatMessage.vue'
import ChatComposer from '../components/ChatComposer.vue'

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api'

type Kind = 'love' | 'manus'
type ChatMessage = { id: number; role: 'assistant' | 'user'; content: string; time: string; pending?: boolean; error?: boolean }

const props = defineProps<{ kind: Kind }>()
const isLove = computed(() => props.kind === 'love')
const title = computed(() => isLove.value ? 'AI 恋爱大师' : 'Manus 超级智能体')
const status = computed(() => sending.value ? (connected.value ? '正在生成回复' : '正在等待响应') : messages.value.at(-1)?.error ? '本次回复异常，可重新发送' : '准备开始对话')
const Icon = computed(() => isLove.value ? Heart : Zap)
const chatId = ref(crypto.randomUUID())
const input = ref('')
const sending = ref(false)
const connected = ref(false)
const messages = ref<ChatMessage[]>([])
const scrollArea = ref<HTMLElement | null>(null)
const composer = ref<InstanceType<typeof ChatComposer> | null>(null)
const announcement = ref('')
watch(() => messages.value.length, (length) => {
  if (length === 0) announcement.value = ''
})
watch(sending, (active) => {
  if (active) announcement.value = ''
  else announcement.value = messages.value.at(-1)?.error ? '本次回复异常，请查看聊天记录中的错误提示。' : '本次回复已结束，可在聊天记录中阅读。'
})
let activeController: AbortController | null = null
let messageCounter = 0

function currentTime() {
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date())
}

function scrollToBottom() {
  nextTick(() => {
    if (scrollArea.value) scrollArea.value.scrollTop = scrollArea.value.scrollHeight
  })
}

function parseEventData(data: string): string {
  try {
    const parsed: unknown = JSON.parse(data)
    if (typeof parsed === 'string') return parsed
    if (parsed && typeof parsed === 'object') {
      const value = parsed as Record<string, unknown>
      for (const key of ['content', 'message', 'text', 'delta']) {
        if (typeof value[key] === 'string') return value[key] as string
      }
    }
  } catch {
    return data
  }
  return ''
}

function closeStream(controller: AbortController | null = activeController) {
  // 旧请求的收尾不能中止后来启动的请求。
  if (!controller || activeController !== controller) return
  activeController = null
  controller.abort()
  connected.value = false
  sending.value = false
}

function appendError(message: ChatMessage, text: string) {
  message.error = true
  message.content += `${message.content ? '\n\n' : ''}${text}`
}

function appendSseFrame(frame: string, message: ChatMessage): 'done' | 'error' | undefined {
  let event = 'message'
  const lines: string[] = []
  for (const line of frame.split(/\r?\n/)) {
    // SSE comment（连接确认、心跳）不产生聊天内容。
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) lines.push(line.slice(5).replace(/^ /, ''))
    else if (line === 'data') lines.push('')
  }
  const data = lines.join('\n')
  if (event === 'error') {
    appendError(message, parseEventData(data) || '任务执行失败，请重试。')
    return 'error'
  }
  // 只有明确的结束事件才能终止；普通正文中的 [DONE] 必须保留。
  if (event === 'done' && data === '[DONE]') return 'done'
  if (data) message.content += parseEventData(data)
  return undefined
}

async function sendMessage() {
  const text = input.value.trim()
  if (!text || sending.value) return

  input.value = ''
  messages.value.push({ id: ++messageCounter, role: 'user', content: text, time: currentTime() })
  const assistantMessage = reactive<ChatMessage>({
    id: ++messageCounter,
    role: 'assistant',
    content: '',
    time: currentTime(),
    pending: true,
  })
  messages.value.push(assistantMessage)
  sending.value = true
  scrollToBottom()

  const controller = new AbortController()
  const love = isLove.value
  activeController = controller
  let failureMessage = '无法建立服务连接，请检查网络或后端服务后重试。'
  try {
    const path = love ? '/ai/love_app/chat/sse' : '/ai/manus/chat'
    const params = new URLSearchParams({ message: text })
    if (love) params.set('chatId', chatId.value)
    const response = await fetch(`${apiBaseUrl}${path}?${params.toString()}`, {
      method: 'GET',
      headers: { Accept: 'text/event-stream' },
      signal: controller.signal,
    })
    if (controller.signal.aborted || activeController !== controller) return
    if (!response.ok) {
      const limitHints: Record<number, string> = {
        400: '输入无效或过长，请缩短问题后重试。',
        429: '当前任务较多或请求过于频繁，请等待已有任务结束后稍候重试。',
        504: '本次聊天已超过时间上限，请缩短问题或缩小任务范围后重试。',
      }
      failureMessage = limitHints[response.status]
        ? `${limitHints[response.status]}（HTTP ${response.status}）`
        : `服务请求失败（HTTP ${response.status}），请稍后重试。`
      throw new Error(failureMessage)
    }

    connected.value = true
    failureMessage = '回复读取中断，内容可能不完整，请重试。'
    if (!response.body) throw new Error('Missing response body')
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let terminal: 'done' | 'error' | undefined
    while (!terminal) {
      const { done, value } = await reader.read()
      if (controller.signal.aborted || activeController !== controller) return
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() || ''
      for (const frame of frames) {
        terminal = appendSseFrame(frame, assistantMessage)
        if (terminal) break
      }
      scrollToBottom()
    }
    if (!terminal && love) {
      buffer += decoder.decode()
      if (buffer.trim()) terminal = appendSseFrame(buffer, assistantMessage)
    }
    if (!love && !terminal) {
      failureMessage = '连接已结束，但未收到任务完成标记，回复可能不完整，请重试。'
      throw new Error(failureMessage)
    }
  } catch {
    // 主动停止、新建会话或切换模式均不显示错误。
    if (!controller.signal.aborted && activeController === controller) {
      appendError(assistantMessage, failureMessage)
    }
  } finally {
    assistantMessage.pending = false
    closeStream(controller)
    scrollToBottom()
  }
}

function stopGeneration() {
  closeStream()
  const pending = [...messages.value].reverse().find((message) => message.role === 'assistant' && message.pending)
  if (pending) pending.pending = false
}

function startNewChat() {
  stopGeneration()
  chatId.value = crypto.randomUUID()
  messages.value = []
}

function useSuggestion(text: string) {
  input.value = text
  nextTick(() => composer.value?.focus())
}

watch(() => props.kind, startNewChat)
onMounted(() => scrollToBottom())
onBeforeUnmount(() => closeStream())
</script>

<template>
  <div class="chat-shell">
    <AppHeader>
      <template #leading><RouterLink class="back-button" to="/" aria-label="返回应用首页"><ArrowLeft :size="20" /></RouterLink></template>
      <template #actions>
        <span class="mode-title"><component :is="Icon" :size="17" /><span>{{ title }}</span></span>
        <button class="new-chat" type="button" title="新建对话" aria-label="新建对话" @click="startNewChat"><Plus :size="18" /><span>新建对话</span></button>
      </template>
    </AppHeader>
    <main class="chat-main">
      <p class="sr-only" role="status">{{ announcement }}</p>
      <div class="conversation-head">
        <span class="conversation-label">{{ isLove ? '情感对话' : '任务空间' }}<span class="status" role="status"><span class="status-dot" :class="{ active: sending }"></span>{{ status }}</span></span>
        <span class="conversation-id">会话 {{ chatId.slice(0, 8).toUpperCase() }}</span>
      </div>
      <section ref="scrollArea" class="messages-area" aria-label="聊天记录" tabindex="0">
        <ChatWelcome v-if="messages.length === 0" :kind="kind" @suggest="useSuggestion" />
        <div v-else class="message-list">
          <ChatMessage v-for="message in messages" :key="message.id" :kind="kind" :message="message" />
        </div>
      </section>
      <ChatComposer ref="composer" v-model="input" :sending="sending" :kind="kind" @send="sendMessage" @stop="stopGeneration" />
    </main>
  </div>
</template>

<style scoped>
.chat-shell { display: flex; flex-direction: column; height: 100vh; height: 100dvh; overflow: hidden; background: var(--surface); }
.back-button { display: grid; place-items: center; width: 44px; height: 44px; border-radius: 10px; color: var(--muted); }
.back-button:hover { background: var(--page); color: var(--ink); }
.mode-title { display: inline-flex; align-items: center; gap: 8px; color: var(--muted); font-size: 14px; }
.mode-title svg { color: var(--brand); }
.new-chat { display: inline-flex; justify-content: center; align-items: center; gap: 7px; min-width: 44px; min-height: 44px; padding: 0 13px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface); font-size: 13px; transition: border-color .16s, background .16s; }
.new-chat:hover { background: var(--brand-soft); border-color: var(--brand); }
.chat-main { display: flex; flex: 1; flex-direction: column; min-height: 0; width: min(100% - 64px, 860px); margin: auto; }
.conversation-head { display: flex; flex-shrink: 0; align-items: center; justify-content: space-between; gap: 12px; min-height: 56px; border-bottom: 1px solid var(--line); }
.conversation-label { display: flex; align-items: center; gap: 16px; font-size: 13px; font-weight: 500; }
.status { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--muted); font-weight: 400; }
.status-dot { width: 6px; height: 6px; border-radius: 50%; background: #8993a0; }
.status-dot.active { background: var(--brand); }
.conversation-id { font-size: 11px; color: var(--subtle); font-variant-numeric: tabular-nums; }
.messages-area { flex: 1; min-height: 0; overflow-y: auto; overscroll-behavior-y: contain; scrollbar-width: thin; scrollbar-color: #d6dae0 transparent; padding: 0 8px; scroll-padding: 20px; }
.messages-area:focus-visible { outline-offset: -3px; }
.message-list { display: flex; flex-direction: column; gap: 32px; padding: 30px 0; }
@media (max-width: 768px) { .chat-main { width: calc(100% - 40px); } .new-chat span { display: none; } .new-chat { padding: 0; } }
@media (max-width: 600px) { .chat-main { width: calc(100% - 32px); } .mode-title { font-size: 12px; gap: 5px; } .mode-title svg { display: none; } .conversation-head { min-height: 46px; } .conversation-label { gap: 10px; font-size: 12px; } .status { font-size: 11px; } .conversation-id { display: none; } .messages-area { padding: 0 2px; } .message-list { gap: 28px; padding: 24px 0; } }
@media (max-width: 359px) { .back-button { width: 36px; } .mode-title { max-width: 78px; line-height: 1.5; } }
</style>
