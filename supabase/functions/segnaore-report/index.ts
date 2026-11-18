// SegnaOre: genera il report PDF delle ore e lo invia via email (Resend) o lo restituisce.
//
// Modalità:
//  - cron:   header "x-cron-secret" (verificato sul Vault) -> invia il report del mese precedente
//            a tutti gli utenti con report_enabled = true
//  - utente: header "Authorization: Bearer <jwt utente>"
//            body { action: "send" | "download", month?: "YYYY-MM", from?: "YYYY-MM-DD", to?: "YYYY-MM-DD" }

import { createClient } from "npm:@supabase/supabase-js@2";
import { PDFDocument, PDFFont, PDFPage, StandardFonts, rgb } from "npm:pdf-lib@1.17.1";
import { encodeBase64 } from "jsr:@std/encoding@1/base64";

const ORDINARY_HOURS = 8;
const MONTHS = [
  "Gennaio", "Febbraio", "Marzo", "Aprile", "Maggio", "Giugno",
  "Luglio", "Agosto", "Settembre", "Ottobre", "Novembre", "Dicembre",
];
const DAYS = ["Dom", "Lun", "Mar", "Mer", "Gio", "Ven", "Sab"];

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type, x-cron-secret",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

const admin = createClient(
  Deno.env.get("SUPABASE_URL")!,
  Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
  { auth: { persistSession: false } },
);

// ---------- calcolo ore ----------

function easterSunday(y: number): string {
  const a = y % 19, b = Math.floor(y / 100), c = y % 100;
  const d = Math.floor(b / 4), e = b % 4, f = Math.floor((b + 8) / 25);
  const g = Math.floor((b - f + 1) / 3);
  const h = (19 * a + b - d - g + 15) % 30;
  const i = Math.floor(c / 4), k = c % 4;
  const l = (32 + 2 * e + 2 * i - h - k) % 7;
  const m = Math.floor((a + 11 * h + 22 * l) / 451);
  const month = Math.floor((h + l - 7 * m + 114) / 31);
  const day = ((h + l - 7 * m + 114) % 31) + 1;
  return `${y}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

function isHoliday(date: string): boolean {
  const md = date.slice(5);
  const fixed = ["01-01", "01-06", "04-25", "05-01", "06-02", "08-15", "11-01", "12-08", "12-25", "12-26"];
  if (fixed.includes(md)) return true;
  const y = Number(date.slice(0, 4));
  const easter = new Date(easterSunday(y) + "T12:00:00Z");
  easter.setUTCDate(easter.getUTCDate() + 1); // Pasquetta
  return easter.toISOString().slice(0, 10) === date;
}

function dayOfWeek(date: string): number {
  return new Date(date + "T12:00:00Z").getUTCDay();
}

/** Lun-ven: fino a 8 ordinarie, il resto straordinario. Sabato: tutto straordinario.
 *  Domenica e festivi nazionali: tutto straordinario festivo. */
function classify(date: string, hours: number) {
  const dow = dayOfWeek(date);
  if (dow === 0 || isHoliday(date)) return { ord: 0, extra: 0, fest: hours };
  if (dow === 6) return { ord: 0, extra: hours, fest: 0 };
  return { ord: Math.min(hours, ORDINARY_HOURS), extra: Math.max(hours - ORDINARY_HOURS, 0), fest: 0 };
}

type Entry = { date: string; hours: number; travel_hours: number; note: string | null };

function summarize(entries: Entry[]) {
  const s = { days: 0, worked: 0, ord: 0, extra: 0, fest: 0, travel: 0 };
  for (const e of entries) {
    const c = classify(e.date, e.hours);
    if (e.hours > 0) s.days++;
    s.worked += e.hours;
    s.ord += c.ord;
    s.extra += c.extra;
    s.fest += c.fest;
    s.travel += e.travel_hours;
  }
  return s;
}

const fmt = (n: number) =>
  n === 0 ? "0" : n.toLocaleString("it-IT", { minimumFractionDigits: 0, maximumFractionDigits: 2 });

// ---------- PDF ----------

// Helvetica (WinAnsi) non supporta tutti i caratteri: sostituisce quelli non stampabili
function safe(text: string): string {
  return text
    .replace(/[‘’]/g, "'")
    .replace(/[“”]/g, '"')
    .replace(/[–—]/g, "-")
    .replace(/[^\x20-\x7E\xA0-\xFF]/g, "");
}

function fit(text: string, font: PDFFont, size: number, max: number): string {
  let t = safe(text);
  while (t.length > 0 && font.widthOfTextAtSize(t, size) > max) t = t.slice(0, -1);
  return t.length < safe(text).length ? t.slice(0, -1) + "..." : t;
}

async function buildPdf(title: string, subtitle: string, entries: Entry[]): Promise<Uint8Array> {
  const pdf = await PDFDocument.create();
  const font = await pdf.embedFont(StandardFonts.Helvetica);
  const bold = await pdf.embedFont(StandardFonts.HelveticaBold);
  const s = summarize(entries);
  const blue = rgb(0.12, 0.37, 0.68);
  const grey = rgb(0.4, 0.4, 0.4);

  let page: PDFPage = pdf.addPage([595, 842]);
  let y = 790;
  const left = 40;

  page.drawText(safe(title), { x: left, y, size: 20, font: bold, color: blue });
  y -= 22;
  page.drawText(safe(subtitle), { x: left, y, size: 10, font, color: grey });
  y -= 30;

  // Riepilogo
  const boxes: [string, string][] = [
    ["Giorni lavorati", String(s.days)],
    ["Ore lavorate", fmt(s.worked)],
    ["Ordinarie", fmt(s.ord)],
    ["Straordinarie", fmt(s.extra)],
    ["Festive", fmt(s.fest)],
    ["Viaggio", fmt(s.travel)],
  ];
  const bw = (595 - 2 * left - 5 * 8) / 6;
  boxes.forEach(([label, value], i) => {
    const x = left + i * (bw + 8);
    page.drawRectangle({ x, y: y - 44, width: bw, height: 50, color: rgb(0.93, 0.95, 0.99) });
    page.drawText(label, { x: x + 6, y: y - 8, size: 8, font, color: grey });
    page.drawText(value, { x: x + 6, y: y - 32, size: 16, font: bold });
  });
  y -= 72;

  const cols = [
    { h: "Data", x: left, w: 62 },
    { h: "Giorno", x: left + 64, w: 40 },
    { h: "Ore", x: left + 106, w: 40 },
    { h: "Ord.", x: left + 148, w: 40 },
    { h: "Straord.", x: left + 190, w: 48 },
    { h: "Festive", x: left + 240, w: 44 },
    { h: "Viaggio", x: left + 286, w: 44 },
    { h: "Note", x: left + 332, w: 183 },
  ];
  const header = () => {
    page.drawRectangle({ x: left - 4, y: y - 5, width: 595 - 2 * left + 8, height: 18, color: blue });
    cols.forEach((c) => page.drawText(c.h, { x: c.x, y, size: 9, font: bold, color: rgb(1, 1, 1) }));
    y -= 18;
  };
  header();

  const sorted = [...entries].sort((a, b) => a.date.localeCompare(b.date));
  sorted.forEach((e, idx) => {
    if (y < 60) {
      page = pdf.addPage([595, 842]);
      y = 800;
      header();
    }
    if (idx % 2 === 1) {
      page.drawRectangle({ x: left - 4, y: y - 4, width: 595 - 2 * left + 8, height: 15, color: rgb(0.96, 0.96, 0.96) });
    }
    const c = classify(e.date, e.hours);
    const [yy, mm, dd] = e.date.split("-");
    const cells = [
      `${dd}/${mm}/${yy}`, DAYS[dayOfWeek(e.date)], fmt(e.hours), fmt(c.ord),
      fmt(c.extra), fmt(c.fest), fmt(e.travel_hours), e.note ?? "",
    ];
    cells.forEach((v, i) =>
      page.drawText(fit(v, font, 9, cols[i].w), { x: cols[i].x, y, size: 9, font })
    );
    y -= 15;
  });

  if (sorted.length === 0) {
    page.drawText("Nessuna ora registrata nel periodo.", { x: left, y, size: 10, font, color: grey });
  }

  const pages = pdf.getPages();
  pages.forEach((p, i) =>
    p.drawText(
      `SegnaOre - lun-ven 8 ore ordinarie, sabato straordinario, domenica e festivi festive - pag. ${i + 1}/${pages.length}`,
      { x: left, y: 25, size: 7, font, color: grey },
    )
  );
  return await pdf.save();
}

// ---------- dati ed email ----------

function monthRange(month: string): { from: string; to: string } {
  const [y, m] = month.split("-").map(Number);
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return { from: `${month}-01`, to: `${month}-${String(last).padStart(2, "0")}` };
}

function previousMonth(): string {
  const d = new Date();
  d.setUTCDate(1);
  d.setUTCMonth(d.getUTCMonth() - 1);
  return d.toISOString().slice(0, 7);
}

function itDate(d: string) {
  const [y, m, dd] = d.split("-");
  return `${dd}/${m}/${y}`;
}

async function loadEntries(userId: string, from: string, to: string): Promise<Entry[]> {
  const { data, error } = await admin
    .from("segnaore_entries")
    .select("date, hours, travel_hours, note")
    .eq("user_id", userId)
    .eq("deleted", false)
    .gte("date", from)
    .lte("date", to)
    .order("date");
  if (error) throw error;
  return (data ?? []).map((r) => ({
    date: r.date,
    hours: Number(r.hours),
    travel_hours: Number(r.travel_hours),
    note: r.note,
  }));
}

async function sendEmail(to: string, subject: string, html: string, filename: string, pdf: Uint8Array) {
  const key = Deno.env.get("RESEND_API_KEY");
  if (!key) throw new Error("RESEND_API_KEY non configurata");
  const res = await fetch("https://api.resend.com/emails", {
    method: "POST",
    headers: { Authorization: `Bearer ${key}`, "Content-Type": "application/json" },
    body: JSON.stringify({
      from: Deno.env.get("REPORT_FROM") ?? "SegnaOre <onboarding@resend.dev>",
      to: [to],
      subject,
      html,
      attachments: [{ filename, content: encodeBase64(pdf) }],
    }),
  });
  if (!res.ok) throw new Error(`Resend ${res.status}: ${await res.text()}`);
}

async function report(userId: string, email: string, from: string, to: string, label: string) {
  const entries = await loadEntries(userId, from, to);
  const s = summarize(entries);
  const pdf = await buildPdf(
    `Report ore - ${label}`,
    `Periodo ${itDate(from)} - ${itDate(to)}  |  ${email}`,
    entries,
  );
  const html = `
    <h2 style="color:#1f5fad">Report ore - ${label}</h2>
    <table cellpadding="6" style="border-collapse:collapse;font-family:sans-serif">
      <tr><td>Giorni lavorati</td><td><b>${s.days}</b></td></tr>
      <tr><td>Ore lavorate</td><td><b>${fmt(s.worked)}</b></td></tr>
      <tr><td>Ordinarie</td><td><b>${fmt(s.ord)}</b></td></tr>
      <tr><td>Straordinarie (feriali + sabato)</td><td><b>${fmt(s.extra)}</b></td></tr>
      <tr><td>Festive (domenica e festivi)</td><td><b>${fmt(s.fest)}</b></td></tr>
      <tr><td>Viaggio / trasferta</td><td><b>${fmt(s.travel)}</b></td></tr>
    </table>
    <p style="color:#666;font-family:sans-serif">In allegato il dettaglio giorno per giorno.</p>`;
  return { pdf, html, summary: s };
}

function labelFor(from: string, to: string, month?: string) {
  if (month) {
    const [y, m] = month.split("-").map(Number);
    return `${MONTHS[m - 1]} ${y}`;
  }
  return `${itDate(from)} - ${itDate(to)}`;
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, "Content-Type": "application/json" },
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });

  try {
    const body = await req.json().catch(() => ({}));

    // --- invio automatico mensile ---
    const cronHeader = req.headers.get("x-cron-secret");
    if (cronHeader) {
      const { data: ok } = await admin.rpc("segnaore_check_cron", { secret: cronHeader });
      if (ok !== true) return json({ error: "Non autorizzato" }, 401);
      const month = body.month ?? previousMonth();
      const { from, to } = monthRange(month);
      const label = labelFor(from, to, month);
      const { data: users, error } = await admin
        .from("segnaore_settings")
        .select("user_id, report_email")
        .eq("report_enabled", true);
      if (error) throw error;
      const results = [];
      for (const u of users ?? []) {
        try {
          const r = await report(u.user_id, u.report_email, from, to, label);
          await sendEmail(u.report_email, `Report ore ${label}`, r.html, `ore-${month}.pdf`, r.pdf);
          results.push({ user: u.user_id, ok: true });
        } catch (e) {
          results.push({ user: u.user_id, ok: false, error: String(e) });
        }
      }
      return json({ month, results });
    }

    // --- richiesta dell'utente ---
    const jwt = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
    const { data: auth, error: authErr } = await admin.auth.getUser(jwt);
    if (authErr || !auth.user) return json({ error: "Non autorizzato" }, 401);
    const user = auth.user;

    const month: string | undefined = body.from ? undefined : (body.month ?? new Date().toISOString().slice(0, 7));
    const range = month ? monthRange(month) : { from: body.from, to: body.to ?? body.from };
    const label = labelFor(range.from, range.to, month);

    const { data: settings } = await admin
      .from("segnaore_settings")
      .select("report_email")
      .eq("user_id", user.id)
      .maybeSingle();
    const email = settings?.report_email ?? user.email ?? "";

    const r = await report(user.id, email, range.from, range.to, label);
    const filename = `ore-${month ?? `${range.from}_${range.to}`}.pdf`;

    if (body.action === "download") {
      return new Response(r.pdf, {
        headers: {
          ...cors,
          "Content-Type": "application/pdf",
          "Content-Disposition": `attachment; filename="${filename}"`,
        },
      });
    }

    await sendEmail(email, `Report ore ${label}`, r.html, filename, r.pdf);
    return json({ ok: true, sentTo: email, summary: r.summary });
  } catch (e) {
    return json({ error: String(e instanceof Error ? e.message : e) }, 500);
  }
});
