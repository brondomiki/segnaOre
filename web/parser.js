// Stesse regole dell'app Android (HoursParser.kt / WorkCalc.kt)

const NUMBER_WORDS = {
  zero: 0, un: 1, uno: 1, una: 1, due: 2, tre: 3, quattro: 4, cinque: 5, sei: 6, sette: 7,
  otto: 8, nove: 9, dieci: 10, undici: 11, dodici: 12, tredici: 13, quattordici: 14,
  quindici: 15, sedici: 16, diciassette: 17, diciotto: 18, diciannove: 19, venti: 20,
  ventuno: 21, ventidue: 22, ventitre: 23, ventiquattro: 24, trenta: 30, quaranta: 40,
  quarantacinque: 45, cinquanta: 50,
};
const ZERO_WORDS = ["ferie", "festa", "festivo", "niente", "nulla", "nessuna", "malattia", "riposo"];
const HOUR_WORDS = new Set(["ore", "ora", "h"]);
const MINUTE_WORDS = new Set(["minuti", "minuto", "min", "m"]);

function tok(raw) {
  if (raw.startsWith("+")) return { t: "A", v: parseFloat(raw.slice(1)) };
  if (/^\d/.test(raw)) {
    if (raw.includes(":")) {
      const [h, m] = raw.split(":");
      return { t: "N", v: parseFloat(h.replace(",", ".")) + parseFloat(m) / 60, frac: true };
    }
    const n = raw.replace(",", ".");
    return { t: "N", v: parseFloat(n), frac: n.includes(".") };
  }
  if (raw in NUMBER_WORDS) return { t: "N", v: NUMBER_WORDS[raw], frac: false };
  return { t: "W", w: raw };
}

const isWord = (x, set) => x.t === "W" && set.has(x.w);

function valid(h) {
  if (isNaN(h) || h < 0 || h > 24) return null;
  return Math.round(h * 100) / 100;
}

export function parseHours(input, fullDay = 8) {
  let t = String(input).toLowerCase().replace(/’/g, "'").replace(/[éè]/g, "e").trim();
  if (!t) return null;
  if (ZERO_WORDS.some((z) => new RegExp(`\\b${z}\\b`).test(t))) return 0;
  if (/\bmezza giornata\b/.test(t)) return fullDay / 2;
  if (/\bgiornata (intera|piena|completa)\b/.test(t) || /\btutto il giorno\b/.test(t)) return fullDay;

  t = t.replace(/\be\s+tre\s+quarti(\s+d'\s*ora)?/g, " +0.75 ")
    .replace(/\be\s+un\s+quarto(\s+d'\s*ora)?/g, " +0.25 ")
    .replace(/\be\s+mezz[ao](\s+ora)?\b/g, " +0.5 ")
    .replace(/\btre\s+quarti\s+d'\s*ora/g, " 0.75 ")
    .replace(/\bun\s+quarto\s+d'\s*ora/g, " 0.25 ")
    .replace(/\bmezz'\s*ora\b|\bmezza\s+ora\b/g, " 0.5 ")
    .replace(/'/g, " ");

  const T = [...t.matchAll(/\+\d+(?:\.\d+)?|\d+(?:[.,]\d+)?(?::\d{1,2})?|[a-zàìòù]+/g)].map((m) => tok(m[0]));
  const k = T.findIndex((x) => x.t === "N");
  if (k < 0) return null;
  const base = T[k];
  let h = base.v;
  let i = k + 1;
  if (i < T.length && isWord(T[i], MINUTE_WORDS)) return valid(h / 60);
  while (i < T.length && isWord(T[i], HOUR_WORDS)) i++;
  if (i < T.length) {
    const n = T[i];
    if (n.t === "A") h += n.v;
    else if (isWord(n, new Set(["e"])) && i + 1 < T.length) {
      const a = T[i + 1];
      if (a.t === "A") h += a.v;
      else if (a.t === "N" && !base.frac && a.v >= 1 && a.v <= 59) h += a.v / 60;
    } else if (n.t === "N" && !base.frac && n.v >= 1 && n.v <= 59) h += n.v / 60;
  }
  return valid(h);
}

const TRAVEL = /\b(viaggio|viaggi|trasferta|trasferte|guida)\b/;
const CONNECTOR = /,|\s+(e|più|piu|poi|piu')\s+/g;
const NUMBER_START = /\b(\d+|zero|un|uno|una|due|tre|quattro|cinque|sei|sette|otto|nove|dieci|undici|dodici|tredici|quattordici|quindici|sedici|diciassette|diciotto|diciannove|venti)\b/g;

/** "otto e due di viaggio" -> { hours: 8, travel: 2 } */
export function parseFull(input, fullDay = 8) {
  const t = String(input).toLowerCase().trim();
  const m = TRAVEL.exec(t);
  if (!m) {
    const h = parseHours(input, fullDay);
    return h === null ? null : { hours: h, travel: null };
  }
  const before = t.slice(0, m.index).trim();
  const after = t.slice(m.index + m[0].length).trim();
  const ta = after ? parseHours(after, fullDay) : null;
  if (ta !== null) return { hours: parseHours(before, fullDay), travel: ta };

  const conn = [...before.matchAll(CONNECTOR)];
  const splits = [];
  conn.filter((c) => /\b(ore|ora|h)\s*$/.test(before.slice(0, c.index)))
    .forEach((c) => splits.push([c.index, c.index + c[0].length]));
  [...conn].reverse().forEach((c) => splits.push([c.index, c.index + c[0].length]));
  [...before.matchAll(NUMBER_START)].slice(1).reverse().forEach((c) => splits.push([c.index, c.index]));
  for (const [e, s] of splits) {
    const w = parseHours(before.slice(0, e), fullDay);
    const tr = parseHours(before.slice(s), fullDay);
    if (w !== null && tr !== null) return { hours: w, travel: tr };
  }
  const tr = parseHours(before, fullDay);
  return tr === null ? null : { hours: null, travel: tr };
}

// ---------- calcolo straordinari ----------

export const ORDINARY_HOURS = 8;

function easterSunday(y) {
  const a = y % 19, b = Math.floor(y / 100), c = y % 100;
  const d = Math.floor(b / 4), e = b % 4, f = Math.floor((b + 8) / 25);
  const g = Math.floor((b - f + 1) / 3);
  const h = (19 * a + b - d - g + 15) % 30;
  const i = Math.floor(c / 4), k = c % 4;
  const l = (32 + 2 * e + 2 * i - h - k) % 7;
  const m = Math.floor((a + 11 * h + 22 * l) / 451);
  const month = Math.floor((h + l - 7 * m + 114) / 31);
  const day = ((h + l - 7 * m + 114) % 31) + 1;
  return new Date(Date.UTC(y, month - 1, day, 12));
}

export function isHoliday(date) {
  const md = date.slice(5);
  if (["01-01", "01-06", "04-25", "05-01", "06-02", "08-15", "11-01", "12-08", "12-25", "12-26"].includes(md)) return true;
  const e = easterSunday(Number(date.slice(0, 4)));
  e.setUTCDate(e.getUTCDate() + 1);
  return e.toISOString().slice(0, 10) === date;
}

export function dayOfWeek(date) {
  return new Date(date + "T12:00:00Z").getUTCDay();
}

export function split(date, hours) {
  const dow = dayOfWeek(date);
  if (dow === 0 || isHoliday(date)) return { ord: 0, extra: 0, fest: hours };
  if (dow === 6) return { ord: 0, extra: hours, fest: 0 };
  return { ord: Math.min(hours, ORDINARY_HOURS), extra: Math.max(hours - ORDINARY_HOURS, 0), fest: 0 };
}

export function summarize(entries) {
  const s = { days: 0, worked: 0, ord: 0, extra: 0, fest: 0, travel: 0 };
  for (const e of entries) {
    const c = split(e.date, e.hours);
    if (e.hours > 0) s.days++;
    s.worked += e.hours;
    s.ord += c.ord;
    s.extra += c.extra;
    s.fest += c.fest;
    s.travel += e.travel_hours;
  }
  return s;
}
