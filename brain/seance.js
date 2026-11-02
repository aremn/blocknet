// Speak with the translator after the fact (ADR-0017, after GregTech Arena's `arena seance`): a logged
// model turn is put back in front of the same model, with its own answer, and the developer asks why.
//   node brain/seance.js [--session <file>] [--turn N] [--model claude:haiku] "почему ты так понял?"
import { appendFileSync, readdirSync, writeFileSync } from 'node:fs';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { option } from './body.js';
import { readSession } from './session-log.js';

const logDir = resolve(dirname(fileURLToPath(import.meta.url)), 'logs');

/** The model turns a session can be asked about: translations and explanations with their prompt. */
export const modelTurns = entries => entries.filter(e => ['translate', 'explain'].includes(e.type) && e.prompt && e.raw != null);

/** Turn N (1-based; negative counts from the end, -1 = the last). */
export function pickTurn(entries, n = -1) {
  const turns = modelTurns(entries);
  const turn = turns[n < 0 ? turns.length + n : n - 1];
  if (!turn) throw new Error(`no model turn ${n}; this session has ${turns.length}`);
  return turn;
}

export function seancePrompt(turn, question) {
  return 'This is not a game message. The developer of the bot is asking about an answer you gave earlier, while '
    + 'translating a player\'s words into jobs. Answer in plain words, in the developer\'s language, no JSON: what '
    + 'you understood, which line of your instructions or of the message led you there, and what would have helped.\n\n'
    + `The message you got then:\n<<<\n${turn.prompt}\n>>>\n\nWhat you answered:\n<<<\n${turn.raw}\n>>>\n\n`
    + `The developer asks: ${question}`;
}

/** The backend the session used, as "backend:model", from its logged model calls. */
export function sessionModel(entries) {
  const call = entries.filter(e => e.type === 'turn' && e.backend && e.model).at(-1);
  return call ? `${call.backend}:${call.model}` : 'claude:haiku';
}

export function newestSession(dir = logDir) {
  const files = readdirSync(dir).filter(f => /^session-.*\.jsonl$/u.test(f)).sort();
  if (!files.length) throw new Error(`no sessions in ${dir}`);
  return join(dir, files.at(-1));
}

export async function main(argv = process.argv.slice(2)) {
  const question = argv.filter((a, i) => !a.startsWith('--') && !argv[i - 1]?.startsWith('--')).at(-1);
  if (!question) throw new Error('usage: node brain/seance.js [--session <file>] [--turn N] [--model backend:model] "question"');
  const session = option('session', null, argv) ?? newestSession();
  const entries = readSession(session);
  const turn = pickTurn(entries, Number(option('turn', '-1', argv)));
  const { createBackend, parseModel, systemPrompt } = await import('./brain.js');
  const spec = parseModel(option('model', sessionModel(entries), argv));
  const promptFile = resolve(logDir, 'prompt.seance.md');
  writeFileSync(promptFile, systemPrompt);
  const backend = createBackend(spec.backend, { ...spec, prompt: systemPrompt, promptFile, maxTurns: 1, timeoutMs: 120_000 });
  try {
    const answer = (await backend.send(seancePrompt(turn, question))).text;
    appendFileSync(join(logDir, `seance-${basename(session, '.jsonl')}.md`),
      `## ${new Date().toISOString()} (${turn.type} at ${turn.time})\n\nQ: ${question}\n\n${answer}\n\n`);
    process.stdout.write(`${answer}\n`);
  } finally { backend.close(); }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => { process.stderr.write(`${error.message}\n`); process.exit(1); });
}
