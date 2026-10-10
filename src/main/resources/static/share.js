import { catalogs, languageNames } from './localizations.js';
import { createRefreshLoop, sessionPresentation } from '../model.mjs';

const node = id => document.getElementById(id);
const text = (id, value) => { if (node(id).textContent !== value) node(id).textContent = value; };
let language = navigator.languages?.map(p => p.slice(0, 2)).find(l => catalogs[l]) ?? 'en';
let result;
let receivedAt = 0;
let errorKey;
const translate = (key, args = []) => (catalogs[language]?.[key] ?? catalogs.en[key] ?? key)
  .replace(/\{(\d+)\}/g, (_, n) => String(args[Number(n)] ?? `{${n}}`));
const time = value => new Intl.DateTimeFormat(language, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value));
const clockTime = value => new Intl.DateTimeFormat(language, { hour: 'numeric', minute: '2-digit' }).format(new Date(value));
const number = value => new Intl.NumberFormat(language, { useGrouping: false }).format(value);
const systemTheme = matchMedia('(prefers-color-scheme: dark)');
const isDark = () => document.documentElement.dataset.theme ? document.documentElement.dataset.theme === 'dark' : systemTheme.matches;
const duration = seconds => {
  const minutes = Math.max(0, Math.ceil(seconds / 60));
  return minutes >= 60 ? translate(Math.floor(minutes / 60) === 1 ? 'duration.singleHour' : '{0} h {1} min', [number(Math.floor(minutes / 60)), number(minutes % 60)]) : translate('{0} min', [number(minutes)]);
};
const elapsed = seconds => {
  const total = Math.max(0, Math.floor(seconds));
  const pad = value => new Intl.NumberFormat(language, { useGrouping: false, minimumIntegerDigits: 2 }).format(value);
  return total >= 3600 ? `${number(Math.floor(total / 3600))}:${pad(Math.floor(total % 3600 / 60))}:${pad(total % 60)}` : `${pad(Math.floor(total / 60))}:${pad(total % 60)}`;
};
const icons = {
  washer: '<rect x="12" y="5" width="72" height="86" rx="10"/><path d="M12 26h72M23 16h14"/><circle cx="48" cy="58" r="23"/><path d="M26 58c12-15 30 15 44 0"/>',
  dryer: '<rect x="12" y="5" width="72" height="86" rx="10"/><path d="M12 26h72M23 16h14"/><circle cx="48" cy="58" r="23"/><path d="M36 48h24M34 58h28M36 68h24"/>',
  dishwasher: '<rect x="12" y="5" width="72" height="86" rx="10"/><path d="M12 26h72M28 17h39M35 42h26M23 77h50"/>',
  oven: '<rect x="8" y="8" width="80" height="80" rx="9"/><rect x="21" y="35" width="54" height="40" rx="5"/><path d="M22 23h1M48 23h1M74 23h1"/>',
  hob: '<rect x="7" y="7" width="82" height="82" rx="12"/><circle cx="30" cy="30" r="13"/><circle cx="66" cy="30" r="10"/><circle cx="30" cy="66" r="10"/><circle cx="66" cy="66" r="13"/>',
  custom: '<path d="M30 22v22M62 22v22M22 44h48v13a24 24 0 0 1-48 0zM46 81v12"/>',
  finished: '<circle cx="48" cy="48" r="35"/><path d="m30 48 12 12 25-25"/>',
  canceled: '<circle cx="48" cy="48" r="35"/><path d="m36 36 24 24m0-24-24 24"/>',
};
for (const [value, name] of Object.entries(languageNames)) {
  const option = document.createElement('option'); option.value = value; option.textContent = name; node('language').append(option);
}
node('language').value = language;
function labels() {
  document.documentElement.lang = language;
  text('refresh', translate('share.refresh')); text('badge', translate('share.live'));
  text('hint', translate('share.snapshotHint')); text('footer', translate('share.browserFooter'));
  node('language').setAttribute('aria-label', translate('share.language'));
  node('theme').setAttribute('aria-label', translate(isDark() ? 'appearance.light' : 'appearance.dark'));
  node('theme').innerHTML = `<svg viewBox="0 0 24 24" aria-hidden="true">${isDark() ? '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.4 1.4m11.2 11.2L19 19M5 19l1.4-1.4M17.6 6.4 19 5"/>' : '<path d="M20.8 13A9 9 0 0 1 11 3.2 9 9 0 1 0 20.8 13Z"/>'}</svg>`;
}
const serverNow = () => Date.parse(result.serverTime) + Math.max(0, performance.now() - receivedAt);
function renderTimer() {
  if (!result) return;
  const s = result.snapshot;
  const now = serverNow();
  if (Date.parse(result.expiresAt) <= now) { result = null; errorKey = 'share.expiredHint'; render(); return; }
  const view = sessionPresentation(s, now);
  const finished = ['finished', 'collected'].includes(view.status);
  node('card').dataset.state = view.status;
  node('card').dataset.mode = s.timingMode;
  node('progress').setAttribute('stroke-dashoffset', String(691.1504 * (1 - view.progress)));
  const iconKey = finished ? 'finished' : view.status === 'canceled' ? 'canceled' : s.kind;
  if (node('art').dataset.icon !== iconKey) {
    node('art').innerHTML = `<svg viewBox="0 0 96 96">${icons[iconKey] ?? icons.custom}</svg>`;
    node('art').dataset.icon = iconKey;
  }
  const key = view.status === 'collected' && ['washer', 'dryer', 'dishwasher'].includes(s.kind) ? 'share.collected'
    : finished ? ({ washer: 'Rufele sunt gata!', dryer: 'Rufele sunt uscate!', dishwasher: 'Vasele sunt curate!' }[s.kind] ?? 'Sesiune încheiată!')
      : ({ running: s.timingMode === 'stopwatch' ? 'Cronometru pornit' : 'Program în desfășurare', due: 'De verificat', canceled: 'Oprită' }[view.status] ?? 'Încheiată');
  const timerLabel = view.status === 'due' ? 'overdue.elapsed' : finished || view.status === 'canceled' ? 'Durată reală' : s.expectedEnd ? 'share.remaining' : 'timp scurs';
  const timer = view.status === 'due' ? `+${elapsed(view.overdueSeconds)}` : elapsed(finished || view.status === 'canceled' ? view.elapsedSeconds : s.expectedEnd ? view.remainingSeconds : view.elapsedSeconds);
  text('timer', timer); text('timer-label', translate(timerLabel));
  node('timer').dataset.length = timer.length > 10 ? 'extended' : timer.length > 8 ? 'long' : 'normal';
  node('timer').setAttribute('aria-label', `${translate(timerLabel)}: ${timer}`);
  text('status', translate(key));
  text('subtitle', view.status === 'due' ? translate('Durata estimată a trecut.')
    : s.completedAt ? translate('Finalizare confirmată la {0}', [clockTime(s.completedAt)])
      : s.canceledAt ? time(s.canceledAt)
        : s.expectedEnd ? translate('Final estimat la {0}', [clockTime(s.expectedEnd)])
          : translate('timp scurs · închei când ai terminat'));
  text('announcement', translate(key));
}
function render() {
  labels();
  text('error', errorKey && (result || errorKey === 'share.expiredHint') ? translate(errorKey) : '');
  node('timer-panel').hidden = !result;
  node('badge').hidden = !result;
  node('sync-details').hidden = !result;
  if (!result) {
    node('card').dataset.state = errorKey ? 'unavailable' : 'loading';
    text('name', 'DONE.'); text('status', translate(errorKey === 'share.expiredHint' ? 'share.expired' : errorKey ? 'share.networkError' : 'share.loading'));
    text('subtitle', ''); text('updated', ''); node('facts').replaceChildren();
    text('announcement', node('status').textContent);
    document.title = 'DONE.';
    return;
  }
  const s = result.snapshot;
  text('name', s.applianceName);
  const rows = [['Program', s.program.localizationKey ? translate(s.program.localizationKey) : s.program.name], ['Pornit la', time(s.startedAt)]];
  if (s.completedAt || s.canceledAt) rows.push(['Încheiere', time(s.completedAt ?? s.canceledAt)]);
  else if (s.expectedEnd) rows.push(['Durată estimată', duration((Date.parse(s.expectedEnd) - Date.parse(s.startedAt)) / 1000)]);
  node('facts').replaceChildren(...rows.map(([label, value]) => {
    const div = document.createElement('div'), dt = document.createElement('dt'), dd = document.createElement('dd');
    dt.textContent = translate(label); dd.textContent = value; div.append(dt, dd); return div;
  }));
  text('updated', translate('share.captured', [time(result.publishedAt)]));
  document.title = `${s.applianceName} · DONE.`;
  renderTimer();
}
async function refresh() {
  node('refresh').disabled = true;
  node('refresh').setAttribute('aria-busy', 'true');
  try {
    const token = location.pathname.split('/').pop();
    if (!/^[A-Za-z0-9_-]{43}$/.test(token)) throw new Error('expired');
    const response = await fetch(`/api/shares/${token}`, { cache: 'no-store', referrerPolicy: 'no-referrer', signal: AbortSignal.timeout(15000) });
    if (response.status === 404 || response.status === 410) throw new Error('expired');
    if (!response.ok) throw new Error('network');
    result = await response.json(); receivedAt = performance.now(); errorKey = null; render();
  } catch (error) {
    if (error.message === 'expired') result = null;
    errorKey = error.message === 'expired' ? 'share.expiredHint' : 'share.networkError'; render();
  } finally { node('refresh').disabled = false; node('refresh').removeAttribute('aria-busy'); }
}
const loop = createRefreshLoop(refresh);
let ticker = setInterval(renderTimer, 1000);
node('refresh').addEventListener('click', loop.refresh);
node('language').addEventListener('change', () => { language = node('language').value; render(); });
node('theme').addEventListener('click', () => { document.documentElement.dataset.theme = isDark() ? 'light' : 'dark'; labels(); });
systemTheme.addEventListener('change', labels);
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible') { renderTimer(); void loop.refresh(); } });
window.addEventListener('online', () => void loop.refresh());
window.addEventListener('pagehide', () => { loop.stop(); clearInterval(ticker); }, { once: true });
window.addEventListener('pageshow', event => { if (event.persisted) location.reload(); });
render(); void loop.start();
