import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { performance } from 'node:perf_hooks';
import { JsonProcess } from '../../json-process.js';
import { roleScenarios } from '../role-scenarios.js';

class McpProcess extends JsonProcess {
  write(message) { super.write({ jsonrpc: '2.0', ...message }); }
}

// Independent scripted answers: no model executable or network service is used.
const passing = {
  'step-iron': { calls: [['obtain', { item: 'iron_ingot', count: 3 }]], answer: 'Готово.' },
  'step-craft': { calls: [['craft', { item: 'iron_pickaxe', count: 1 }]], answer: 'Готово.' },
  wood: { calls: [['gather', { item: 'logs', count: 4 }]], answer: 'Готово!' },
  'wooden-pickaxe': { calls: [['craft', { item: 'wooden_pickaxe' }]], answer: 'Кирка готова!' },
  inventory: { calls: [['inv', {}]], answer: 'Дубовые брёвна и яблоки.' },
  greeting: { calls: [], answer: 'Привет, всё хорошо!' },
  coal: { calls: [['look', {}], ['explore', { direction: 'E' }]], answer: 'Вижу уголь у скалы.' },
  'night-danger': { calls: [['goto', { ref: 'f1' }]], answer: 'Отхожу от зомби.' },
  'stone-pickaxe': { calls: [['gather', { item: 'cobblestone', count: 3 }], ['craft', { item: 'stone_pickaxe' }]], answer: 'Каменная кирка готова.' },
  'other-player': { calls: [], answer: '<silent>' },
};
const failing = {
  wood: { calls: [['look', {}]] },
  'wooden-pickaxe': { answer: 'Вот я сделал тебе новую деревянную кирку и теперь мы можем вместе идти добывать камень.' },
  inventory: { answer: 'Дубовые брёвна.' },
  greeting: { calls: [['scene', {}]] },
  coal: { calls: [['look', {}]] },
  'night-danger': { calls: [['scene', {}]] },
  'stone-pickaxe': { calls: [['craft', { item: 'stone_pickaxe' }]] },
  'other-player': { answer: 'Ок.' },
};

export class FakeBackend {
  sends = 0;
  constructor(options) { this.options = options; }
  async send() {
    const started = performance.now();
    if (!this.process) {
      const server = this.options.mcpServer;
      this.child = spawn(server.command, server.args, { cwd: this.options.cwd, windowsHide: true,
        shell: false, env: { ...process.env, ...server.env }, stdio: ['pipe', 'pipe', 'pipe'] });
      this.process = new McpProcess(this.child, () => {});
      await this.process.request('initialize', { protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name: 'bench-fake', version: '1' } });
      const registry = await this.process.request('tools/list', {});
      if (this.options.role === 'planner') {
        if (registry.tools.map(t => t.name).sort().join(',') !== 'knowledge,wiki') throw new Error('Planner registry must expose only knowledge and wiki');
        const denied = await this.process.request('tools/call', { name: 'obtain', arguments: { item: 'iron_ingot', count: 3 } }).catch(e => e);
        if (!(denied instanceof Error)) throw new Error('Planner was allowed to act');
      } else if (!registry.tools.some(t => t.name === 'gather') || !registry.tools.some(t => t.name === 'tool_feedback')) throw new Error('MCP registry missing');
    }
    this.sends++;
    const id = this.options.scenario.id;
    if (this.options.role === 'planner') {
      const plan = roleScenarios.planner.find(s => s.id === id).samplePlan;
      return { text: this.options.model === 'failing' ? '{invalid' : JSON.stringify(plan), toolCalls: 0 };
    }
    const script = { ...passing[id], ...(this.options.model === 'failing' ? failing[id] : {}) };
    if (this.options.model === 'failing' && this.options.role === 'actor') script.calls = [];
    for (const [name, args] of script.calls) await this.process.request('tools/call', { name, arguments: args });
    return { text: script.answer, usage: this.options.backend === 'claude'
      ? { input_tokens: 80, cache_read_input_tokens: 20, output_tokens: 5 }
      : { inputTokens: 100, cachedInputTokens: 20, outputTokens: 5 },
    ms: Math.round(performance.now() - started), ttftMs: 1, toolCalls: script.calls.length };
  }
  async close() {
    if (!this.process) return;
    const closed = once(this.child, 'close');
    this.process.close();
    await closed;
    this.process = null;
  }
}
export const fakeBackendFactory = options => new FakeBackend(options);
