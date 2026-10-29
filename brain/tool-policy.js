// The actor and the planner see only these high-level tools; mine_ore, explore_caves, scene, ...
// stay on the bridge as obtain's fallbacks, for debugging and typed commands. Fewer, bigger tools
// keep a small model from picking the wrong one. descend and tunnel are visible (ADR-0015): players
// ask to "dig down" or "make a mine", and without them the bot said it could not.
export const ACTOR_TOOLS = ['map', 'look', 'obtain', 'craft', 'goto', 'come', 'follow', 'attack', 'pickup',
  'give', 'gather', 'inspect', 'look-in', 'memory', 'transfer', 'job', 'menu', 'menu_click', 'encode_pattern', 'plan', 'place', 'use', 'eat', 'deposit', 'withdraw', 'autocraft', 'stop', 'wiki', 'knowledge', 'inv', 'locate', 'descend', 'tunnel'];

export const actorTools = tools => (tools ?? []).filter(t => ACTOR_TOOLS.includes(t.name));
