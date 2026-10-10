export const refreshAfterSeconds = 300;
const kinds = new Set(['washer', 'dryer', 'dishwasher', 'oven', 'hob', 'custom']);
const languages = new Set(['en', 'ro', 'es', 'it', 'fr', 'de', 'pl', 'hi', 'ja']);
const segmenter = new Intl.Segmenter('en', { granularity: 'grapheme' });
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export class ShareError extends Error {
  constructor(code, status = 400) { super(code); this.code = code; this.status = status; }
}
export function validateSnapshot(input, now = Date.now(), catalogKeys = null) {
  const allowed = ['schemaVersion', 'id', 'applianceName', 'kind', 'program', 'timingMode', 'startedAt', 'expectedEnd', 'completedAt', 'collectedAt', 'canceledAt', 'capturedAt', 'language'];
  if (!input || typeof input !== 'object' || Array.isArray(input) || Object.keys(input).some(k => !allowed.includes(k))) throw new ShareError('INVALID_SNAPSHOT');
  const string = (v, max) => typeof v === 'string' && v.trim().length > 0 && [...segmenter.segment(v)].length <= max;
  const date = (value, required = false) => {
    if (value == null && !required) return null;
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z$/.test(value)) throw new ShareError('INVALID_TIMESTAMP');
    const n = Date.parse(value);
    if (!Number.isFinite(n) || n < 0 || n > 4_102_444_800_000 || new Date(n).toISOString().slice(0, 19) !== value.slice(0, 19)) throw new ShareError('INVALID_TIMESTAMP');
    return value;
  };
  if (input.schemaVersion !== 1 || !uuid.test(input.id) || !string(input.applianceName, 40) || !kinds.has(input.kind) || !languages.has(input.language) || !['countdown', 'stopwatch'].includes(input.timingMode)) throw new ShareError('INVALID_SNAPSHOT');
  const p = input.program;
  if (!p || typeof p !== 'object' || Array.isArray(p) || Object.keys(p).some(k => !['name', 'minutes', 'localizationKey', 'isCustom'].includes(k)) || !string(p.name, 60) || !Number.isInteger(p.minutes) || p.minutes < 0 || p.minutes > 1440) throw new ShareError('INVALID_PROGRAM');
  if (p.localizationKey != null && (typeof p.localizationKey !== 'string' || p.localizationKey.length > 200 || (catalogKeys && !catalogKeys.has(p.localizationKey)))) throw new ShareError('INVALID_PROGRAM');
  if (p.isCustom != null && typeof p.isCustom !== 'boolean') throw new ShareError('INVALID_PROGRAM');
  const result = { ...input, program: { name: p.name, minutes: p.minutes, localizationKey: p.localizationKey ?? null, isCustom: p.isCustom ?? p.localizationKey == null } };
  for (const key of ['startedAt', 'expectedEnd', 'completedAt', 'collectedAt', 'canceledAt', 'capturedAt']) result[key] = date(input[key], key === 'startedAt' || key === 'capturedAt');
  const start = Date.parse(result.startedAt), captured = Date.parse(result.capturedAt);
  if (start > captured || captured > now + 300_000) throw new ShareError('INVALID_TIMESTAMP');
  for (const key of ['completedAt', 'canceledAt']) if (result[key] && (Date.parse(result[key]) < start || Date.parse(result[key]) > captured)) throw new ShareError('INVALID_TIMESTAMP');
  if (result.completedAt && result.canceledAt) throw new ShareError('INVALID_SNAPSHOT');
  if (result.collectedAt && (!result.completedAt || Date.parse(result.collectedAt) < Date.parse(result.completedAt) || Date.parse(result.collectedAt) > captured)) throw new ShareError('INVALID_TIMESTAMP');
  if (input.timingMode === 'countdown' ? (p.minutes < 1 || !result.expectedEnd || Date.parse(result.expectedEnd) <= start) : result.expectedEnd !== null) throw new ShareError('INVALID_PROGRAM');
  return result;
}
export function statusOf(snapshot, now = Date.now()) {
  if (snapshot.canceledAt) return 'canceled';
  if (snapshot.collectedAt) return 'collected';
  if (snapshot.completedAt) return 'finished';
  return snapshot.expectedEnd && Date.parse(snapshot.expectedEnd) <= now ? 'due' : 'running';
}
// A browser clock may advance an estimate, but only owner timestamps confirm
// completion. Terminal durations remain frozen, including after collection.
export function sessionPresentation(snapshot, now = Date.now()) {
  const status = statusOf(snapshot, now);
  const start = Date.parse(snapshot.startedAt);
  const stop = snapshot.completedAt ?? snapshot.canceledAt;
  const effectiveNow = stop ? Date.parse(stop) : now;
  const end = snapshot.expectedEnd ? Date.parse(snapshot.expectedEnd) : null;
  return {
    status,
    elapsedSeconds: Math.max(0, (effectiveNow - start) / 1000),
    remainingSeconds: end === null ? null : Math.max(0, (end - effectiveNow) / 1000),
    overdueSeconds: status === 'due' ? Math.max(0, (now - end) / 1000) : null,
    progress: status === 'finished' || status === 'collected' || end === null ? 1 : Math.min(1, Math.max(0, (effectiveNow - start) / (end - start))),
  };
}
export function validateRevision(n) {
  if (!Number.isSafeInteger(n) || n < 0) throw new ShareError('INVALID_REVISION');
  return n;
}

export function createRefreshLoop(refresh, { schedule = setTimeout, cancel = clearTimeout, interval = 300_000 } = {}) {
  let timer, stopped = false, busy = false;
  const run = async () => {
    if (stopped || busy) return;
    cancel(timer); busy = true;
    try { await refresh(); }
    finally { busy = false; if (!stopped) timer = schedule(run, interval); }
  };
  return { start: run, refresh: run, stop() { stopped = true; cancel(timer); } };
}
