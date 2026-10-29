import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'

// Execute the actual component script, not a copy of its SSE parser.
// This harness covers request/stream state only. Vue rendering, watcher scheduling,
// component lifecycle, browser networking and proxy timeouts need integration tests.
// The component currently uses single-line imports; adding multiline imports may
// require extending the import stripping below.
const component = readFileSync(new URL('../src/views/ChatView.vue', import.meta.url), 'utf8')
const script = component.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)?.[1]
assert.ok(script, 'ChatView.vue must contain its TypeScript script setup block')
const compiled = ts.transpileModule(
  script
    .replace(/^import\s.+$/gm, '')
    .replaceAll('import.meta.env', '__env') + `
      globalThis.__chatHarness = {
        sendMessage, stopGeneration, startNewChat,
        messages, input, sending, connected, chatId,
      }
    `,
  {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None },
    reportDiagnostics: true,
  },
)
assert.equal(
  compiled.diagnostics?.filter((diagnostic) => diagnostic.category === ts.DiagnosticCategory.Error).length,
  0,
  'The component script must transpile for the test harness',
)

function createChat(fetch, kind = 'manus') {
  const context = {
    __env: {},
    defineProps: () => ({ kind }),
    ref: (value) => ({ value }),
    computed: (get) => ({ get value() { return get() } }),
    reactive: (value) => value,
    watch: () => {},
    nextTick: (callback) => Promise.resolve().then(() => callback?.()),
    onMounted: () => {},
    onBeforeUnmount: () => {},
    crypto: { randomUUID },
    Heart: Symbol('Heart'),
    Zap: Symbol('Zap'),
    fetch,
    URLSearchParams,
    TextDecoder,
    AbortController,
    DOMException,
  }
  vm.runInNewContext(compiled.outputText, context, { filename: 'ChatView.script-setup.js' })
  return context.__chatHarness
}

const encoder = new TextEncoder()
const bytes = (text) => encoder.encode(text)
const doneFrame = 'event:done\ndata:[DONE]\n\n'
const deltaFrame = (delta) => `event:delta\ndata:${JSON.stringify({ delta })}\n\n`
const assistant = (chat) => chat.messages.value.at(-1)

function responseFromChunks(chunks, { status = 200, readError } = {}) {
  let index = 0
  return {
    ok: status >= 200 && status < 300,
    status,
    body: {
      getReader: () => ({
        async read() {
          if (index < chunks.length) return { done: false, value: chunks[index++] }
          if (readError) throw readError
          return { done: true, value: undefined }
        },
      }),
    },
  }
}

function deferred() {
  let resolve
  let reject
  const promise = new Promise((accept, fail) => {
    resolve = accept
    reject = fail
  })
  return { promise, resolve, reject }
}

// A pending read deliberately ignores abort until the test releases it. This
// models a late completion/rejection and makes request ownership races repeatable.
function controlledStream(prefix = []) {
  const entered = deferred()
  const pending = deferred()
  let index = 0
  return {
    entered: entered.promise,
    resolve: pending.resolve,
    reject: pending.reject,
    response: {
      ok: true,
      status: 200,
      body: {
        getReader: () => ({
          read() {
            if (index < prefix.length) {
              return Promise.resolve({ done: false, value: bytes(prefix[index++]) })
            }
            entered.resolve()
            return pending.promise
          },
        }),
      },
    },
  }
}

async function send(chat, text = '请整理任务结果') {
  chat.input.value = text
  await chat.sendMessage()
  return assistant(chat)
}

function assertFinished(chat) {
  assert.equal(chat.sending.value, false)
  assert.equal(chat.connected.value, false)
  assert.equal(assistant(chat).pending, false)
}

test('normal UTF-8 SSE survives byte-by-byte chunks and ends at done', async () => {
  const wire = bytes('data:你好，世界🌏\r\n\r\ndata:第二行\n\n' + doneFrame)
  let request
  const chat = createChat(async (url, options) => {
    request = { url, options }
    // Split multibyte code points and frame delimiters across reads.
    return responseFromChunks(Array.from(wire, (byte) => Uint8Array.of(byte)))
  })
  const message = await send(chat, '中文任务')
  assert.equal(message.content, '你好，世界🌏第二行')
  assert.equal(message.error, undefined)
  assert.equal(chat.input.value, '')
  assert.match(request.url, /^\/api\/ai\/manus\/chat\?/)
  assert.equal(new URL(request.url, 'http://test.local').searchParams.get('message'), '中文任务')
  assert.equal(request.options.method, 'GET')
  assert.equal(request.options.headers.Accept, 'text/event-stream')
  assertFinished(chat)
})

for (const [eventName, frame] of [
  ['implicit message', 'data:[DONE]\r\n\r\n'],
  ['explicit message', 'event:message\r\ndata:[DONE]\r\n\r\n'],
]) {
  for (const fragmented of [false, true]) {
    test(`${eventName} containing literal DONE stays pending until a done event (${fragmented ? 'fragmented' : 'whole'} frames)`, { timeout: 5000 }, async () => {
      const wire = 'data:第一段\n\n' + frame + 'data:后续内容\n\n'
      const stream = controlledStream(fragmented ? Array.from(wire) : [wire])
      const chat = createChat(async () => stream.response)
      chat.input.value = '请继续回复'
      const work = chat.sendMessage()
      try {
        // A premature finish must fail assertions instead of waiting forever for another read.
        await Promise.race([stream.entered, work])
        assert.equal(assistant(chat).content, '第一段[DONE]后续内容')
        assert.equal(assistant(chat).pending, true)
        assert.equal(chat.sending.value, true)
        assert.equal(assistant(chat).error, undefined)
      } finally {
        stream.resolve({ done: false, value: bytes(doneFrame) })
        await work
      }
      assert.equal(assistant(chat).content, '第一段[DONE]后续内容')
      assert.equal(assistant(chat).error, undefined)
      assertFinished(chat)
    })
  }

  test(`Manus EOF after ${eventName} containing literal DONE still reports missing completion`, async () => {
    const chat = createChat(async () => responseFromChunks([bytes('data:已有回复\n\n' + frame)]))
    const message = await send(chat)
    assert.equal(message.error, true)
    assert.ok(message.content.startsWith('已有回复[DONE]'))
    assert.match(message.content, /未收到任务完成标记/)
    assertFinished(chat)
  })

  test(`love chat preserves literal DONE in ${eventName} and still succeeds at EOF`, async () => {
    const chat = createChat(async () => responseFromChunks([
      bytes('data:恋爱建议\n\n' + frame + 'data:后续建议\n\n'),
    ]), 'love')
    const message = await send(chat)
    assert.equal(message.content, '恋爱建议[DONE]后续建议')
    assert.equal(message.error, undefined)
    assertFinished(chat)
  })
}

test('JSON delta events preserve exact text byte-by-byte and literal DONE never ends the stream', { timeout: 5000 }, async () => {
  const deltas = [
    '  前导空格',
    '\n\n下一行\n',
    '12345',
    '{"content":"看似 JSON 的正文"}',
    '[DONE]',
    '随后仍然继续🌏😀\n  结束',
  ]
  const wire = bytes(deltas.map(deltaFrame).join(''))
  const entered = deferred()
  const terminal = deferred()
  let index = 0
  const chat = createChat(async () => ({
    ok: true,
    status: 200,
    body: {
      getReader: () => ({
        read() {
          if (index < wire.length) {
            return Promise.resolve({ done: false, value: Uint8Array.of(wire[index++]) })
          }
          entered.resolve()
          return terminal.promise
        },
      }),
    },
  }))
  chat.input.value = '请流式回复'
  const work = chat.sendMessage()
  await entered.promise
  const expected = '  前导空格\n\n下一行\n12345{"content":"看似 JSON 的正文"}[DONE]随后仍然继续🌏😀\n  结束'
  assert.equal(assistant(chat).content, expected)
  assert.equal(assistant(chat).error, undefined)
  assert.equal(assistant(chat).pending, true, 'a delta containing [DONE] is ordinary text')
  assert.equal(chat.sending.value, true, 'only the terminal event may finish this reply')
  terminal.resolve({ done: false, value: bytes(doneFrame) })
  await work
  assert.equal(assistant(chat).content, expected)
  assert.equal(assistant(chat).error, undefined)
  assertFinished(chat)
})

test('an error after JSON delta events preserves every preceding delta exactly', async () => {
  const wire = bytes(
    ['  已有内容\n', '123', '{"delta":"正文中的 JSON"}', '[DONE]', '🌏\n  ']
      .map(deltaFrame).join('')
    + 'event:error\ndata:{"message":"生成中断，请重试。"}\n\n' + doneFrame,
  )
  const chat = createChat(async () => responseFromChunks(
    Array.from(wire, (byte) => Uint8Array.of(byte)),
  ))
  const message = await send(chat)
  assert.equal(message.content, '  已有内容\n123{"delta":"正文中的 JSON"}[DONE]🌏\n  \n\n生成中断，请重试。')
  assert.equal(message.error, true)
  assertFinished(chat)
})

test('connection comments and interleaved heartbeat comments never enter the reply', async () => {
  const chat = createChat(async () => responseFromChunks([
    bytes(':connected\n\n:heartbeat\n\n'),
    bytes('data:第一段\n\n:heartbeat\r\n\r\ndata:第二段\n\n'),
    bytes(':heartbeat\n\n' + doneFrame),
  ]))
  const message = await send(chat)
  assert.equal(message.content, '第一段第二段')
  assert.equal(message.error, undefined)
  assertFinished(chat)
})

test('an explicit error event preserves the reply already received', async () => {
  const chat = createChat(async () => responseFromChunks([
    bytes('data:已经完成的部分\n\n'),
    bytes('event:error\ndata:{"message":"工具执行失败，请重试。"}\n\n' + doneFrame),
  ]))
  const message = await send(chat)
  assert.equal(message.content, '已经完成的部分\n\n工具执行失败，请重试。')
  assert.equal(message.error, true)
  assertFinished(chat)
})

for (const [name, tail] of [
  ['missing done', ''],
  ['truncated done payload', 'event:done\ndata:[DO'],
  ['done without a terminating empty line', 'event:done\ndata:[DONE]'],
  ['invalid done payload in a complete frame', 'event:done\ndata:[DO]\n\n'],
]) {
  test(`Manus EOF with ${name} is not reported as success`, async () => {
    const chat = createChat(async () => responseFromChunks([
      bytes('data:已有回复\n\n'),
      bytes(tail),
    ]))
    const message = await send(chat)
    assert.equal(message.error, true)
    assert.ok(message.content.startsWith('已有回复'))
    assert.match(message.content, /未收到任务完成标记/)
    assertFinished(chat)
  })
}

test('model limit errors give actionable hints and release the sending state', async () => {
  for (const [status, hint] of [[400, /输入无效或过长/], [429, /当前任务较多或请求过于频繁/], [504, /超过时间上限/]]) {
    const chat = createChat(async () => responseFromChunks([], { status }))
    chat.input.value = 'test limit'
    await chat.sendMessage()
    assert.match(assistant(chat).content, hint)
    assert.match(assistant(chat).content, new RegExp(`HTTP ${status}`))
    assert.equal(assistant(chat).error, true)
    assert.equal(chat.sending.value, false)
  }
})

test('love chat timeout event preserves partial answer and displays the JSON message as an error', async () => {
  const timeout = '本次聊天已超过时间上限，请缩短问题后重试。'
  const chat = createChat(async () => responseFromChunks([
    bytes('data:已生成的部分\n\n'),
    bytes(`event:error\ndata:${JSON.stringify({ message: timeout })}\n\n`),
  ]), 'love')
  chat.input.value = 'test timeout'
  await chat.sendMessage()
  assert.equal(assistant(chat).content, `已生成的部分\n\n${timeout}`)
  assert.equal(assistant(chat).error, true)
  assert.equal(chat.sending.value, false)
})

test('HTTP errors report the response status', async () => {
  const chat = createChat(async () => responseFromChunks([], { status: 503 }))
  const message = await send(chat)
  assert.equal(message.error, true)
  assert.match(message.content, /HTTP 503/)
  assert.doesNotMatch(message.content, /无法建立服务连接|回复读取中断/)
  assertFinished(chat)
})

test('fetch failure is identified as failure to establish the connection', async () => {
  const chat = createChat(async () => { throw new TypeError('fetch failed') })
  const message = await send(chat)
  assert.equal(message.error, true)
  assert.match(message.content, /无法建立服务连接/)
  assert.doesNotMatch(message.content, /回复读取中断/)
  assertFinished(chat)
})

test('read failure preserves partial text and is distinct from fetch failure', async () => {
  const chat = createChat(async () => responseFromChunks([
    bytes('data:已经收到的内容\n\n'),
  ], { readError: new TypeError('connection reset during read') }))
  const message = await send(chat)
  assert.equal(message.error, true)
  assert.equal(message.content, '已经收到的内容\n\n回复读取中断，内容可能不完整，请重试。')
  assert.doesNotMatch(message.content, /无法建立服务连接/)
  assertFinished(chat)
})

test('an intentional stop does not turn a late read rejection into an error', { timeout: 5000 }, async () => {
  const stream = controlledStream(['data:停止前的内容\n\n'])
  let signal
  const chat = createChat(async (_url, options) => {
    signal = options.signal
    return stream.response
  })
  chat.input.value = '慢任务'
  const work = chat.sendMessage()
  await stream.entered
  assert.equal(chat.sending.value, true)
  chat.stopGeneration()
  assert.equal(signal.aborted, true)
  assert.equal(assistant(chat).pending, false)
  stream.reject(new DOMException('aborted by user', 'AbortError'))
  await work
  assert.equal(assistant(chat).content, '停止前的内容')
  assert.equal(assistant(chat).error, undefined)
  assertFinished(chat)
})

for (const lateResult of ['reject', 'resolve']) {
  test(`the old request finally cannot abort a new request after a late ${lateResult}`, { timeout: 5000 }, async () => {
    const oldStream = controlledStream()
    const newStream = controlledStream()
    const signals = []
    const chat = createChat(async (_url, options) => {
      signals.push(options.signal)
      return signals.length === 1 ? oldStream.response : newStream.response
    })
    chat.input.value = '旧任务'
    const oldWork = chat.sendMessage()
    await oldStream.entered
    const oldChatId = chat.chatId.value
    chat.startNewChat()
    assert.equal(signals[0].aborted, true)
    assert.notEqual(chat.chatId.value, oldChatId)
    assert.equal(chat.messages.value.length, 0)

    chat.input.value = '新任务'
    const newWork = chat.sendMessage()
    await newStream.entered
    if (lateResult === 'reject') oldStream.reject(new Error('old reader closed late'))
    else oldStream.resolve({ done: true, value: undefined })
    await oldWork
    assert.equal(signals[1].aborted, false, 'old finally must not abort the current controller')
    assert.equal(chat.sending.value, true)
    assert.equal(chat.connected.value, true)
    assert.equal(assistant(chat).pending, true)
    assert.equal(assistant(chat).error, undefined)
    assert.equal(chat.messages.value.length, 2)

    newStream.resolve({ done: false, value: bytes('data:新任务成功\n\n' + doneFrame) })
    await newWork
    assert.equal(assistant(chat).content, '新任务成功')
    assert.equal(assistant(chat).error, undefined)
    assertFinished(chat)
  })
}

test('love chat retains EOF compatibility without a Manus done marker', async () => {
  let url
  const chat = createChat(async (requestUrl) => {
    url = requestUrl
    return responseFromChunks([bytes('data:恋爱建议\n\n')])
  }, 'love')
  const message = await send(chat)
  assert.equal(message.content, '恋爱建议')
  assert.equal(message.error, undefined)
  assert.match(url, /^\/api\/ai\/love_app\/chat\/sse\?/)
  assert.equal(new URL(url, 'http://test.local').searchParams.get('chatId'), chat.chatId.value)
  assertFinished(chat)
})
