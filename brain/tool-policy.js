// The actor and the planner see only these high-level tools; low-level ones (descend, tunnel,
// mine_ore, explore_caves, gather, ...) stay on the bridge for debugging and typed commands.
// Fewer, bigger tools keep a small model from picking the wrong one.
export const ACTOR_TOOLS = ['map', 'look', 'obtain', 'craft', 'goto', 'come', 'follow', 'attack', 'pickup',
  'give', 'gather', 'inspect', 'look-in', 'memory', 'transfer', 'job', 'menu', 'menu_click', 'encode_pattern', 'plan', 'place', 'use', 'eat', 'deposit', 'withdraw', 'autocraft', 'stop', 'wiki', 'knowledge', 'inv', 'locate'];

export const actorTools = tools => (tools ?? []).filter(t => ACTOR_TOOLS.includes(t.name));
