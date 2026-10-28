import { readFile } from 'node:fs/promises';

export const persona = JSON.parse(await readFile(new URL('./persona.json', import.meta.url), 'utf8'));
export const personaPrompt = await readFile(new URL('./persona.md', import.meta.url), 'utf8');

/** Deliver the first model-written line, while the turn is still streaming. */
export class Thinking {
  constructor({ say, active = () => true, pool = persona } = {}) {
    Object.assign(this, { say, active, pool });
  }
  async run(work, { backend, player, isActive = () => true } = {}) {
    let buffer = '', first = '', delivered = false, speech;
    const previous = backend?.onText;
    const receive = (delta, complete = false) => {
      if (delivered) return;
      buffer += delta;
      if (!buffer.includes('\n') && !complete) return;
      delivered = true;
      first = buffer.split('\n')[0].trim();
      if (!first || /^[{`[]/u.test(first) || first === '<silent>' || first === 'needs_plan') { first = ''; return; }
      if (this.active() && isActive()) speech = Promise.resolve(this.say(first.replace(/\s+/gu, ' ').split(' ').slice(0, 12).join(' ').slice(0, 200), player)).catch(() => {});
    };
    if (backend) backend.onText = receive;
    try {
      const result = await work();
      // Fake/non-streaming transports still handle the same wire format at completion.
      if (!delivered && String(result.text).includes('\n')) receive(String(result.text), true);
      await speech;
      let text = String(result.text ?? '');
      if (first && text.trimStart().startsWith(first)) text = text.trimStart().slice(first.length).trimStart();
      return { ...result, text };
    } finally {
      if (backend) backend.onText = previous;
      await speech;
    }
  }
}
