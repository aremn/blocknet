/** Match only short addressed orders; longer requests belong to the model. */
export function addressed(text, bot, aliases = []) {
  for (const name of [bot, ...aliases].filter(Boolean).sort((a, b) => b.length - a.length)) {
    if (text.slice(0, name.length).toLowerCase() !== name.toLowerCase()) continue;
    const rest = text.slice(name.length);
    if (rest && !/^[\s,:!?.—-]/u.test(rest)) continue;
    return rest.replace(/^[\s,:!?.—-]+/u, '').trim();
  }
  return null;
}

export function routeRule(text, player) {
  const order = text.toLowerCase().trim().replace(/[.!?]+$/u, '').trim();
  const result = (tool, args) => ({ tool, args });
  if (/^(stop|стоп)$/u.test(order)) return result('stop', {});
  if (/^(come|come here|come to me|ко мне|сюда)$/u.test(order)) return result('come', { player });
  if (/^(follow|follow me|за мной|следуй|следуй за мной)$/u.test(order)) return result('follow', { player });
  if (/^(look|look around|осмотрись)$/u.test(order)) return result('look', {});
  const gather = order.match(/^(?:накопай|добудь)\s+(\d+)\s+(.+)$/u);
  if (gather) {
    const items = { булыжника: 'cobblestone', булыжник: 'cobblestone', камня: 'stone', камень: 'stone',
      земли: 'dirt', землю: 'dirt', песка: 'sand', песок: 'sand', угля: 'coal', уголь: 'coal' };
    const item = items[gather[2]] ?? (/^(?:[a-z0-9_.-]+:)?[a-z0-9_./-]+$/u.test(gather[2]) ? gather[2] : null);
    const count = Number(gather[1]);
    if (item && count > 0 && count <= 1000000) return result('gather', { item, count });
  }
  return null;
}

/** All brains see the same bridge chat/spoke events, keeping player routing consistent. */
export class ChatRouter {
  constructor(bot, aliases = [], listen = 'all') {
    if (!['all', 'name'].includes(listen)) throw new Error('--listen must be all or name');
    this.bot = bot; this.aliases = aliases; this.listen = listen; this.last = new Map();
  }
  spoke(bot, player) { if (player) this.last.set(player, bot); }
  route(text, player, bots = [this.bot]) {
    const explicit = addressed(text, this.bot, this.aliases);
    if (explicit !== null) return explicit;
    if (this.listen === 'name') return null;
    for (const bot of bots) if (addressed(text, bot) !== null) return null;
    return bots.length === 1 && bots[0].toLowerCase() === this.bot.toLowerCase()
      || this.last.get(player)?.toLowerCase() === this.bot.toLowerCase() ? text.trim() : null;
  }
}
