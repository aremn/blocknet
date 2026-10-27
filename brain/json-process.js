import { createInterface } from 'node:readline';

/** JSON lines and failure propagation shared by the two stdio protocols. No spawning here. */
export class JsonProcess {
  constructor(child, onMessage, onFailure = () => {}) {
    this.child = child;
    this.pending = new Map();
    this.nextId = 0;
    this.stderr = '';
    this.lines = createInterface({ input: child.stdout });
    this.lines.on('line', line => {
      try {
        const message = JSON.parse(line);
        if (message.id !== undefined && !message.method && this.pending.has(message.id)) {
          const { resolve, reject } = this.pending.get(message.id);
          this.pending.delete(message.id);
          if (message.error) reject(new Error(message.error.message));
          else resolve(message.result);
        } else onMessage(message);
      } catch (error) { this.fail(error); }
    });
    this.onFailure = onFailure;
    child.stderr.on('data', data => { this.stderr = (this.stderr + data).slice(-4000); });
    child.on('error', error => this.fail(error));
    child.on('close', code => this.fail(new Error(`Model process exited ${code}: ${this.stderr}`)));
    child.stdin.on('error', error => this.fail(error));
  }

  write(message) {
    if (this.error) throw this.error;
    this.child.stdin.write(`${JSON.stringify(message)}\n`);
  }

  request(method, params) {
    return new Promise((resolve, reject) => {
      const id = ++this.nextId;
      this.pending.set(id, { resolve, reject });
      try { this.write({ id, method, params }); }
      catch (error) { this.pending.delete(id); reject(error); }
    });
  }

  fail(error) {
    if (this.error) return;
    this.error = error;
    for (const pending of this.pending.values()) pending.reject(error);
    this.pending.clear();
    this.onFailure(error);
  }

  close() {
    this.fail(new Error('Model session closed'));
    this.lines.close();
    this.child.kill();
  }
}

export const processOptions = cwd => ({ cwd, shell: false, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
