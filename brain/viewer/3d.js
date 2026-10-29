// 3D view of the bot's eye memory: remembered surface blocks as cubes, a free-flying camera and a
// crosshair that names the block it points at. Plain WebGL2, no libraries (the page CSP allows only
// this origin's scripts).
const $ = id => document.getElementById(id);
const canvas = $('scene');
const gl = canvas.getContext('webgl2', { antialias: true });
if (!gl) throw new Error('WebGL2 недоступен в этом браузере');

// ---- colors ---------------------------------------------------------------------------------
const ORE = [['coal', [40, 40, 44]], ['iron', [216, 175, 147]], ['copper', [224, 128, 82]], ['gold', [250, 214, 70]],
  ['diamond', [90, 236, 228]], ['emerald', [60, 220, 110]], ['lapis', [40, 80, 220]], ['redstone', [230, 40, 40]], ['quartz', [235, 225, 215]]];
const CATEGORY = { stone: [128, 134, 138], cliff: [128, 134, 138], dirt: [134, 96, 67], tree: [104, 82, 52], water: [52, 110, 200],
  lava: [235, 110, 30], built: [176, 150, 112], station: [196, 142, 110], container: [214, 168, 98], machine: [170, 158, 215],
  crop: [150, 190, 70], dark: [96, 90, 132], other: [140, 150, 150] };
function colorOf(id, category) {
  const name = id.replace(/^.*:/u, '');
  if (id === 'unknown_dark') return [96, 90, 132];
  if (name.endsWith('_ore') || category === 'ore') {
    for (const [key, rgb] of ORE) if (name.includes(key)) return rgb;
    return [210, 170, 80];
  }
  if (name.includes('leaves')) return [62, 128, 52];
  if (name.includes('grass_block') || name === 'moss_block') return [98, 150, 66];
  if (name.includes('deepslate') || name.includes('tuff') || name.includes('basalt')) return [78, 80, 86];
  if (name.includes('sand')) return [216, 202, 150];
  if (name.includes('gravel')) return [140, 132, 128];
  if (name.includes('snow') || name.includes('ice')) return [228, 240, 248];
  if (name.includes('planks') || name.includes('stairs') || name.includes('slab') && name.includes('oak')) return [176, 140, 90];
  if (name.includes('log') || name.includes('wood')) return [104, 82, 52];
  if (name.includes('andesite')) return [136, 138, 136];
  if (name.includes('diorite')) return [196, 196, 196];
  if (name.includes('granite')) return [154, 106, 89];
  if (name.includes('dripstone')) return [130, 100, 80];
  return CATEGORY[category] ?? CATEGORY.other;
}
const translucent = id => /water|glass|ice(?!_)|leaves/u.test(id) && !id.includes('packed');

// ---- matrices ------------------------------------------------------------------------------
function perspective(fovy, aspect, near, far) {
  const f = 1 / Math.tan(fovy / 2), nf = 1 / (near - far);
  return [f / aspect, 0, 0, 0, 0, f, 0, 0, 0, 0, (far + near) * nf, -1, 0, 0, 2 * far * near * nf, 0];
}
function lookAt(eye, center, up) {
  let zx = eye[0] - center[0], zy = eye[1] - center[1], zz = eye[2] - center[2];
  let len = Math.hypot(zx, zy, zz); zx /= len; zy /= len; zz /= len;
  let xx = up[1] * zz - up[2] * zy, xy = up[2] * zx - up[0] * zz, xz = up[0] * zy - up[1] * zx;
  len = Math.hypot(xx, xy, xz); xx /= len; xy /= len; xz /= len;
  const yx = zy * xz - zz * xy, yy = zz * xx - zx * xz, yz = zx * xy - zy * xx;
  return [xx, yx, zx, 0, xy, yy, zy, 0, xz, yz, zz, 0,
    -(xx * eye[0] + xy * eye[1] + xz * eye[2]), -(yx * eye[0] + yy * eye[1] + yz * eye[2]), -(zx * eye[0] + zy * eye[1] + zz * eye[2]), 1];
}
function multiply(a, b) {
  const out = new Array(16);
  for (let c = 0; c < 4; c++) for (let r = 0; r < 4; r++)
    out[c * 4 + r] = a[r] * b[c * 4] + a[4 + r] * b[c * 4 + 1] + a[8 + r] * b[c * 4 + 2] + a[12 + r] * b[c * 4 + 3];
  return out;
}

// ---- shaders -------------------------------------------------------------------------------
function program(vertex, fragment) {
  const make = (type, source) => {
    const shader = gl.createShader(type); gl.shaderSource(shader, source); gl.compileShader(shader);
    if (!gl.getShaderParameter(shader, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(shader));
    return shader;
  };
  const p = gl.createProgram();
  gl.attachShader(p, make(gl.VERTEX_SHADER, vertex)); gl.attachShader(p, make(gl.FRAGMENT_SHADER, fragment)); gl.linkProgram(p);
  if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
  return p;
}
const shader = program(`#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec4 aColor;
uniform mat4 uMVP; uniform vec3 uEye; out vec4 vColor; out float vDist;
void main(){ gl_Position = uMVP * vec4(aPos, 1.0); vColor = aColor; vDist = distance(aPos, uEye); }`,
`#version 300 es
precision mediump float; in vec4 vColor; in float vDist; uniform float uFog; out vec4 outColor;
void main(){ float fog = clamp((vDist - uFog * 0.55) / (uFog * 0.45), 0.0, 0.85);
  outColor = vec4(mix(vColor.rgb, vec3(0.05, 0.09, 0.11), fog), vColor.a); }`);
const uMVP = gl.getUniformLocation(shader, 'uMVP'), uEye = gl.getUniformLocation(shader, 'uEye'), uFog = gl.getUniformLocation(shader, 'uFog');

// ---- meshes --------------------------------------------------------------------------------
// Each face: normal, shade, and its four corners as offsets from the block's min corner.
const FACES = [
  [[0, 1, 0], 1.0, [[0, 1, 0], [0, 1, 1], [1, 1, 1], [1, 1, 0]]],
  [[0, -1, 0], 0.5, [[0, 0, 0], [1, 0, 0], [1, 0, 1], [0, 0, 1]]],
  [[1, 0, 0], 0.8, [[1, 0, 0], [1, 1, 0], [1, 1, 1], [1, 0, 1]]],
  [[-1, 0, 0], 0.8, [[0, 0, 0], [0, 0, 1], [0, 1, 1], [0, 1, 0]]],
  [[0, 0, 1], 0.65, [[0, 0, 1], [1, 0, 1], [1, 1, 1], [0, 1, 1]]],
  [[0, 0, -1], 0.65, [[0, 0, 0], [0, 1, 0], [1, 1, 0], [1, 0, 0]]]];
function upload(positions, colors, count) {
  const vao = gl.createVertexArray(); gl.bindVertexArray(vao);
  const pos = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, pos); gl.bufferData(gl.ARRAY_BUFFER, positions.subarray(0, count * 3), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 0, 0);
  const col = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, col); gl.bufferData(gl.ARRAY_BUFFER, colors.subarray(0, count * 4), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 4, gl.UNSIGNED_BYTE, true, 0, 0);
  const quads = count / 4, indices = new Uint32Array(quads * 6);
  for (let q = 0; q < quads; q++) indices.set([q * 4, q * 4 + 1, q * 4 + 2, q * 4, q * 4 + 2, q * 4 + 3], q * 6);
  const ibo = gl.createBuffer(); gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, ibo); gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, indices, gl.STATIC_DRAW);
  gl.bindVertexArray(null);
  return { vao, count: indices.length, buffers: [pos, col, ibo] };
}
function release(mesh) { if (!mesh) return; gl.deleteVertexArray(mesh.vao); mesh.buffers.forEach(b => gl.deleteBuffer(b)); }

let snapshot = null, blocks = new Map(), opaque = null, glass = null, botMesh = null;
const key = (x, y, z) => `${x},${y},${z}`;
function build(data) {
  blocks = new Map();
  const [cx, cy, cz] = data.center, rows = data.voxels;
  for (let i = 0; i < rows.length; i += 5) {
    const [id, category] = data.palette[rows[i + 3]];
    blocks.set(key(cx + rows[i], cy + rows[i + 1], cz + rows[i + 2]), { id, category, age: rows[i + 4] });
  }
  // Two passes: count the visible faces, then fill buffers of exactly that size.
  const visible = [];
  const counts = [0, 0];
  for (const [k, block] of blocks) {
    const [x, y, z] = k.split(',').map(Number), clear = translucent(block.id) ? 1 : 0;
    for (let face = 0; face < 6; face++) {
      const n = FACES[face][0], neighbour = blocks.get(key(x + n[0], y + n[1], z + n[2]));
      // Hide faces between two solid blocks, and between two blocks of the same see-through kind.
      if (neighbour && (!translucent(neighbour.id) || translucent(block.id) && neighbour.id === block.id)) continue;
      visible.push(x, y, z, face, clear);
      counts[clear]++;
    }
  }
  const sizes = [0, 0], buffers = counts.map(n => [new Float32Array(n * 12), new Uint8Array(n * 16)]);
  for (let i = 0; i < visible.length; i += 5) {
    const [x, y, z, face, clear] = visible.slice(i, i + 5), block = blocks.get(key(x, y, z));
    const base = colorOf(block.id, block.category), dim = block.id === 'unknown_dark' ? 1 : block.age <= 10 ? 1 : 0.55;
    {
      const [, shade, corners] = FACES[face];
      const [p, c] = buffers[clear];
      for (const corner of corners) {
        const v = sizes[clear]++;
        p.set([x + corner[0], y + corner[1], z + corner[2]], v * 3);
        c.set([base[0] * shade * dim, base[1] * shade * dim, base[2] * shade * dim, clear ? 115 : 255], v * 4);
      }
    }
  }
  release(opaque); release(glass);
  opaque = upload(buffers[0][0], buffers[0][1], sizes[0]);
  glass = upload(buffers[1][0], buffers[1][1], sizes[1]);
  // The bot: a bright 0.6 x 1.8 x 0.6 column at its feet.
  const [bx, by, bz] = data.position, box = new Float32Array(72), boxColor = new Uint8Array(96);
  let v = 0;
  for (const [, shade, corners] of FACES) for (const corner of corners) {
    box.set([bx + 0.2 + corner[0] * 0.6, by + corner[1] * 1.8, bz + 0.2 + corner[2] * 0.6], v * 3);
    boxColor.set([95 * shade + 60, 240 * shade, 208 * shade, 255], v * 4); v++;
  }
  release(botMesh); botMesh = upload(box, boxColor, 24);
}

// ---- camera and controls -------------------------------------------------------------------
const camera = { pos: [0, 80, 0], yaw: 0, pitch: 30, speed: 12 };
const keys = new Set();
function forward() {
  const yaw = camera.yaw * Math.PI / 180, pitch = camera.pitch * Math.PI / 180;
  return [-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)];
}
function home() {
  if (!snapshot) return;
  const [bx, by, bz] = snapshot.position, yaw = snapshot.yaw * Math.PI / 180;
  // Behind and above the bot, looking where it looks.
  camera.pos = [bx + 0.5 + Math.sin(yaw) * 9, by + 7, bz + 0.5 - Math.cos(yaw) * 9];
  camera.yaw = snapshot.yaw; camera.pitch = 30;
}
// Look around: drag with any mouse button, or click once for game-style mouse look (Esc releases).
let drag = null;
const turn = (dx, dy) => {
  camera.yaw += dx * 0.15;
  camera.pitch = Math.max(-89, Math.min(89, camera.pitch + dy * 0.15));
};
canvas.addEventListener('contextmenu', e => e.preventDefault());
canvas.addEventListener('mousedown', e => { if (document.pointerLockElement !== canvas) drag = { x: e.clientX, y: e.clientY, moved: false }; });
document.addEventListener('mouseup', e => {
  if (drag && !drag.moved && e.button === 0) canvas.requestPointerLock();
  drag = null;
});
document.addEventListener('mousemove', e => {
  if (document.pointerLockElement === canvas) { turn(e.movementX, e.movementY); return; }
  if (!drag) return;
  const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
  if (Math.abs(dx) + Math.abs(dy) > 2) drag.moved = true;
  drag.x = e.clientX; drag.y = e.clientY;
  if (drag.moved) turn(dx, dy);
});
document.addEventListener('keydown', e => {
  if (e.target.tagName === 'SELECT') return;
  keys.add(e.code);
  if (e.code === 'KeyF') home();
  if (e.code === 'Space') e.preventDefault();
});
document.addEventListener('keyup', e => keys.delete(e.code));
canvas.addEventListener('wheel', e => { e.preventDefault(); camera.speed = Math.max(2, Math.min(80, camera.speed * (e.deltaY < 0 ? 1.2 : 1 / 1.2))); }, { passive: false });

/** Walk the voxel grid from the eye (Amanatides-Woo) to the first remembered block. */
function pick() {
  const d = forward(), p = camera.pos;
  let x = Math.floor(p[0]), y = Math.floor(p[1]), z = Math.floor(p[2]);
  const step = d.map(v => Math.sign(v)), delta = d.map(v => v === 0 ? Infinity : Math.abs(1 / v));
  const next = [0, 1, 2].map(i => d[i] === 0 ? Infinity : ((d[i] > 0 ? Math.floor(p[i]) + 1 - p[i] : p[i] - Math.floor(p[i])) * delta[i]));
  for (let i = 0; i < 400; i++) {
    const block = blocks.get(key(x, y, z));
    if (block) return { x, y, z, ...block };
    const axis = next[0] < next[1] ? (next[0] < next[2] ? 0 : 2) : (next[1] < next[2] ? 1 : 2);
    if (axis === 0) x += step[0]; else if (axis === 1) y += step[1]; else z += step[2];
    next[axis] += delta[axis];
    if (next[axis] > 250) break;
  }
  return null;
}

let last = performance.now(), lastPick = 0;
function frame(now) {
  const dt = Math.min(0.1, (now - last) / 1000); last = now;
  const f = forward(), flat = Math.hypot(f[0], f[2]) || 1, fwd = [f[0] / flat, 0, f[2] / flat], right = [-fwd[2], 0, fwd[0]];
  const speed = camera.speed * (keys.has('ShiftLeft') || keys.has('ShiftRight') ? 3 : 1) * dt;
  const move = (v, s) => { for (let i = 0; i < 3; i++) camera.pos[i] += v[i] * s; };
  if (keys.has('KeyW')) move(f, speed); if (keys.has('KeyS')) move(f, -speed);
  if (keys.has('KeyD')) move(right, speed); if (keys.has('KeyA')) move(right, -speed);
  if (keys.has('Space')) camera.pos[1] += speed; if (keys.has('KeyC') || keys.has('ControlLeft')) camera.pos[1] -= speed;
  const look = 120 * dt / 0.15;
  if (keys.has('ArrowLeft')) turn(-look, 0); if (keys.has('ArrowRight')) turn(look, 0);
  if (keys.has('ArrowUp')) turn(0, -look); if (keys.has('ArrowDown')) turn(0, look);

  const ratio = devicePixelRatio || 1, w = canvas.clientWidth * ratio, h = canvas.clientHeight * ratio;
  if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
  gl.viewport(0, 0, w, h);
  gl.clearColor(0.05, 0.09, 0.11, 1); gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT);
  const eye = camera.pos, view = lookAt(eye, [eye[0] + f[0], eye[1] + f[1], eye[2] + f[2]], [0, 1, 0]);
  const fog = (snapshot?.radius ?? 64) * 2.2;
  gl.useProgram(shader);
  gl.uniformMatrix4fv(uMVP, false, multiply(perspective(70 * Math.PI / 180, w / h, 0.1, fog * 2), view));
  gl.uniform3fv(uEye, eye); gl.uniform1f(uFog, fog);
  gl.enable(gl.DEPTH_TEST); gl.enable(gl.CULL_FACE); gl.disable(gl.BLEND); gl.depthMask(true);
  for (const mesh of [opaque, botMesh]) if (mesh) { gl.bindVertexArray(mesh.vao); gl.drawElements(gl.TRIANGLES, mesh.count, gl.UNSIGNED_INT, 0); }
  if (glass?.count) {
    gl.enable(gl.BLEND); gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA); gl.depthMask(false); gl.disable(gl.CULL_FACE);
    gl.bindVertexArray(glass.vao); gl.drawElements(gl.TRIANGLES, glass.count, gl.UNSIGNED_INT, 0);
  }
  gl.bindVertexArray(null);
  if (now - lastPick > 120) {
    lastPick = now;
    const hit = pick();
    $('picked').textContent = hit ? `${hit.id === 'unknown_dark' ? 'видел в темноте: блок неизвестен' : hit.id}\n`
      + `${hit.category} · X ${hit.x} · Y ${hit.y} · Z ${hit.z}\nвидел ${hit.age} с назад` : 'Под прицелом ничего не запомнено';
  }
  requestAnimationFrame(frame);
}

// ---- data ----------------------------------------------------------------------------------
function error(message) { $('error').textContent = message; $('error').style.display = message ? 'block' : 'none'; }
let loading = false, firstLoad = true;
async function refresh() {
  if (loading) return; loading = true;
  try {
    const state = await (await fetch('/api/bots')).json();
    const old = $('bot').value;
    $('bot').replaceChildren(...state.bots.map(name => { const o = document.createElement('option'); o.value = name; o.textContent = name; return o; }));
    if (state.bots.includes(old)) $('bot').value = old;
    $('connection').textContent = state.connected ? 'Игра подключена' : 'Ожидание игры';
    if (!state.connected || !state.bots.length) {
      error(state.connected ? 'В мире нет ботов: /blocknet spawn <имя>.' : 'Нет соединения с игрой.'); return;
    }
    const query = new URLSearchParams({ bot: $('bot').value, mode: 'voxels', radius: $('radius').value });
    const response = await fetch('/api/memory?' + query), data = await response.json();
    if (!response.ok) throw new Error(data.error);
    snapshot = data; build(data);
    if (firstLoad) { home(); firstLoad = false; }
    $('stats').textContent = `${(data.voxels.length / 5).toLocaleString()} видимых блоков из ${data.knownBlocks.toLocaleString()} запомненных`
      + `${data.truncated ? ' (обрезано по лимиту)' : ''} · радиус ${data.radius} · ${data.dimension} · ${new Date().toLocaleTimeString()}`;
    $('task').textContent = `${data.bot}: ${data.task} · восприятие ${data.perception}`;
    error('');
  } catch (e) { error(`${e.message} Последний снимок оставлен на экране.`); }
  finally { loading = false; }
}
$('refresh').onclick = refresh;
$('home').onclick = home;
$('bot').onchange = () => { firstLoad = true; refresh(); };
$('radius').onchange = refresh;
setInterval(() => { if ($('live').checked && !document.hidden) refresh(); }, 10000);
refresh();
requestAnimationFrame(frame);
