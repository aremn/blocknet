// The repo is public: no personal paths, e-mail addresses or secrets in tracked files (after GregTech
// Arena's tools/check_public.py). CI runs it on every push; locally: node tools/check-public.mjs
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

export const PATTERNS = {
  'user home path': /[A-Za-z]:[\\/]+Users[\\/]+(?![<*%{$]|Public\b|Default\b|you\b|name\b)[^\\/\s"'`]+|\/c\/Users\/(?![<*$])[^/\s"'`]+/iu,
  'unix home path': /\/home\/(?![<*$]|runner\b|user\b)[a-z0-9._-]+\//u,
  'e-mail': /[A-Za-z0-9._%+-]+@(?!users\.noreply\.github\.com|anthropic\.com|example\.(?:com|org))[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}\b/u,
  'anthropic key': /sk-ant-[A-Za-z0-9_-]{10,}/u,
  'github token': /gh[pousr]_[A-Za-z0-9]{20,}/u,
  'oauth token': /CLAUDE_CODE_OAUTH_TOKEN\s*=\s*\S{10,}/u,
  'private key': /-----BEGIN [A-Z ]*PRIVATE KEY-----/u,
};
const BINARY = /\.(?:png|jpe?g|gif|ico|jar|zip|nbt|ogg|wav|onnx|bin|class|dat|mca)$/iu;

/** [{file, line, what, text}] for every line that matches a pattern. */
export function findings(file, text) {
  const out = [];
  text.split(/\r?\n/u).forEach((line, i) => {
    if (line.includes('public-check: allow')) return; // a test fixture that must look like a leak
    for (const [what, pattern] of Object.entries(PATTERNS)) {
      if (pattern.test(line)) out.push({ file, line: i + 1, what, text: line.trim().slice(0, 160) });
    }
  });
  return out;
}

export function main() {
  const files = execFileSync('git', ['ls-files', '-z'], { encoding: 'utf8' }).split('\0').filter(f => f && !BINARY.test(f));
  const found = files.flatMap(file => {
    let text;
    try { text = readFileSync(file, 'utf8'); } catch { return []; }
    return text.includes('\0') ? [] : findings(file, text);
  });
  for (const f of found) process.stdout.write(`${f.file}:${f.line}: ${f.what}: ${f.text}\n`);
  process.stdout.write(found.length ? `public check FAILED: ${found.length} line(s)\n` : `public check ok: ${files.length} files\n`);
  return found.length ? 1 : 0;
}

if (process.argv[1]?.endsWith('check-public.mjs')) process.exit(main());
