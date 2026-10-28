import { readFile } from 'node:fs/promises';

export const persona = JSON.parse(await readFile(new URL('./persona.json', import.meta.url), 'utf8'));

/** One local, cancellable acknowledgement per slow call; shared cooldown across all roles. */
export class Thinking {
  constructor({ say, active = () => true, pool = persona, now = Date.now, random = Math.random } = {}) {
    Object.assign(this, { say, active, pool, now, random });
    this.recent = []; this.last = -Infinity;
  }
  phrase(text, languageText) {
    const language = /[а-яё]/iu.test(languageText || text) ? 'ru' : 'en';
    const situation = /стро|\bbuild|\bhouse|shelter|дом/iu.test(text) ? 'thinking'
      : /крафт|рецепт|сдела|кирк|\bcraft|\bmake|recipe/iu.test(text) ? 'crafting'
      : /най[дт]|ищ|поиск|\bfind|\bsearch|\blocat/iu.test(text) ? 'finding' : 'thinking';
    const phrases = this.pool[language]?.[situation] ?? persona[language][situation];
    const available = phrases.filter(p => !this.recent.includes(p));
    if (!available.length) return ''; // A user-edited tiny pool may need to stay quiet.
    const phrase = available[Math.floor(this.random() * available.length)];
    this.recent = [...this.recent, phrase].slice(-(this.pool.recentCount ?? 3));
    return phrase;
  }
  async run(work, { text = '', languageText = '', player, isActive = () => true } = {}) {
    let speech;
    const timer = setTimeout(() => {
      if (!this.active() || !isActive() || this.now() - this.last < Math.max(8000, this.pool.intervalMs ?? 8000)) return;
      const phrase = this.phrase(text, languageText);
      if (!phrase) return;
      this.last = this.now();
      speech = Promise.resolve().then(() => this.say(phrase, player)).catch(() => {});
    }, this.pool.delayMs ?? 700);
    try { return await work(); }
    finally { clearTimeout(timer); await speech; }
  }
}
