// Voice: the bot hears players (speech to text on a local whisper.cpp server) and answers aloud
// (Piper text to speech). Both run locally and are optional: without them chat stays text-only.
// brain/setup-voice.ps1 downloads them to %LOCALAPPDATA%locknet (or -Root); the bridge config may
// override paths under "voice": { "home", "stt": {...}, "tts": {...} }.
import { spawn as spawnProcess } from 'node:child_process';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { readFile, rm, writeFile } from 'node:fs/promises';
import net from 'node:net';
import { homedir, tmpdir } from 'node:os';
import { basename, join } from 'node:path';

/** File in the voices folder naming the voice picked in game; survives restarts. */
const CHOSEN = 'chosen.txt';

/**
 * Where the voice pieces live: the config's "home", BLOCKNET_VOICE_HOME, ~/blocknet, then the per-user app
 * data folder. A packaged (MSIX) app's writes to AppData can land in its private copy that a game started
 * from a launcher never sees, so a folder outside AppData is checked first.
 */
export function voiceHomes(config = {}) {
  const appData = process.env.LOCALAPPDATA
    ?? (process.platform === 'win32' ? join(homedir(), 'AppData', 'Local') : join(homedir(), '.local', 'share'));
  return [config.home, process.env.BLOCKNET_VOICE_HOME, join(homedir(), 'blocknet'), join(appData, 'blocknet')].filter(Boolean);
}

/** Phrases Whisper invents for silence or noise (subtitle credits from its training data). */
const HALLUCINATIONS = [/субтитр/iu, /продолжение следует/iu, /редактор субтитров/iu, /спасибо за просмотр/iu,
  /подписывайтесь/iu, /^\s*[.…!?-]*\s*$/u, /amara\.org/iu, /dimatorzok/iu];

export function cleanTranscript(text) {
  const clean = String(text ?? '').replace(/\[[^\]]*\]|\([^)]*\)/gu, ' ').replace(/\s+/gu, ' ').trim();
  return HALLUCINATIONS.some(pattern => pattern.test(clean)) ? '' : clean;
}

function newest(dir, test) {
  if (!existsSync(dir)) return undefined;
  return readdirSync(dir).filter(test).sort().map(name => join(dir, name)).at(-1);
}

/** Paths from the config, else whatever setup-voice.ps1 installed. Missing pieces stay undefined. */
export function voiceConfig(config = {}) {
  const stt = config.stt ?? {}, tts = config.tts ?? {};
  const homes = voiceHomes(config);
  // An explicit home is used as given; otherwise the first one with anything installed.
  const home = config.home ?? homes.find(h => existsSync(join(h, 'whisper')) || existsSync(join(h, 'piper'))) ?? homes.at(-1);
  const whisper = join(home, 'whisper');
  const server = stt.server ?? [join(whisper, 'cuda', 'Release', 'whisper-server.exe'), join(whisper, 'cpu', 'Release', 'whisper-server.exe'),
    join(whisper, 'whisper-server')].find(existsSync);
  // Prefer the best model present: turbo over small over base.
  const models = join(whisper, 'models');
  const model = stt.model ?? ['large-v3-turbo', 'medium', 'small', 'base'].map(kind => newest(models, n => n.includes(kind) && n.endsWith('.bin'))).find(Boolean);
  const piper = tts.piper ?? [join(home, 'piper', 'piper', 'piper.exe'), join(home, 'piper', 'piper', 'piper')].find(existsSync);
  const voices = tts.voices ?? join(home, 'piper', 'voices');
  // A voice picked in game (/blocknet voice) wins over the newest one installed.
  const chosen = existsSync(join(voices, CHOSEN)) ? join(voices, readFileSync(join(voices, CHOSEN), 'utf8').trim()) : '';
  const voice = tts.voice ?? (chosen.endsWith('.onnx') && existsSync(chosen) ? chosen : newest(voices, n => n.endsWith('.onnx')));
  return {
    stt: server && model ? { server, model, language: stt.language ?? 'ru', gpu: stt.gpu ?? server.includes(`${join('whisper', 'cuda')}`),
      threads: stt.threads ?? 4 } : null,
    tts: piper && voice && tts.enabled !== false ? { piper, voice, voices, speaker: tts.speaker } : null,
    home,
  };
}

async function freePort() {
  return new Promise((resolvePort, reject) => {
    const probe = net.createServer().listen(0, '127.0.0.1', () => {
      const { port } = probe.address(); probe.close(() => resolvePort(port));
    }).on('error', reject);
  });
}

/** A local whisper.cpp HTTP server, started once and reused for every phrase. */
export class Hearing {
  constructor({ server, model, language = 'ru', gpu = true, threads = 4, prompt = '', spawn = spawnProcess, fetchImpl = fetch, log = () => {} }) {
    Object.assign(this, { server, model, language, gpu, threads, prompt, spawn, fetchImpl, log });
    this.queue = Promise.resolve();
  }
  async start() {
    if (this.ready) return this.ready;
    this.ready = (async () => {
      this.port = await freePort();
      const args = ['-m', this.model, '-l', this.language, '--host', '127.0.0.1', '--port', String(this.port),
        '-t', String(this.threads), '-nt', ...(this.gpu ? [] : ['-ng'])];
      this.process = this.spawn(this.server, args, { stdio: 'ignore', windowsHide: true });
      this.process.on?.('exit', code => { this.log('voice_hearing_exit', { code }); this.ready = null; });
      // Loading a large model takes a few seconds; the server answers / once it is up.
      for (let i = 0; i < 120; i++) {
        try { const response = await this.fetchImpl(`http://127.0.0.1:${this.port}/`); if (response.ok) return; } catch { /* not up yet */ }
        await new Promise(r => setTimeout(r, 500));
      }
      throw new Error('whisper server did not start within 60 s');
    })();
    return this.ready;
  }
  /** One phrase at a time: the server works on a single request anyway. */
  transcribe(wav) {
    const work = this.queue.then(async () => {
      await this.start();
      const form = new FormData();
      form.append('file', new Blob([wav], { type: 'audio/wav' }), 'phrase.wav');
      form.append('response_format', 'json');
      form.append('temperature', '0.0');
      if (this.prompt) form.append('prompt', this.prompt);
      const response = await this.fetchImpl(`http://127.0.0.1:${this.port}/inference`, { method: 'POST', body: form });
      if (!response.ok) throw new Error(`whisper server answered ${response.status}`);
      return cleanTranscript((await response.json()).text);
    });
    this.queue = work.catch(() => {});
    return work;
  }
  /** Loads the model onto the GPU before the first phrase (the first inference took ~1.8 s cold). */
  async warm() {
    const started = Date.now();
    await this.transcribe(silence(500));
    return Date.now() - started;
  }
  close() { this.process?.kill(); this.process = null; this.ready = null; }
}

/** A mono 16 kHz 16-bit WAV of silence; Whisper's answer to it is filtered out as noise. */
export function silence(ms) {
  const samples = Math.round(16 * ms), data = samples * 2, wav = Buffer.alloc(44 + data);
  wav.write('RIFF', 0); wav.writeUInt32LE(36 + data, 4); wav.write('WAVEfmt ', 8); wav.writeUInt32LE(16, 16);
  wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22); wav.writeUInt32LE(16000, 24); wav.writeUInt32LE(32000, 28);
  wav.writeUInt16LE(2, 32); wav.writeUInt16LE(16, 34); wav.write('data', 36); wav.writeUInt32LE(data, 40);
  return wav;
}

/** "ru_RU-dmitri-medium.onnx" -> "dmitri": the name a player says or types. */
export function voiceName(file) {
  const stem = basename(String(file ?? '')).replace(/\.onnx$/u, '');
  return stem.split('-')[1] ?? stem;
}

/**
 * The voice a request names: "next" cycles, a name or part of it matches; "" or "list" lists
 * (returns null). Undefined when nothing matches.
 */
export function pickVoice(voices, current, choice) {
  const wanted = String(choice ?? '').trim().toLowerCase();
  if (!wanted || wanted === 'list' || wanted === 'список') return null;
  if (wanted === 'next' || wanted === 'дальше' || wanted === 'следующий') {
    return voices[(voices.indexOf(current) + 1) % voices.length];
  }
  return voices.find(v => voiceName(v).toLowerCase() === wanted) ?? voices.find(v => basename(v).toLowerCase().includes(wanted));
}

/** Piper reads text on stdin and writes a WAV; one short process per reply keeps nothing in memory. */
export class Speech {
  constructor({ piper, voice, voices, speaker, spawn = spawnProcess }) { Object.assign(this, { piper, voice, voices, speaker, spawn }); this.count = 0; }
  /** Installed voices (full paths), in a stable order. */
  list() {
    if (!this.voices || !existsSync(this.voices)) return [this.voice];
    const found = readdirSync(this.voices).filter(n => n.endsWith('.onnx')).sort().map(n => join(this.voices, n));
    return found.length ? found : [this.voice];
  }
  /** Switches at once (the next reply uses it) and remembers it for the next start. */
  async use(voice) {
    // A configured speaker id belongs to the configured multi-speaker model only.
    if (voice !== this.voice) this.speaker = undefined;
    this.voice = voice;
    if (this.voices && existsSync(this.voices)) await writeFile(join(this.voices, CHOSEN), basename(voice));
  }
  synthesize(text) {
    const file = join(tmpdir(), `blocknet-say-${process.pid}-${this.count++}.wav`);
    return new Promise((resolveWav, reject) => {
      const args = ['--model', this.voice, '--output_file', file, ...(this.speaker != null ? ['--speaker', String(this.speaker)] : [])];
      const child = this.spawn(this.piper, args, { stdio: ['pipe', 'ignore', 'ignore'], windowsHide: true });
      child.on('error', reject);
      child.on('exit', async code => {
        try {
          if (code !== 0) throw new Error(`piper exited with ${code}`);
          resolveWav(await readFile(file));
        } catch (error) { reject(error); } finally { rm(file, { force: true }).catch(() => {}); }
      });
      child.stdin.end(`${speakable(text)}\n`);
    });
  }
}

/** Text a voice can read: no emoji, markup or refs like "h6"/"p2"; one line, bounded. */
export function speakable(text) {
  return String(text ?? '').replace(/[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}✔✅❌]/gu, '')
    .replace(/\b[a-z]\d+\b/gu, '').replace(/[`*_#>]/gu, '').replace(/\s+/gu, ' ').trim().slice(0, 400);
}
