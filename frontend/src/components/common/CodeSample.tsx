export type Protocol = 'api' | 'agui'
export type SampleLanguage = 'curl' | 'python' | 'javascript'

export function endpointOf(protocol: Protocol, agentId: string, domain: string): string {
  const base = domain.replace(/\/$/, '')
  return protocol === 'api'
    ? `${base}/v1/agents/${agentId}/chat`
    : `${base}/api/agui/v1/agents/${agentId}/run`
}

export function methodOf(protocol: Protocol, domain: string): string {
  return `POST ${endpointOf(protocol, '{agentId}', domain)}`
}

export function headerLinesOf(protocol: Protocol): { name: string; value: string }[] {
  const common = [
    { name: 'Authorization', value: 'Bearer <Agent API Key>' },
    { name: 'Content-Type', value: 'application/json' },
  ]
  return protocol === 'agui'
    ? [...common, { name: 'Accept', value: 'text/event-stream' }, { name: 'X-AGUI-Version', value: '1' }]
    : [...common, { name: 'Accept', value: 'application/json / text/event-stream' }]
}

export function buildCodeSample(protocol: Protocol, language: SampleLanguage, agentId: string, domain: string): string {
  const url = endpointOf(protocol, agentId, domain)
  if (protocol === 'api') {
    const body = JSON.stringify({ messages: [{ role: 'user', content: '请分析订单下降的原因' }], stream: false }, null, 2)
    if (language === 'curl') {
      return [
        `curl -X POST "${url}" \\`,
        '  -H "Authorization: Bearer $ALBEDO_AGENT_KEY" \\',
        '  -H "Content-Type: application/json" \\',
        `  -d '${body.replace(/\n/g, '\n     ')}'`,
        '',
        '# 流式返回：将 stream 设为 true，并把 Accept 改为 text/event-stream',
      ].join('\n')
    }
    if (language === 'python') {
      return [
        'import os, requests',
        '',
        `url = "${url}"`,
        'headers = {',
        '    "Authorization": f"Bearer {os.environ[\'ALBEDO_AGENT_KEY\']}",',
        '    "Content-Type": "application/json",',
        '}',
        'payload = {',
        '    "messages": [{"role": "user", "content": "请分析订单下降的原因"}],',
        '    "stream": False,',
        '}',
        '',
        'response = requests.post(url, headers=headers, json=payload, timeout=120)',
        'response.raise_for_status()',
        'print(response.json()["message"]["content"])',
        '# 流式返回：stream=True 且逐行解析 SSE 的 message.delta 事件',
      ].join('\n')
    }
    return [
      'const response = await fetch(' + `"${url}"` + ', {',
      '  method: "POST",',
      '  headers: {',
      '    Authorization: `Bearer ${process.env.ALBEDO_AGENT_KEY}`,',
      '    "Content-Type": "application/json",',
      '  },',
      '  body: JSON.stringify({',
      '    messages: [{ role: "user", content: "请分析订单下降的原因" }],',
      '    stream: false,',
      '  }),',
      '});',
      '',
      'const data = await response.json();',
      'console.log(data.message.content);',
      '// 流式返回：stream=true 时用 ReadableStream 逐块读取 SSE 事件',
    ].join('\n')
  }

  const body = JSON.stringify({
    threadId: 'thd_research_001',
    runId: 'run_research_008',
    messages: [{ id: 'msg_u_008', role: 'user', content: '研究 2026 年新能源汽车市场' }],
    context: { variables: { outputLanguage: 'zh-CN' } },
  }, null, 2)
  if (language === 'curl') {
    return [
      `curl -N -X POST "${url}" \\`,
      '  -H "Authorization: Bearer $ALBEDO_AGENT_KEY" \\',
      '  -H "Content-Type: application/json" \\',
      '  -H "Accept: text/event-stream" \\',
      '  -H "X-AGUI-Version: 1" \\',
      `  -d '${body.replace(/\n/g, '\n     ')}'`,
      '',
      '# 逐行读取 event:/data: 结构，按 sequence 排序并消费 RUN_* / TEXT_MESSAGE_* / TOOL_CALL_* 事件',
    ].join('\n')
  }
  if (language === 'python') {
    return [
      'import os, json, requests',
      '',
      `url = "${url}"`,
      'headers = {',
      '    "Authorization": f"Bearer {os.environ[\'ALBEDO_AGENT_KEY\']}",',
      '    "Content-Type": "application/json",',
      '    "Accept": "text/event-stream",',
      '    "X-AGUI-Version": "1",',
      '}',
      'payload = {',
      '    "threadId": "thd_research_001",',
      '    "runId": "run_research_008",',
      '    "messages": [{"id": "msg_u_008", "role": "user", "content": "研究 2026 年新能源汽车市场"}],',
      '}',
      '',
      'with requests.post(url, headers=headers, json=payload, stream=True) as response:',
      '    response.raise_for_status()',
      '    for line in response.iter_lines(decode_unicode=True):',
      '        if line and line.startswith("data: "):',
      '            event = json.loads(line[6:])',
      '            if event["type"] == "TEXT_MESSAGE_CONTENT":',
      '                print(event["delta"], end="", flush=True)',
    ].join('\n')
  }
  return [
    'const response = await fetch(' + `"${url}"` + ', {',
    '  method: "POST",',
    '  headers: {',
    '    Authorization: `Bearer ${process.env.ALBEDO_AGENT_KEY}`,',
    '    "Content-Type": "application/json",',
    '    Accept: "text/event-stream",',
    '    "X-AGUI-Version": "1",',
    '  },',
    '  body: JSON.stringify({',
    '    threadId: "thd_research_001",',
    '    runId: "run_research_008",',
    '    messages: [{ id: "msg_u_008", role: "user", content: "研究 2026 年新能源汽车市场" }],',
    '  }),',
    '});',
    '',
    'const reader = response.body.getReader();',
    'const decoder = new TextDecoder();',
    'let buffer = "";',
    'while (true) {',
    '  const { done, value } = await reader.read();',
    '  if (done) break;',
    '  buffer += decoder.decode(value, { stream: true });',
    '  const chunks = buffer.split("\\n\\n");',
    '  buffer = chunks.pop() ?? "";',
    '  for (const chunk of chunks) {',
    '    const data = chunk.split("\\n").find((line) => line.startsWith("data: "));',
    '    if (!data) continue;',
    '    const event = JSON.parse(data.slice(6));',
    '    if (event.type === "TEXT_MESSAGE_CONTENT") process.stdout.write(event.delta);',
    '  }',
    '}',
  ].join('\n')
}
