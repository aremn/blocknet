export const pickaxePlan = {
  goal: 'железная кирка с нуля', budget_min: 20,
  steps: [
    { id: 1, do: 'obtain stone_pickaxe 1', done: 'inv stone_pickaxe >= 1' },
    { id: 2, do: 'obtain iron_ingot 3', done: 'inv iron_ingot >= 3' },
    { id: 3, do: 'craft iron_pickaxe 1', done: 'inv iron_pickaxe >= 1' },
  ],
};
export const shelterPlan = {
  status: 'blocked', reason: 'Building needs a verified blueprint result; arrival and an owned door do not prove a shelter.',
};
const common = {
  state: 'pos 0 64 0; inventory: empty', inventory: 'empty inventory',
  scene: 'forest, day; trees t1 oak E 4m; observed empty building site: 2 64 2; no hostiles',
  responses: [],
};
export const roleScenarios = {
  planner: [
    { ...common, id: 'plan-iron', message: 'железная кирка с нуля', samplePlan: pickaxePlan,
      rules: [{ type: 'planQuality', target: 'iron_pickaxe' }] },
    { ...common, id: 'plan-shelter', message: 'дом до ночи', samplePlan: shelterPlan,
      rules: [{ type: 'planQuality', target: 'shelter' }] },
  ],
  actor: [
    { ...common, id: 'step-iron', message: 'Obtain three iron ingots.',
      goal: 'железная кирка с нуля', step: 'Obtain three iron ingots.', done: 'inv iron_ingot >= 3',
      planStatus: 'план: шаг 2/3 obtain iron_ingot 3 — 0/3',
      responses: [{ tool: 'obtain', itemPattern: '^(?:minecraft:)?iron_ingot$', text: 'obtained 3 iron_ingot', inventory: '3 iron_ingot slots [0]' }],
      rules: [{ type: 'singleStep', tool: 'obtain', item: 'iron_ingot', count: 3 }, { type: 'calls', max: 1 }] },
    { ...common, id: 'step-craft', message: 'Craft the iron pickaxe.',
      state: 'pos 0 64 0; inventory: 3 iron_ingot, 2 stick', inventory: '3 iron_ingot slots [0]; 2 stick slots [1]',
      goal: 'железная кирка с нуля', step: 'Craft one iron pickaxe.', done: 'inv iron_pickaxe >= 1',
      planStatus: 'план: шаг 3/3 craft iron_pickaxe 1 — 0/1',
      responses: [{ tool: 'craft', itemPattern: '^(?:minecraft:)?iron_pickaxe$', text: 'crafted iron_pickaxe', inventory: 'iron_pickaxe slots [0]' }],
      rules: [{ type: 'singleStep', tool: 'craft', item: 'iron_pickaxe', count: 1 }, { type: 'calls', max: 1 }] },
  ],
};
