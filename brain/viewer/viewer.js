const $ = id => document.getElementById(id);
const canvas = $('map'), ctx = canvas.getContext('2d');
let data, cells = new Map(), center, zoom = 7, pending = false, queued = false, drag, width, height, first = true;
const colors = {stone:'#85949d',cliff:'#85949d',dirt:'#927354',grass:'#7b9d63',tree:'#598662',ore:'#d5a754',container:'#dfaf69',station:'#c68e6e',machine:'#ac9ed7',water:'#477fa5',lava:'#e77e46',dark:'#605b75',built:'#b9a294',air:'#263b46'};
function error(message) { $('error').textContent = message; $('error').style.display = message ? 'block' : 'none'; }
function draw() {
  const box = canvas.getBoundingClientRect(), ratio = devicePixelRatio || 1;
  width = box.width; height = box.height; canvas.width = width * ratio; canvas.height = height * ratio; ctx.setTransform(ratio,0,0,ratio,0,0);
  ctx.fillStyle = '#111e26'; ctx.fillRect(0,0,width,height);
  if (!data) return;
  const c = center ?? [data.x, data.z], screen = (x,z) => [width/2+(x-c[0])*zoom, height/2+(z-c[1])*zoom];
  for (const row of data.cells) {
    const [x,y,z,id,kind,age] = row, [px,pz] = screen(x,z);
    ctx.globalAlpha = age === null || age <= 5 ? 1 : .42;
    ctx.fillStyle = colors[kind] ?? (id.includes('leaves') ? '#598662' : '#718c91');
    ctx.fillRect(px,pz,Math.max(1,zoom-.5),Math.max(1,zoom-.5));
  }
  ctx.globalAlpha = 1;
  for (const o of data.objects.filter(o => ['container','station','machine'].includes(o.category))) {
    const [x,y,z] = o.position;
    if (data.mode === 'slice' ? y !== data.y : y > data.y) continue;
    const [px,pz] = screen(x,z); ctx.strokeStyle='#ffd69a';ctx.strokeRect(px-1,pz-1,zoom+2,zoom+2);
    ctx.fillStyle='#fff1d9';ctx.font='11px system-ui';ctx.fillText(o.ref,px+zoom+3,pz+7);
  }
  const [bx,,bz] = data.position, [px,pz] = screen(bx,bz);
  ctx.fillStyle='#72f0c0';ctx.beginPath();ctx.arc(px+zoom/2,pz+zoom/2,5,0,Math.PI*2);ctx.fill();
  ctx.font='12px system-ui';ctx.fillText('бот · Y '+data.position[1],px+12,pz-6);
  ctx.fillStyle='#8ca4ad';ctx.fillText(`N ↑   центр ${Math.round(c[0])}, ${Math.round(c[1])}   ${data.mode === 'slice' ? 'срез' : 'верх до'} Y ${data.y}`,14,24);
}
function objects() {
  $('objects').replaceChildren();
  for (const o of data?.objects ?? []) {
    if (!`${o.ref} ${o.kind}`.includes($('filter').value.toLowerCase())) continue;
    const b = document.createElement('button');b.textContent=`${o.ref} · ${o.kind} · ${o.age} с назад`;
    b.onclick=()=>{center=[o.position[0],o.position[2]];$('height').value=o.position[1];$('selected').textContent=`${o.ref} ${o.kind}\n${o.position.join(', ')}\nИзвестных блоков: ${o.blocks}`;refresh();};$('objects').append(b);
  }
}
async function refresh() {
  if (pending) { queued = true; return; } pending = true;
  try {
    const response = await fetch('/api/bots'); const state = await response.json();
    const old=$('bot').value;
    $('bot').replaceChildren(...state.bots.map(name=>{const o=document.createElement('option');o.value=name;o.textContent=name;return o;}));
    if(state.bots.includes(old))$('bot').value=old;
    $('connection').textContent=state.connected ? 'Игра подключена' : 'Ожидание игры';
    if(!state.connected || !state.bots.length){error(state.connected?'В мире нет ботов. Создай бота командой /blocknet spawn.':'Нет соединения с игрой. Проверь запуск мира и путь к конфигу. Последний снимок сохранён на экране.');return;}
    const query=new URLSearchParams({bot:$('bot').value,mode:$('mode').value,radius:'64'});
    if(!first)query.set('y',$('height').value);
    if(center){query.set('x',Math.round(center[0]));query.set('z',Math.round(center[1]));}
    const r=await fetch('/api/memory?'+query); const value=await r.json(); if(!r.ok)throw new Error(value.error);
    if (queued) return;
    if(first && value.cells.length){
      const xs=value.cells.map(c=>c[0]),zs=value.cells.map(c=>c[2]);
      const span=Math.max(12,Math.max(...xs)-Math.min(...xs)+4,Math.max(...zs)-Math.min(...zs)+4);
      zoom=Math.max(3,Math.min(24,Math.min(width||800,height||580)*.7/span));
    }
    data=value;first=false;$('height').value=data.y; cells=new Map(data.cells.map(c=>[`${c[0]},${c[2]}`,c]));
    $('mode').disabled=Boolean(data.savedSnapshot);$('height').disabled=Boolean(data.savedSnapshot);
    if(data.savedSnapshot)$('mode').value=data.mode;
    $('connection').textContent=data.savedSnapshot ? 'Сохранённый снимок · не живой мир' : 'Игра подключена';
    $('title').textContent=`${data.bot} · ${data.dimension}`;
    $('stats').textContent=`${data.knownBlocks.toLocaleString()} блоков · ${data.knownAir.toLocaleString()} ячеек воздуха · ${data.sections} секций. Снимок ${new Date().toLocaleTimeString()}. Известные высоты: ${data.minY}…${data.maxY}.`;
    $('task').textContent=data.task+(data.job && data.job!=='{}'?' · незавершённый груз сохранён':'');$('containers').textContent=data.containers;error('');objects();draw();
  } catch(e){error(e.message+' Последний снимок не обновлён.');} finally {pending=false;if(queued){queued=false;refresh();}}
}
canvas.onpointerdown=e=>{drag={x:e.clientX,y:e.clientY,start:center??[data?.x??0,data?.z??0],moved:false};canvas.setPointerCapture(e.pointerId);};
canvas.onpointermove=e=>{if(!drag)return;const dx=e.clientX-drag.x,dz=e.clientY-drag.y;if(Math.abs(dx)+Math.abs(dz)>4)drag.moved=true;center=[drag.start[0]-dx/zoom,drag.start[1]-dz/zoom];draw();};
canvas.onpointerup=e=>{if(!drag)return;if(drag.moved)refresh();else if(data){const b=canvas.getBoundingClientRect(),c=center??[data.x,data.z],x=Math.floor((e.clientX-b.left-width/2)/zoom+c[0]),z=Math.floor((e.clientY-b.top-height/2)/zoom+c[1]),cell=cells.get(`${x},${z}`);$('selected').textContent=cell?`${cell[3]}\nX ${cell[0]} · Y ${cell[1]} · Z ${cell[2]}\n${cell[5]===null?'Воздух: время наблюдения неизвестно':`Наблюдался ${cell[5]} с назад`}`:`X ${x} · Z ${z}\nНет данных в выбранном виде`;}drag=null;};
canvas.onwheel=e=>{e.preventDefault();zoom=Math.max(2,Math.min(28,zoom*(e.deltaY<0?1.15:1/1.15)));draw();};
$('center').onclick=()=>{center=null;first=true;refresh();};$('refresh').onclick=refresh;
$('bot').onchange=()=>{center=null;first=true;data=null;refresh();};$('mode').onchange=refresh;$('height').onchange=refresh;$('filter').oninput=objects;
new ResizeObserver(draw).observe(canvas);setInterval(()=>{if($('live').checked&&!document.hidden)refresh();},3000);refresh();
