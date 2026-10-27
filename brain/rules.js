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
  const ru = /[а-яё]/iu.test(order);
  const result = (tool, args, english, russian) => ({ tool, args, ack: ru ? russian : english });
  if (/^(stop|стоп)$/u.test(order)) return result('stop', {}, 'Stopped.', 'Стою.');
  if (/^(come|come here|come to me|ко мне|сюда)$/u.test(order)) return result('come', { player }, 'Coming.', 'Иду.');
  if (/^(follow|follow me|за мной|следуй|следуй за мной)$/u.test(order)) return result('follow', { player }, 'Following.', 'Следую за тобой.');
  if (/^(look|look around|осмотрись)$/u.test(order)) return result('look', {}, 'Looking around.', 'Осматриваюсь.');
  return null;
}
