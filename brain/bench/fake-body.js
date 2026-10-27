import net from 'node:net';
import { createInterface } from 'node:readline';
import { readFile, writeFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';

export const tools = JSON.parse(await readFile(new URL('./tools.json', import.meta.url), 'utf8'));

function validate(schema, args) {
  for (const key of schema.required) if (!(key in args)) throw new Error(`Missing ${key}`);
  for (const [key, value] of Object.entries(args)) {
    const property = schema.properties[key];
    if (!property || (property.type === 'integer' ? !Number.isInteger(value) : typeof value !== property.type)
      || property.minimum != null && value < property.minimum || property.maximum != null && value > property.maximum
      || property.enum && !property.enum.includes(value)) throw new Error(`Invalid ${key}`);
  }
  if (schema.oneOf && schema.oneOf.filter(choice => choice.required.every(key => key in args)).length !== 1)
    throw new Error('Expected ref OR x, y, z');
}

/** A private ephemeral localhost bridge; never reads the live game's config or port. */
export class FakeBody {
  sockets = new Set();
  reset(scenario) {
    this.scenario = scenario;
    this.inventory = scenario.inventory;
    this.scene = scenario.scene;
    this.calls = [];
  }

  result(tool, args) {
    const response = this.scenario.responses.find(r => r.tool === tool
      && (!r.itemPattern || new RegExp(r.itemPattern, 'i').test(args.item ?? ''))
      && (!r.args || Object.entries(r.args).every(([key, value]) => args[key] === value)));
    if (response) {
      if (response.requires && !this.inventory.includes(response.requires)) return `failed: missing ${response.requires}`;
      this.inventory = response.inventory ?? this.inventory;
      this.scene = response.scene ?? this.scene;
      return response.text;
    }
    if (tool === 'scene' || tool === 'look') return this.scene;
    if (tool === 'inv') return this.inventory;
    if (tool === 'status') return this.scenario.status ?? 'health 20/20, food 20; task idle';
    if (tool === 'changes') return `body r1: health 20/20, food 20\ninventory r1: ${this.inventory}\ntask r1: idle`;
    if (tool === 'stop') return 'stopped';
    return `failed: no scripted result for ${tool}; use observed refs and available materials`;
  }

  async start(configFile) {
    this.token = randomUUID();
    this.server = net.createServer(socket => {
      this.sockets.add(socket);
      socket.setNoDelay(true);
      socket.on('error', () => {});
      socket.on('close', () => this.sockets.delete(socket));
      const lines = createInterface({ input: socket });
      lines.on('error', () => socket.destroy());
      let authenticated = false;
      lines.on('line', line => {
        let request;
        const send = value => socket.write(`${JSON.stringify({ jsonrpc: '2.0', ...value })}\n`);
        let call;
        try {
          request = JSON.parse(line);
          const { id, method, params = {} } = request;
          if (method === 'hello') {
            if (params.protocol !== 1 || params.token !== this.token) throw new Error('Invalid hello');
            authenticated = true;
            send({ id, result: { protocol: 1, bots: ['BenchBot'], tools: tools.map(({ task, ...tool }) => tool) } });
            return;
          }
          if (!authenticated || method !== 'tool.call' || params.bot !== 'BenchBot') throw new Error('Invalid bridge request');
          call = { tool: params.tool, args: params.args ?? {} };
          this.calls.push(call);
          const tool = tools.find(t => t.name === call.tool);
          if (!tool) throw new Error(`Unknown tool: ${call.tool}`);
          validate(tool.params, call.args);
          call.result = this.result(call.tool, call.args);
          if (tool.task) {
            const task_id = randomUUID();
            send({ id, result: { task_id } });
            send({ method: 'event', params: { type: 'task.finished', bot: 'BenchBot', task_id, result: call.result } });
          } else send({ id, result: { result: call.result } });
        } catch (error) {
          if (call) call.result = `failed: ${error.message}`;
          send({ id: request?.id ?? null, error: { code: -32602, message: error.message } });
        }
      });
    });
    await new Promise((resolve, reject) => { this.server.once('error', reject); this.server.listen(0, '127.0.0.1', resolve); });
    await writeFile(configFile, JSON.stringify({ port: this.server.address().port, token: this.token }));
  }

  async close() {
    for (const socket of this.sockets) socket.destroy();
    if (this.server?.listening) await new Promise(resolve => this.server.close(resolve));
  }
}
