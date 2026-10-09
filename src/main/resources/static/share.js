import { catalogs, languageNames } from './localizations.js';
import { createRefreshLoop } from '../model.mjs';

const node = id => document.getElementById(id);
let language = navigator.languages?.map(p => p.slice(0,2)).find(l => catalogs[l]) ?? 'en';
let result;
let errorKey;
const translate = (key, args=[]) => (catalogs[language]?.[key] ?? catalogs.en[key] ?? key).replace(/\{(\d+)\}/g, (_, n) => String(args[Number(n)] ?? `{${n}}`));
const time = value => new Intl.DateTimeFormat(language, {dateStyle:'medium',timeStyle:'short'}).format(new Date(value));
const duration = seconds => {
  const minutes = Math.max(0, Math.ceil(seconds/60));
  return minutes >= 60 ? translate(Math.floor(minutes/60) === 1 ? 'duration.singleHour' : '{0} h {1} min',[Math.floor(minutes/60),minutes%60]) : translate('{0} min',[minutes]);
};
const icons = {
  washer:'<rect x="12" y="5" width="72" height="86" rx="10"/><path d="M12 26h72M23 16h14"/><circle cx="48" cy="58" r="23"/><path d="M26 58c12-15 30 15 44 0"/>',
  dryer:'<rect x="12" y="5" width="72" height="86" rx="10"/><path d="M12 26h72M23 16h14"/><circle cx="48" cy="58" r="23"/><path d="M36 48h24M34 58h28M36 68h24"/>',
  dishwasher:'<rect x="12" y="5" width="72" height="86" rx="10"/><path d="M12 26h72M28 17h39M35 42h26M23 77h50"/>',
  oven:'<rect x="8" y="8" width="80" height="80" rx="9"/><rect x="21" y="35" width="54" height="40" rx="5"/><path d="M22 23h1M48 23h1M74 23h1"/>',
  hob:'<rect x="7" y="7" width="82" height="82" rx="12"/><circle cx="30" cy="30" r="13"/><circle cx="66" cy="30" r="10"/><circle cx="30" cy="66" r="10"/><circle cx="66" cy="66" r="13"/>',
  custom:'<path d="M30 22v22M62 22v22M22 44h48v13a24 24 0 0 1-48 0zM46 81v12"/>',
};
for (const [value, name] of Object.entries(languageNames)) {
  const option=document.createElement('option'); option.value=value; option.textContent=name; node('language').append(option);
}
node('language').value=language;
function labels() {
  document.documentElement.lang=language;
  node('refresh').textContent=translate('share.refresh'); node('badge').textContent=translate('share.live');
  node('hint').textContent=translate('share.snapshotHint'); node('footer').textContent=translate('share.browserFooter');
  node('language').setAttribute('aria-label',translate('share.language'));
}
function render() {
  labels();
  node('error').textContent=errorKey ? translate(errorKey) : '';
  if (!result) {
    node('status').textContent=translate(errorKey==='share.expiredHint' ? 'share.expired' : 'share.loading');
    return;
  }
  const s=result.snapshot;
  node('name').textContent=s.applianceName;
  node('art').innerHTML=`<svg viewBox="0 0 96 96">${icons[s.kind] ?? icons.custom}</svg>`;
  const finished=s.completedAt && !s.canceledAt;
  const key=result.status==='collected' && ['washer','dryer','dishwasher'].includes(s.kind) ? 'share.collected' : finished ? ({washer:'Rufele sunt gata!',dryer:'Rufele sunt uscate!',dishwasher:'Vasele sunt curate!'}[s.kind] ?? 'Sesiune încheiată!') : ({running:'În desfășurare',due:'De verificat',canceled:'Oprită'}[result.status] ?? 'Încheiată');
  node('status').textContent=translate(key);
  node('subtitle').textContent=result.status==='running' && s.expectedEnd ? translate('{0} rămase',[duration((Date.parse(s.expectedEnd)-Date.parse(result.serverTime))/1000)]) : '';
  const rows=[['Program',s.program.localizationKey ? translate(s.program.localizationKey) : s.program.name],['Pornit la',time(s.startedAt)]];
  if (s.completedAt || s.canceledAt) rows.push(['Încheiere',time(s.completedAt ?? s.canceledAt)]);
  if (s.expectedEnd && result.status==='running') rows.push(['Estimare finală',time(s.expectedEnd)]);
  const elapsed=Math.max(0,(Date.parse(s.completedAt ?? s.canceledAt ?? result.serverTime)-Date.parse(s.startedAt))/1000);
  rows.push(['Durată reală',duration(elapsed)]);
  node('facts').replaceChildren(...rows.map(([label,value])=>{
    const div=document.createElement('div'), dt=document.createElement('dt'), dd=document.createElement('dd');
    dt.textContent=translate(label); dd.textContent=value; div.append(dt,dd); return div;
  }));
  node('updated').textContent=translate('share.captured',[time(result.publishedAt)]);
  document.title=`${s.applianceName} · DONE.`;
}
async function refresh() {
  node('refresh').disabled=true;
  try {
    const token=location.pathname.split('/').pop();
    if (!/^[A-Za-z0-9_-]{43}$/.test(token)) throw new Error('expired');
    const response=await fetch(`/api/shares/${token}`,{cache:'no-store',referrerPolicy:'no-referrer',signal:AbortSignal.timeout(15000)});
    if (response.status===404 || response.status===410) throw new Error('expired');
    if (!response.ok) throw new Error('network');
    result=await response.json(); errorKey=null; render();
  } catch (error) {
    if (error.message==='expired') { result=null; node('name').textContent='DONE.'; node('status').textContent=translate('share.expired'); node('facts').replaceChildren(); node('subtitle').textContent=''; node('updated').textContent=''; node('art').replaceChildren(); }
    errorKey=error.message==='expired' ? 'share.expiredHint' : 'share.networkError'; render();
  } finally { node('refresh').disabled=false; }
}
const loop=createRefreshLoop(refresh);
node('refresh').addEventListener('click',loop.refresh);
node('language').addEventListener('change',()=>{language=node('language').value;render();});
document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible') void loop.refresh();});
window.addEventListener('online',()=>void loop.refresh());
window.addEventListener('pagehide',()=>loop.stop(),{once:true});
window.addEventListener('pageshow',event=>{if(event.persisted) location.reload();});
labels(); node('status').textContent=translate('share.loading'); void loop.start();
