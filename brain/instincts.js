// Instincts (ADR-0017, after GregTech Arena's huygens/instincts.py): when a job fails, a hint says where to
// look, never what the answer is; the same failure again says that repeating will not change it.

/** (pattern on the body's report, hint). The first match wins, so narrow patterns come first. */
export const RULES = [
  [/need a (?:pickaxe|tool)\b|needs? an? (?:axe|pickaxe|shovel)/iu,
    'The bot lacks the right tool for that block. It can make one from what it has, or the player can hand one over.'],
  [/inventory full|no room/iu, 'The bag is full: putting the loot into a chest makes room.'],
  [/nothing to smelt/iu, 'There is no raw ore in the bag: it has to be dug first.'],
  [/unknown item|no item named/iu, 'This pack has no item by that name: ask what they mean, by the name the game shows.'],
  [/no recipe for|no crafting-table recipe/iu, 'Nothing here crafts or smelts it by hand: it may come from a machine, a mob or a structure.'],
  [/no place named/iu, 'The notebook has no place by that name: the player can look at it and say what it is.'],
  [/cannot see container|not a container|unknown observed ref|retired or unconfirmed ref|hint is in another dimension/iu,
    'The bot has to see that container from where it stands: the player should look at it and say it again.'],
  [/recipient is behind an obstacle|recipient unavailable|in another dimension|is gone\b/iu,
    'The player has to be reachable: same dimension, no wall in between.'],
  [/budget ended|timed out/iu, 'The job ran out of time. What it got so far is in the bag; asking again goes on from there.'],
  [/\bdied\b/iu, 'The bot died during the job; what it carried may lie where it died.'],
  [/stuck|got as close as i could|no path|unreachable/iu, 'The way there is blocked: the player could clear it or lead the bot closer.'],
  [/not enough|missing|short of/iu, 'Materials are missing: say which ones and where they could come from.'],
];

const REPEAT_WINDOW_MS = 30 * 60_000;

export function hint(report) {
  const text = String(report ?? '');
  return RULES.find(([pattern]) => pattern.test(text))?.[1] ?? null;
}

/** The same failure in other numbers (counts, coordinates, ticks) is the same failure. */
export const normalise = report => String(report ?? '').replace(/^\[[^\]]+\] /u, '').replace(/-?\d+(?:[.,]\d+)?/gu, '#').slice(0, 160);

/** Remembers recent failures of one bot; `failed` returns the hint and how often this one came back. */
export class Instincts {
  seen = new Map();
  constructor(windowMs = REPEAT_WINDOW_MS) { this.windowMs = windowMs; }
  failed(job, report, now = Date.now()) {
    const key = `${job.job} ${job.item ?? job.what ?? ''} ${normalise(report)}`;
    for (const [k, v] of this.seen) if (now - v.last > this.windowMs) this.seen.delete(k);
    const before = this.seen.get(key);
    const entry = { times: (before?.times ?? 0) + 1, first: before?.first ?? now, last: now };
    this.seen.set(key, entry);
    return { hint: hint(report), times: entry.times, minutes: Math.round((now - entry.first) / 60_000) };
  }
  /** Prompt lines for the model that explains the failure to the player. */
  lines(job, report, now) {
    const { hint: h, times, minutes } = this.failed(job, report, now);
    return [h && `Hint (where to look, not the answer): ${h}`,
      times > 1 && `The same job failed the same way ${times} times in ${minutes} min: do not suggest simply trying again; say what has to change.`]
      .filter(Boolean);
  }
}
