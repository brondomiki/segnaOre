import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { parseHours, parseFull, split, summarize, dayOfWeek } from "./parser.js";

const SUPABASE_URL = "https://ehwysdqadddelbdielfu.supabase.co";
const SUPABASE_KEY = "sb_publishable_kHGaX6xC6znNfWGrFxmtnw_6guDWLrT";
const FULL_DAY = 8;
const MONTHS = ["Gennaio", "Febbraio", "Marzo", "Aprile", "Maggio", "Giugno", "Luglio", "Agosto", "Settembre", "Ottobre", "Novembre", "Dicembre"];
const DAYS = ["Dom", "Lun", "Mar", "Mer", "Gio", "Ven", "Sab"];

const sb = createClient(SUPABASE_URL, SUPABASE_KEY);
const $ = (id) => document.getElementById(id);

let user = null;
let entries = []; // { date, hours, travel_hours, note }

// ---------- utilità ----------

const fmt = (n) => (Number(n) || 0).toLocaleString("it-IT", { maximumFractionDigits: 2 });
const iso = (d) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
const itDate = (s) => s.split("-").reverse().join("/");
const today = () => iso(new Date());

function monthBounds(y, m) {
  return [iso(new Date(y, m, 1)), iso(new Date(y, m + 1, 0))];
}

function msg(el, text, kind = "") {
  el.textContent = text;
  el.className = "msg " + kind;
  if (text && kind !== "err") setTimeout(() => { if (el.textContent === text) el.textContent = ""; }, 4000);
}

function download(blob, name) {
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = name;
  a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 2000);
}

// ---------- accesso ----------

async function showSession(session) {
  user = session?.user ?? null;
  $("loginView").classList.toggle("hidden", !!user);
  $("appView").classList.toggle("hidden", !user);
  $("userBox").classList.toggle("hidden", !user);
  if (!user) return;
  $("userEmail").textContent = user.email;
  await ensureSettings();
  await load();
}

$("loginForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  msg($("loginMsg"), "Accesso…");
  const { error } = await sb.auth.signInWithPassword({ email: $("loginEmail").value.trim(), password: $("loginPassword").value });
  if (error) msg($("loginMsg"), traduci(error.message), "err");
});

$("signupBtn").addEventListener("click", async () => {
  const email = $("loginEmail").value.trim();
  const password = $("loginPassword").value;
  if (password.length < 6) return msg($("loginMsg"), "La password deve avere almeno 6 caratteri", "err");
  const { data, error } = await sb.auth.signUp({ email, password, options: { emailRedirectTo: location.origin } });
  if (error) return msg($("loginMsg"), traduci(error.message), "err");
  if (!data.session) msg($("loginMsg"), "Account creato: conferma l'email che ti è arrivata, poi accedi.", "ok");
});

$("logoutBtn").addEventListener("click", () => sb.auth.signOut());

function traduci(m) {
  if (/Invalid login/i.test(m)) return "Email o password errate";
  if (/not confirmed/i.test(m)) return "Conferma prima l'email (controlla la posta)";
  if (/already registered/i.test(m)) return "Email già registrata: usa Accedi";
  return m;
}

sb.auth.onAuthStateChange((_ev, session) => {
  // evita di ricaricare a ogni rinnovo del token
  if ((session?.user?.id ?? null) !== (user?.id ?? null)) showSession(session);
});
sb.auth.getSession().then(({ data }) => showSession(data.session));

// ---------- dati ----------

async function load() {
  const { data, error } = await sb
    .from("segnaore_entries")
    .select("date, hours, travel_hours, note")
    .eq("deleted", false)
    .order("date", { ascending: false });
  if (error) return msg($("actionMsg"), error.message, "err");
  entries = data.map((r) => ({ ...r, hours: Number(r.hours), travel_hours: Number(r.travel_hours) }));
  render();
}

async function save(date, hours, travel, note) {
  const { error } = await sb.from("segnaore_entries").upsert({
    user_id: user.id, date, hours, travel_hours: travel, note: note || null,
    deleted: false, updated_ms: Date.now(),
  }, { onConflict: "user_id,date" });
  if (error) throw error;
  await load();
}

async function remove(date) {
  const { error } = await sb.from("segnaore_entries")
    .update({ deleted: true, updated_ms: Date.now() })
    .eq("user_id", user.id).eq("date", date);
  if (error) return msg($("actionMsg"), error.message, "err");
  await load();
}

async function ensureSettings() {
  await sb.from("segnaore_settings").upsert({ user_id: user.id }, { onConflict: "user_id", ignoreDuplicates: true });
  const { data } = await sb.from("segnaore_settings").select("report_email, report_enabled").eq("user_id", user.id).maybeSingle();
  if (data) {
    $("reportEmail").value = data.report_email;
    $("reportEnabled").checked = data.report_enabled;
  }
}

// ---------- inserimento ----------

$("fDate").value = today();

$("entryForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const hours = parseHours($("fHours").value, FULL_DAY);
  const travelText = $("fTravel").value.trim();
  const travel = travelText ? parseHours(travelText, FULL_DAY) : 0;
  if (hours === null) return msg($("formMsg"), "Ore non valide", "err");
  if (travel === null) return msg($("formMsg"), "Ore di viaggio non valide", "err");
  await submit($("fDate").value, hours, travel, $("fNote").value.trim());
});

async function submit(date, hours, travel, note) {
  try {
    await save(date, hours, travel, note);
    msg($("formMsg"), `Salvato: ${fmt(hours)} ore${travel ? ` + ${fmt(travel)} viaggio` : ""} il ${itDate(date)}`, "ok");
    $("fHours").value = "";
    $("fTravel").value = "";
    $("fNote").value = "";
  } catch (err) {
    msg($("formMsg"), err.message, "err");
  }
}

$("fullDayBtn").addEventListener("click", () => {
  const ex = entries.find((x) => x.date === $("fDate").value);
  submit($("fDate").value, FULL_DAY, ex?.travel_hours ?? 0, $("fNote").value.trim());
});
$("ferieBtn").addEventListener("click", () => submit($("fDate").value, 0, 0, "ferie"));

// ---------- voce (Chrome / Edge / Safari) ----------

const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
if (!Recognition) {
  $("micBtn").classList.add("hidden");
} else {
  const rec = new Recognition();
  rec.lang = "it-IT";
  rec.interimResults = false;
  rec.maxAlternatives = 3;
  rec.onstart = () => $("micBtn").classList.add("listening");
  rec.onend = () => $("micBtn").classList.remove("listening");
  rec.onerror = (e) => msg($("formMsg"), "Microfono: " + e.error, "err");
  rec.onresult = async (e) => {
    const alts = [...e.results[0]].map((a) => a.transcript);
    const heard = alts[0];
    const parsed = alts.map((a) => parseFull(a, FULL_DAY)).find((p) => p);
    if (!parsed) return msg($("formMsg"), `Non ho capito "${heard}"`, "err");
    const date = $("fDate").value;
    const ex = entries.find((x) => x.date === date);
    const hours = parsed.hours ?? ex?.hours ?? 0;
    const travel = parsed.travel ?? ex?.travel_hours ?? 0;
    await submit(date, hours, travel, heard);
  };
  $("micBtn").addEventListener("click", () => rec.start());
}

// ---------- filtro e riepilogo ----------

function setRange(from, to, chip) {
  $("fromDate").value = from;
  $("toDate").value = to;
  document.querySelectorAll(".chip").forEach((c) => c.classList.toggle("active", c.dataset.range === chip));
  render();
}

document.querySelectorAll(".chip").forEach((c) => c.addEventListener("click", () => {
  const now = new Date();
  if (c.dataset.range === "month") setRange(...monthBounds(now.getFullYear(), now.getMonth()), "month");
  if (c.dataset.range === "prev") setRange(...monthBounds(now.getFullYear(), now.getMonth() - 1), "prev");
  if (c.dataset.range === "year") setRange(`${now.getFullYear()}-01-01`, `${now.getFullYear()}-12-31`, "year");
}));
["fromDate", "toDate"].forEach((id) => $(id).addEventListener("change", () => {
  document.querySelectorAll(".chip").forEach((c) => c.classList.remove("active"));
  if ($("fromDate").value > $("toDate").value) $("toDate").value = $("fromDate").value;
  render();
}));
{
  const now = new Date();
  setRange(...monthBounds(now.getFullYear(), now.getMonth()), "month");
}

function filtered() {
  const f = $("fromDate").value, t = $("toDate").value;
  return entries.filter((e) => e.date >= f && e.date <= t);
}

function render() {
  const list = filtered();
  const s = summarize(list);
  $("stats").innerHTML = [
    ["Giorni", s.days], ["Lavorate", fmt(s.worked)], ["Ordinarie", fmt(s.ord)],
    ["Straordinarie", fmt(s.extra)], ["Festive", fmt(s.fest)], ["Viaggio", fmt(s.travel)],
  ].map(([l, v]) => `<div class="stat"><div class="l">${l}</div><div class="v">${v}</div></div>`).join("");

  $("count").textContent = `(${list.length})`;
  $("empty").classList.toggle("hidden", list.length > 0);
  const tbody = $("rows");
  tbody.innerHTML = "";
  for (const e of list) {
    const c = split(e.date, e.hours);
    const dow = dayOfWeek(e.date);
    const tr = document.createElement("tr");
    if (dow === 0 || dow === 6) tr.className = "weekend";
    tr.innerHTML = `
      <td>${DAYS[dow]} ${itDate(e.date)}</td>
      <td><b>${fmt(e.hours)}</b></td><td>${fmt(c.ord)}</td><td>${fmt(c.extra)}</td>
      <td>${fmt(c.fest)}</td><td>${fmt(e.travel_hours)}</td>
      <td class="note"></td>
      <td><button class="del" title="Elimina">✕</button></td>`;
    tr.querySelector(".note").textContent = e.note ?? "";
    tr.addEventListener("click", () => edit(e));
    tr.querySelector(".del").addEventListener("click", (ev) => {
      ev.stopPropagation();
      if (confirm(`Eliminare le ore del ${itDate(e.date)}?`)) remove(e.date);
    });
    tbody.appendChild(tr);
  }

  // archivio mensile
  const byMonth = {};
  for (const e of entries) (byMonth[e.date.slice(0, 7)] ??= []).push(e);
  $("archive").innerHTML = "";
  Object.keys(byMonth).sort().reverse().forEach((ym) => {
    const m = summarize(byMonth[ym]);
    const [y, mo] = ym.split("-").map(Number);
    const div = document.createElement("div");
    div.className = "month";
    div.innerHTML = `<div><div>${MONTHS[mo - 1]} ${y}</div>
      <div class="sub">${m.days} giorni · straord. ${fmt(m.extra)} · festive ${fmt(m.fest)} · viaggio ${fmt(m.travel)}</div></div>
      <div class="tot">${fmt(m.worked)} ore</div>`;
    div.addEventListener("click", () => {
      setRange(...monthBounds(y, mo - 1), "");
      window.scrollTo({ top: 0, behavior: "smooth" });
    });
    $("archive").appendChild(div);
  });
  if (!entries.length) $("archive").innerHTML = '<p class="muted">Ancora nessun mese registrato.</p>';
}

function edit(e) {
  $("fDate").value = e.date;
  $("fHours").value = fmt(e.hours);
  $("fTravel").value = e.travel_hours ? fmt(e.travel_hours) : "";
  $("fNote").value = e.note ?? "";
  window.scrollTo({ top: 0, behavior: "smooth" });
  $("fHours").focus();
}

// ---------- export ----------

$("csvBtn").addEventListener("click", () => {
  const list = [...filtered()].sort((a, b) => a.date.localeCompare(b.date));
  const n = (v) => fmt(v).replace(/\./g, "");
  const lines = ["data;giorno;ore lavorate;ordinarie;straordinarie;festive;viaggio;nota"];
  for (const e of list) {
    const c = split(e.date, e.hours);
    lines.push([e.date, DAYS[dayOfWeek(e.date)], n(e.hours), n(c.ord), n(c.extra), n(c.fest), n(e.travel_hours),
      (e.note ?? "").replace(/[;\n]/g, " ")].join(";"));
  }
  const s = summarize(list);
  lines.push(["TOTALE", "", n(s.worked), n(s.ord), n(s.extra), n(s.fest), n(s.travel), ""].join(";"));
  download(new Blob(["﻿" + lines.join("\n")], { type: "text/csv;charset=utf-8" }),
    `ore_${$("fromDate").value}_${$("toDate").value}.csv`);
});

async function callReport(action) {
  const { data: { session } } = await sb.auth.getSession();
  const res = await fetch(`${SUPABASE_URL}/functions/v1/segnaore-report`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${session.access_token}`, apikey: SUPABASE_KEY },
    body: JSON.stringify({ action, from: $("fromDate").value, to: $("toDate").value }),
  });
  if (!res.ok) {
    const j = await res.json().catch(() => ({}));
    throw new Error(j.error || `Errore ${res.status}`);
  }
  return res;
}

$("pdfBtn").addEventListener("click", async () => {
  msg($("actionMsg"), "Preparo il PDF…");
  try {
    const res = await callReport("download");
    download(await res.blob(), `ore_${$("fromDate").value}_${$("toDate").value}.pdf`);
    msg($("actionMsg"), "PDF scaricato", "ok");
  } catch (e) {
    msg($("actionMsg"), e.message, "err");
  }
});

$("mailBtn").addEventListener("click", async () => {
  msg($("actionMsg"), "Invio in corso…");
  try {
    const j = await (await callReport("send")).json();
    msg($("actionMsg"), `Report inviato a ${j.sentTo}`, "ok");
  } catch (e) {
    msg($("actionMsg"), e.message, "err");
  }
});

// ---------- impostazioni ----------

$("settingsForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const { error } = await sb.from("segnaore_settings").update({
    report_email: $("reportEmail").value.trim(),
    report_enabled: $("reportEnabled").checked,
    updated_at: new Date().toISOString(),
  }).eq("user_id", user.id);
  msg($("settingsMsg"), error ? error.message : "Salvato", error ? "err" : "ok");
});

// ---------- password ----------

$("pwForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  if ($("newPw").value !== $("newPw2").value) return msg($("pwMsg"), "Le password non coincidono", "err");
  const { error } = await sb.auth.updateUser({ password: $("newPw").value });
  if (error) return msg($("pwMsg"), error.message, "err");
  $("pwForm").reset();
  msg($("pwMsg"), "Password cambiata", "ok");
});
