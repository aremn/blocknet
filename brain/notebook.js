// The bot's notebook (ADR-0017, after GregTech Arena's Huygens memory): places the player named and
// standing wishes, written only through jobs with fixed fields and limits, read back as a short card
// that every translation gets. The model never writes the file.
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

export const PLACES_MAX = 32, NOTES_MAX = 20, NAME_MAX = 40, NOTE_MAX = 200;
const DUPLICATE_AT = 0.6; // word overlap (Jaccard) at which a new note repeats an old one

export class Refused extends Error {}

/** A place name as the player says it: "руда", "сундук для руды", "дом". */
export const placeName = value => typeof value === 'string' && value.trim().length >= 1 && value.trim().length <= NAME_MAX
  && !/^p\d+$/u.test(value.trim()) && !/[{}[\]"\n]/u.test(value);

const key = name => name.trim().toLowerCase().replace(/\s+/gu, ' ');
const words = text => new Set(String(text).toLowerCase().match(/[\p{L}\p{N}_]{3,}/gu) ?? []);
const overlap = (a, b) => { const union = new Set([...a, ...b]).size; return union ? [...a].filter(w => b.has(w)).length / union : 0; };

/** "12, 64, -3" (the body's pointed_block) as [12, 64, -3]. */
export function position(block) {
  const n = String(block ?? '').match(/-?\d+/gu)?.map(Number);
  return n?.length === 3 ? n : null;
}

export class Notebook {
  constructor(file) {
    this.file = file;
    this.data = { version: 1, places: [], notes: [], counter: 0 };
    if (file && existsSync(file)) {
      try { Object.assign(this.data, JSON.parse(readFileSync(file, 'utf8'))); } catch { /* a broken file starts empty */ }
    }
  }
  save() {
    if (!this.file) return;
    mkdirSync(dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp`;
    writeFileSync(tmp, JSON.stringify(this.data, null, 1));
    renameSync(tmp, this.file);
  }
  place(name) { return this.data.places.find(p => key(p.name) === key(String(name ?? ''))) ?? null; }
  /** Names the block at `pos`; a name said again moves it, like a map waypoint. */
  name(name, pos, from) {
    if (!placeName(name)) throw new Refused(`a place name is 1-${NAME_MAX} characters of plain words`);
    if (!pos) throw new Refused('nothing pointed at: look at the block and say it again');
    const old = this.place(name);
    if (!old && this.data.places.length >= PLACES_MAX) throw new Refused(`${PLACES_MAX} places already: forget one first`);
    const entry = { name: name.trim(), pos, from, at: new Date().toISOString() };
    if (old) Object.assign(old, entry); else this.data.places.push(entry);
    this.save();
    return `${old ? 'moved' : 'named'} ${entry.name} at ${pos.join(' ')}`;
  }
  note(text, from) {
    text = String(text ?? '').replace(/\s+/gu, ' ').trim();
    if (!text) throw new Refused('an empty note');
    if (text.length > NOTE_MAX) throw new Refused(`a note is at most ${NOTE_MAX} characters: say it shorter`);
    const mine = words(text);
    const same = this.data.notes.find(n => overlap(mine, words(n.text)) >= DUPLICATE_AT);
    if (same) { Object.assign(same, { text, from, at: new Date().toISOString() }); this.save(); return `updated note ${same.id}`; }
    if (this.data.notes.length >= NOTES_MAX) throw new Refused(`${NOTES_MAX} notes already: forget one first`);
    const id = `N${++this.data.counter}`;
    this.data.notes.push({ id, text, from, at: new Date().toISOString() });
    this.save();
    return `noted ${id}`;
  }
  forget({ name, note }) {
    if (note) {
      const before = this.data.notes.length;
      this.data.notes = this.data.notes.filter(n => n.id.toLowerCase() !== String(note).toLowerCase());
      if (this.data.notes.length === before) throw new Refused(`no note ${note}`);
      this.save();
      return `forgot note ${note}`;
    }
    const place = this.place(name);
    if (!place) throw new Refused(`no place named ${name}`);
    this.data.places = this.data.places.filter(p => p !== place);
    this.save();
    return `forgot ${place.name}`;
  }
  /** The card every translation gets: built by script, empty when nothing is written. */
  card() {
    const places = this.data.places.map(p => `${p.name} (block ${p.pos.join(' ')})`).join('; ');
    const notes = this.data.notes.map(n => `${n.id} ${JSON.stringify(n.text)}`).join('; ');
    return [places && `Named places: ${places}`, notes && `Player's standing wishes: ${notes}`].filter(Boolean).join('\n').slice(0, 1500);
  }
}
