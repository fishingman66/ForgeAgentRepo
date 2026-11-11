import { describe, expect, it } from 'vitest';

import { dispatchStreamEvent, type SseState, type StreamCallbacks } from '../sse-events';

const newState = (): SseState => ({ runId: '', cursor: 0, ok: false, failed: false });

/** 记录型回调桩:任意回调触发时登记事件名与参数 */
const stubCb = (log: Array<[string, unknown[]]>): StreamCallbacks => ({
  onMeta: (...a) => log.push(['meta', a]),
  onDelta: (...a) => log.push(['delta', a]),
  onDone: (...a) => log.push(['done', a]),
  onError: (...a) => log.push(['error', a]),
  onRunId: (...a) => log.push(['run_id', a]),
  onTeamAgentStart: (...a) => log.push(['team_agent_start', a]),
  onTeamAgentDelta: (...a) => log.push(['team_delta', a]),
  onTeamAgentDone: (...a) => log.push(['team_agent_done', a]),
  onUsage: (...a) => log.push(['usage', a]),
  onWfStart: (...a) => log.push(['wf_start', a]),
  onWfNodeStart: (...a) => log.push(['node_start', a]),
  onWfNodeDone: (...a) => log.push(['node_done', a]),
  onWfNodeError: (...a) => log.push(['node_error', a])
});

describe('dispatchStreamEvent', () => {
  it('run_id 更新状态游标并回调', () => {
    const state = newState();
    const log: Array<[string, unknown[]]> = [];
    dispatchStreamEvent(state, 'run_id', '{"runId":"r-123"}', 7, stubCb(log));
    expect(state.runId).toBe('r-123');
    expect(state.cursor).toBe(7);
    expect(log).toEqual([['run_id', ['r-123']]]);
  });

  it('delta 优先 text 其次 delta 字段', () => {
    const log: Array<[string, unknown[]]> = [];
    const cb = stubCb(log);
    dispatchStreamEvent(newState(), 'delta', '{"text":"甲"}', 1, cb);
    dispatchStreamEvent(newState(), 'delta', '{"delta":"乙"}', 1, cb);
    // 非 JSON 的 delta 帧被整体 try/catch 吞掉(与既有生产行为一致),不触发回调
    dispatchStreamEvent(newState(), 'delta', 'plain', 1, cb);
    expect(log.map(([, a]) => a[0])).toEqual(['甲', '乙']);
  });

  it('done/error 置终态标志', () => {
    const state = newState();
    dispatchStreamEvent(state, 'done', '{"sessionId":1}', 1, stubCb([]));
    expect(state.ok).toBe(true);
    const s2 = newState();
    dispatchStreamEvent(s2, 'error', '{"message":"boom"}', 2, stubCb([]));
    expect(s2.failed).toBe(true);
  });

  it('error 的 JSON 无 message 字段时回退 payload 原文', () => {
    const log: Array<[string, unknown[]]> = [];
    // '"raw"' 是合法 JSON(字符串),parse 成功但无 message → 回退 payload 原文
    dispatchStreamEvent(newState(), 'error', '"raw"', 1, stubCb(log));
    expect(log[0][1][0]).toBe('"raw"');
  });

  it('error payload 非 JSON 时也回退原文', () => {
    const log: Array<[string, unknown[]]> = [];
    dispatchStreamEvent(newState(), 'error', 'not-json', 1, stubCb(log));
    expect(log).toEqual([['error', ['not-json']]]);
  });

  it('工作流事件四分支完整透传(体检 E1)', () => {
    const log: Array<[string, unknown[]]> = [];
    const cb = stubCb(log);
    dispatchStreamEvent(newState(), 'wf_start', '{"wfRunId":9,"mode":"auto"}', 1, cb);
    dispatchStreamEvent(newState(), 'node_start', '{"nodeId":"n1","nodeName":"推理","nodeType":"llm"}', 2, cb);
    dispatchStreamEvent(newState(), 'node_done', '{"nodeId":"n1","nodeName":"推理","nodeType":"llm","status":"success"}', 3, cb);
    dispatchStreamEvent(newState(), 'node_error', '{"nodeId":"n2","nodeName":"检索","nodeType":"knowledge","error":"kb down"}', 4, cb);
    expect(log).toEqual([
      ['wf_start', [9, 'auto']],
      ['node_start', ['n1', '推理', 'llm']],
      ['node_done', ['n1', '推理', 'llm', 'success']],
      ['node_error', ['n2', '检索', 'knowledge', 'kb down']]
    ]);
  });

  it('工作流事件载荷缺字段时回退空串不抛异常', () => {
    const log: Array<[string, unknown[]]> = [];
    dispatchStreamEvent(newState(), 'node_start', '{}', 1, stubCb(log));
    expect(log).toEqual([['node_start', ['', '', '']]]);
  });

  it('team 接力三事件与 usage 正常分发', () => {
    const log: Array<[string, unknown[]]> = [];
    const cb = stubCb(log);
    dispatchStreamEvent(newState(), 'team_agent_start', '{"index":0,"total":2,"agentName":"甲","role":"member"}', 1, cb);
    dispatchStreamEvent(newState(), 'team_delta', '{"index":0,"text":"hi"}', 2, cb);
    dispatchStreamEvent(newState(), 'team_agent_done', '{"index":0,"content":"done","totalTokens":12}', 3, cb);
    dispatchStreamEvent(newState(), 'usage', '{"sessionId":5,"lastRoundTokens":12}', 4, cb);
    expect(log.length).toBe(4);
    expect(log[2]).toEqual(['team_agent_done', [0, 'done', 12]]);
  });

  it('未知事件静默忽略且游标仍前进', () => {
    const state = newState();
    const log: Array<[string, unknown[]]> = [];
    dispatchStreamEvent(state, 'future_event', '{}', 9, stubCb(log));
    expect(log).toEqual([]);
    expect(state.cursor).toBe(9);
  });

  it('空事件名按 message 处理,同样静默', () => {
    const state = newState();
    const log: Array<[string, unknown[]]> = [];
    dispatchStreamEvent(state, '', '{}', 1, stubCb(log));
    expect(log).toEqual([]);
  });
});
