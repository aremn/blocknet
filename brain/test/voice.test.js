import { test } from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { PassThrough } from 'node:stream';
import { BrainSession } from '../brain.js';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Hearing, Speech, cleanTranscript, pickVoice, speakable, voiceConfig, voiceName } from '../voice.js';

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function until(predicate, timeout = 3000) {
  const start = Date.now();
  while (!predicate()) { if (Date.now() - start > timeout) throw new Error('condition timed out'); await sleep(5); }
}

test('whisper silence hallucinations and markup are dropped, real phrases kept', () => {
  assert.equal(cleanTranscript(' Продолжение следует... '), '');
  assert.equal(cleanTranscript('Субтитры сделал DimaTorzok'), '');
  assert.equal(cleanTranscript('[музыка]'), '');
  assert.equal(cleanTranscript(' Пидор, закинь  всё железо в сеть. '), 'Пидор, закинь всё железо в сеть.');
  assert.equal(speakable('✔ Готово, положил в h6 и p2 **всё**'), 'Готово, положил в и всё');
});

test('explicit voice config wins and missing pieces switch a half off', () => {
  const voice = voiceConfig({ stt: { server: 'srv.exe', model: 'm.bin', gpu: false }, tts: { piper: 'p.exe', voice: 'v.onnx' } });
  assert.deepEqual(voice.stt, { server: 'srv.exe', model: 'm.bin', language: 'ru', gpu: false, threads: 4 });
  assert.equal(voiceConfig({ home: 'X:/nowhere' }).home, 'X:/nowhere', 'an explicit home is searched first');
  assert.equal(voice.tts.voice, 'v.onnx');
  assert.equal(voiceConfig({ stt: { server: 'srv.exe', model: 'm.bin' }, tts: { piper: 'p.exe', voice: 'v.onnx', enabled: false } }).tts, null);
});

test('hearing starts the whisper server once and posts each phrase as a wav form', async () => {
  const spawned = [], posts = [];
  const spawn = (cmd, args) => { spawned.push({ cmd, args }); const child = new EventEmitter(); child.kill = () => {}; return child; };
  const fetchImpl = async (url, init) => {
    if (!init) return { ok: true };
    posts.push({ url, file: init.body.get('file'), language: init.body.get('prompt') });
    return { ok: true, json: async () => ({ text: ' привет бот ' }) };
  };
  const hearing = new Hearing({ server: 'whisper-server.exe', model: 'turbo.bin', gpu: false, prompt: 'Minecraft', spawn, fetchImpl });
  assert.equal(await hearing.transcribe(Buffer.from('RIFF')), 'привет бот');
  assert.equal(await hearing.transcribe(Buffer.from('RIFF')), 'привет бот');
  assert.equal(spawned.length, 1, 'one server for all phrases');
  assert.ok(spawned[0].args.includes('-ng') && spawned[0].args.includes('ru'), 'cpu flag and language passed');
  assert.equal(posts.length, 2);
  assert.match(posts[0].url, /\/inference$/u);
});

test('speech feeds piper the speakable text and returns its wav', async () => {
  let written = '';
  const spawn = (cmd, args) => {
    const child = new EventEmitter(); child.stdin = new PassThrough();
    child.stdin.on('data', chunk => { written += chunk; });
    child.stdin.on('finish', async () => {
      const { writeFile } = await import('node:fs/promises');
      await writeFile(args[args.indexOf('--output_file') + 1], Buffer.from('RIFFwav'));
      child.emit('exit', 0);
    });
    return child;
  };
  const wav = await new Speech({ piper: 'piper.exe', voice: 'v.onnx', spawn }).synthesize('Готово ✔ в h6');
  assert.equal(wav.toString(), 'RIFFwav');
  assert.equal(written.trim(), 'Готово в');
});

test('a phrase heard near the bot becomes chat, is echoed back, and the reply is spoken', async () => {
  class Body extends EventEmitter {
    hello = { bots: ['Kirill'], tools: [] }; calls = [];
    async ready() { return this.hello; }
    async call(bot, tool, args) { this.calls.push({ tool, args }); return { result: tool === 'changes' ? 'body r1: health 20/20' : 'ok' }; }
    close() {}
  }
  const body = new Body(), prompts = [];
  const backend = { backend: 'fake', model: 'fake', maxTurns: 3, close() {},
    send: async text => { prompts.push(text); return { text: 'Сейчас сделаю.' }; } };
  const hearing = { transcribe: async () => 'Кирилл, принеси уголь', close() {} };
  const speech = { synthesize: async text => Buffer.from(`WAV:${text}`) };
  const session = new BrainSession({ body, backend, bot: 'Kirill', pauseMs: 5, hearing, speech });
  await session.start();
  body.emit('event', { type: 'voice', from: 'Aremnchan', bots: ['Someone'], ms: 900, wav: 'AAAA' });
  body.emit('event', { type: 'voice', from: 'Aremnchan', bots: ['Kirill'], ms: 900, wav: 'AAAA', pointed_ref: 'p1' });
  await until(() => body.calls.some(c => c.tool === 'speak'));
  assert.deepEqual(body.calls.find(c => c.tool === 'heard').args, { player: 'Aremnchan', text: 'Кирилл, принеси уголь' });
  assert.ok(prompts.some(p => p.includes('принеси уголь')), 'the transcript reached the model as a chat message');
  assert.ok(body.calls.some(c => c.tool === 'say'), 'the reply still goes to text chat');
  assert.match(Buffer.from(body.calls.find(c => c.tool === 'speak').args.wav, 'base64').toString(), /^WAV:/u);
  assert.equal(body.calls.filter(c => c.tool === 'heard').length, 1, 'a phrase near another bot is ignored');
  session.close();
});

test('voices are named by speaker, cycled with next and matched by part of a name', () => {
  const voices = ['V/ru_RU-denis-medium.onnx', 'V/ru_RU-dmitri-medium.onnx', 'V/ru_RU-irina-medium.onnx'];
  assert.equal(voiceName(voices[2]), 'irina');
  assert.equal(pickVoice(voices, voices[1], 'next'), voices[2]);
  assert.equal(pickVoice(voices, voices[2], 'дальше'), voices[0], 'next wraps around');
  assert.equal(pickVoice(voices, voices[0], 'IRINA'), voices[2]);
  assert.equal(pickVoice(voices, voices[0], 'list'), null);
  assert.equal(pickVoice(voices, voices[0], ''), null);
  assert.equal(pickVoice(voices, voices[0], 'olga'), undefined);
});

test('/blocknet voice switches the voice at once, remembers it and says so in the new voice', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'blocknet-voices-'));
  for (const name of ['ru_RU-dmitri-medium.onnx', 'ru_RU-irina-medium.onnx']) await writeFile(join(dir, name), '');
  class Body extends EventEmitter {
    hello = { bots: ['Kirill'], tools: [] }; calls = [];
    async ready() { return this.hello; }
    async call(bot, tool, args) { this.calls.push({ tool, args }); return { result: 'ok' }; }
    close() {}
  }
  const body = new Body(), spoken = [];
  const backend = { backend: 'fake', model: 'fake', maxTurns: 3, close() {}, send: async () => ({ text: '<silent>' }) };
  const speech = new Speech({ piper: 'piper.exe', voice: join(dir, 'ru_RU-dmitri-medium.onnx'), voices: dir });
  speech.synthesize = async text => { spoken.push([voiceName(speech.voice), text]); return Buffer.from('WAV'); };
  const session = new BrainSession({ body, backend, bot: 'Kirill', pauseMs: 5, speech });
  await session.start();
  body.emit('event', { type: 'voice.choose', bot: 'Someone', from: 'Aremnchan', choice: 'next' });
  body.emit('event', { type: 'voice.choose', bot: 'Kirill', from: 'Aremnchan', choice: '' });
  await until(() => spoken.length === 1);
  assert.match(spoken[0][1], /dmitri, irina.*Сейчас dmitri/u, 'lists voices without switching');
  body.emit('event', { type: 'voice.choose', bot: 'Kirill', from: 'Aremnchan', choice: 'next' });
  await until(() => spoken.length === 2);
  assert.deepEqual(spoken[1][0], 'irina', 'the confirmation is already in the new voice');
  assert.equal((await readFile(join(dir, 'chosen.txt'), 'utf8')).trim(), 'ru_RU-irina-medium.onnx');
  assert.equal(voiceConfig({ home: 'X:/nowhere', tts: { piper: 'p.exe', voices: dir } }).tts.voice, join(dir, 'ru_RU-irina-medium.onnx'),
    'the next start keeps the chosen voice');
  body.emit('event', { type: 'voice.choose', bot: 'Kirill', from: 'Aremnchan', choice: 'olga' });
  await until(() => spoken.length === 3);
  assert.match(spoken[2][1], /Нет голоса «olga»/u);
  assert.equal(voiceName(speech.voice), 'irina', 'an unknown name keeps the voice');
  session.close();
  await rm(dir, { recursive: true, force: true });
});

test('the quick mouth answers first with a tiny prompt; the model preamble does not repeat it', async () => {
  class Body extends EventEmitter {
    hello = { bots: ['Kirill'], tools: [] }; calls = [];
    async ready() { return this.hello; }
    async call(bot, tool, args) { this.calls.push({ tool, args }); return { result: tool === 'changes' ? 'body r1: health 20/20' : 'ok' }; }
    close() {}
  }
  const said = body => body.calls.filter(c => c.tool === 'say').map(c => c.args.text);
  let release;
  const actorGate = new Promise(resolve => { release = resolve; });
  const quickPrompts = [];
  const make = (quickText, quickDelay) => {
    const body = new Body();
    const backend = { backend: 'fake', model: 'fake', maxTurns: 3, close() {},
      send: async () => { await actorGate; return { text: 'Ща, иду.\n<silent>' }; } };
    const quickBackend = { close() {}, send: async text => { quickPrompts.push(text); await sleep(quickDelay); return { text: quickText }; } };
    return { body, session: new BrainSession({ body, backend, bot: 'Kirill', pauseMs: 5, quickBackend }) };
  };
  const fast = make('Ага, бегу.', 1);
  await fast.session.start();
  fast.body.emit('event', { type: 'chat', from: 'Aremnchan', text: 'Кирилл, иди сюда к дому' });
  await until(() => said(fast.body).length === 1);
  assert.deepEqual(said(fast.body), ['Ага, бегу.'], 'the quick line is out before the actor answers');
  assert.ok(quickPrompts[0].length < 3000, `tiny prompt: ${quickPrompts[0].length} chars`);
  assert.match(quickPrompts[0], /иди сюда к дому/u);
  release();
  await until(() => !fast.session.busy);
  assert.deepEqual(said(fast.body), ['Ага, бегу.'], 'the actor preamble duplicate is dropped');
  fast.session.close();

  const slow = make('Ага, бегу.', 80);
  await slow.session.start();
  slow.body.emit('event', { type: 'chat', from: 'Aremnchan', text: 'Кирилл, иди сюда к дому' });
  await until(() => !slow.session.busy);
  await sleep(120);
  assert.deepEqual(said(slow.body), ['Ща, иду.'], 'a quick line later than the preamble stays silent');
  slow.session.close();
});

test('a chore the body took on is voiced once in a casual line, not while a turn is talking', async () => {
  class Body extends EventEmitter {
    hello = { bots: ['Kirill'], tools: [] }; calls = [];
    async ready() { return this.hello; }
    async call(bot, tool, args) { this.calls.push({ tool, args }); return { result: 'ok' }; }
    close() {}
  }
  const body = new Body(), prompts = [];
  const backend = { backend: 'fake', model: 'fake', maxTurns: 3, close() {}, send: async () => ({ text: '<silent>' }) };
  const chatBackend = { backend: 'fake', model: 'fake', close() {}, send: async text => { prompts.push(text); return { text: 'Пойду дерева нарублю.' }; } };
  const session = new BrainSession({ body, backend, chatBackend, bot: 'Kirill', pauseMs: 5 });
  await session.start();
  const chore = note => body.emit('event', { type: 'moment', bot: 'Kirill', kind: 'chore', note });
  chore('low on wood (2 logs); chopping the oak tree t3 nearby');
  await until(() => body.calls.some(c => c.tool === 'say'));
  assert.equal(body.calls.find(c => c.tool === 'say').args.text, 'Пойду дерева нарублю.');
  assert.match(prompts[0], /chopping the oak tree t3/u);
  chore('no torches left; crafting 8 from coal and sticks');
  await sleep(30);
  assert.equal(prompts.length, 1, 'at most one chore line in 20 s');
  session.close();
});
