'use strict';

/* =====================================================================
   First Choice - front end (plain JavaScript, no framework)
   Screens: home, results, seats, passengers, ticket, trips, admin
   ===================================================================== */

/* ---------- small helpers ---------- */
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const enc = encodeURIComponent;
const money = (n) => '₹' + Number(n).toLocaleString('en-IN', { maximumFractionDigits: 2 });
const pad = (n) => String(n).padStart(2, '0');
const ymd = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const parseLocalDate = (s) => {
  if (!s) return new Date();
  const [y, m, d] = String(s).slice(0, 10).split('-').map(Number);
  return new Date(y, m - 1, d);
};
const addDays = (d, n) => { const x = new Date(d); x.setDate(x.getDate() + n); return x; };
const fmtTime = (iso) => new Date(iso).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: true });
const fmtDate = (iso) => new Date(iso).toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short' });
const fmtDateLong = (iso) => parseLocalDate(iso).toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
const duration = (a, b) => { const m = Math.round((new Date(b) - new Date(a)) / 60000); return `${Math.floor(m / 60)} h ${pad(m % 60)} m`; };
const typeLabel = (t) => ({ AC: 'AC', NON_AC: 'Non-AC', SLEEPER: 'Sleeper' }[t] || t);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const store = {
  get(k) { try { return localStorage.getItem(k) || ''; } catch { return ''; } },
  set(k, v) { try { v ? localStorage.setItem(k, v) : localStorage.removeItem(k); } catch { /* storage blocked */ } },
};

/* ---------- state ---------- */
const state = { user: null, token: store.get('fc_token'), cities: [], trip: null, afterLogin: null };
let navId = 0;
const app = $('#app');

function toast(msg, kind = '') {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'show ' + kind;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { t.className = ''; }, 3800);
}

async function api(path, opts = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (state.token) headers.Authorization = 'Bearer ' + state.token;
  let res;
  try {
    res = await fetch('/api' + path, { method: opts.method || 'GET', headers, body: opts.body ? JSON.stringify(opts.body) : undefined });
  } catch {
    throw new Error('Cannot reach the server. Check your connection and try again.');
  }
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 && state.token) setSession(null);
  if (!res.ok) throw new Error(data.error || 'Something went wrong. Please try again.');
  return data;
}

function setSession(s) {
  if (s) {
    state.user = { name: s.name, email: s.email, role: s.role };
    state.token = s.token || state.token;
  } else {
    state.user = null;
    state.token = '';
  }
  store.set('fc_token', state.token);
  renderHeader();
}

/* ---------- header ---------- */
function renderHeader() {
  const here = (location.hash.slice(1) || '/').split('?')[0].split('/')[1] || '';
  const link = (href, label, key) => `<a href="${href}"${here === key ? ' aria-current="page"' : ''}>${label}</a>`;
  const u = state.user;
  $('#top').innerHTML = `
    <div class="top-in">
      <a class="brand" href="#/">
        <svg class="logo" viewBox="0 0 32 32" aria-hidden="true"><rect x="2" y="5" width="28" height="22" rx="6" fill="#0E5A4A"/><rect x="5" y="8" width="22" height="16" rx="3.5" fill="none" stroke="#fff" stroke-width="1.6"/><path d="M9 18h14" stroke="#F4A300" stroke-width="2.4" stroke-linecap="round" stroke-dasharray="4 3"/></svg>
        First Choice
      </a>
      <nav aria-label="Main">
        ${link('#/', 'Book a bus', '')}
        ${link('#/pnr', 'Check PNR', 'pnr')}
        ${link('#/trips', 'My trips', 'trips')}
        ${u && u.role === 'ADMIN' ? link('#/admin', 'Admin', 'admin') : ''}
      </nav>
      <div class="acct">
        ${u ? `<span>Hi, ${esc(u.name.split(' ')[0])}</span><button class="btn small" id="logoutBtn" type="button">Log out</button>`
            : `<button class="btn small primary" id="loginBtn" type="button">Log in</button>`}
      </div>
    </div>`;
  const lo = $('#logoutBtn'), li = $('#loginBtn');
  if (lo) lo.onclick = async () => {
    try { await api('/logout', { method: 'POST' }); } catch { /* ignore */ }
    setSession(null);
    state.trip = null;
    toast('You are logged out.');
    location.hash = '#/';
    route();
  };
  if (li) li.onclick = () => openAuth();
}

/* ---------- auth dialog ---------- */
const dlg = $('#authDialog');
let authMode = 'login';

function setAuthMode(mode) {
  authMode = mode;
  $$('.seg button', dlg).forEach((b) => b.classList.toggle('on', b.dataset.mode === mode));
  $('.reg-only', dlg).hidden = mode !== 'register';
  $('#authTitle').textContent = mode === 'login' ? 'Log in' : 'Create your account';
  $('#authSubmit').textContent = mode === 'login' ? 'Log in' : 'Create account';
  $('input[name=password]', dlg).autocomplete = mode === 'login' ? 'current-password' : 'new-password';
  $('#authErr').textContent = '';
}

function openAuth(afterLogin) {
  state.afterLogin = afterLogin || null;
  setAuthMode('login');
  $('#authForm').reset();
  if (!dlg.open) dlg.showModal();
  $('input[name=email]', dlg).focus();
}

$$('.seg button', dlg).forEach((b) => { b.onclick = () => setAuthMode(b.dataset.mode); });
$('#authForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const f = e.target, err = $('#authErr'), btn = $('#authSubmit');
  const body = { email: f.elements.email.value.trim(), password: f.elements.password.value };
  if (authMode === 'register') body.name = f.elements.name.value.trim();
  if (!body.email || !body.password || (authMode === 'register' && !body.name)) { err.textContent = 'Fill in all the fields.'; return; }
  btn.disabled = true;
  try {
    const s = await api(authMode === 'login' ? '/login' : '/register', { method: 'POST', body });
    setSession(s);
    dlg.close();
    toast(authMode === 'login' ? `Welcome back, ${s.name.split(' ')[0]}.` : 'Account created. You are logged in.');
    const next = state.afterLogin;
    state.afterLogin = null;
    next ? next() : route();
  } catch (ex) {
    err.textContent = ex.message;
  } finally {
    btn.disabled = false;
  }
});

/* ---------- router ---------- */
function route() {
  const id = ++navId;
  renderHeader();
  const [pathPart, queryPart] = (location.hash.slice(1) || '/').split('?');
  const parts = pathPart.split('/').filter(Boolean);
  const q = Object.fromEntries(new URLSearchParams(queryPart || ''));
  const views = {
    undefined: () => home(id),
    pnr: () => pnrView(id),
    results: () => results(q, id),
    seats: () => seats(Number(parts[1]), id),
    passengers: () => passengers(Number(parts[1]), id),
    ticket: () => ticket(parts[1], id),
    trips: () => trips(id),
    admin: () => adminView(parts[1] || 'report', id),
  };
  (views[parts[0]] || views.undefined)();
  window.scrollTo(0, 0);
}
window.addEventListener('hashchange', route);

function pnrView(id) {
  if (id !== navId) return;
  app.innerHTML = `
    <div style="max-width:540px;margin:24px auto 0;">
      <a class="back-link" href="#/">Back to home</a>
      <section class="panel" style="margin-top:16px;">
        <h2>Check your ticket by PNR</h2>
        <p style="color:var(--muted);margin:8px 0 20px;">Enter your 10-character booking PNR to view your ticket, check seat details, or print.</p>
        <form id="pnrSearchPageForm" novalidate>
          <label class="f">Booking PNR
            <input name="pnr" placeholder="e.g. FC..." maxlength="12" style="font-size:1.15rem;letter-spacing:0.06em;font-weight:700;text-transform:uppercase" autofocus required>
          </label>
          <p class="err" id="pnrPageErr" role="alert" style="margin-top:8px;"></p>
          <button class="btn primary wide" type="submit" style="margin-top:14px;">View ticket</button>
        </form>
      </section>
    </div>`;

  $('#pnrSearchPageForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const val = e.target.elements.pnr.value.trim().toUpperCase();
    const err = $('#pnrPageErr');
    if (!val) { err.textContent = 'Please enter your PNR.'; return; }
    location.hash = '#/ticket/' + enc(val);
  });
}

function showError(message, id) {
  if (id !== navId) return;
  app.innerHTML = `<div class="empty"><h2>That did not work</h2><p>${esc(message)}</p><a class="btn primary" href="#/">Back to search</a></div>`;
}

function needLogin(id, what) {
  if (id !== navId) return true;
  if (state.user) return false;
  app.innerHTML = `<div class="empty"><h2>Log in to see ${esc(what)}</h2><p>Your trips and tickets are linked to your account.</p><button class="btn primary" id="needLogin" type="button">Log in or create account</button></div>`;
  $('#needLogin').onclick = () => openAuth(() => route());
  return true;
}

/* =====================================================================
   HOME
   ===================================================================== */
async function home(id) {
  const today = ymd(new Date());
  const tomorrow = ymd(addDays(new Date(), 1));
  const popular = [['Barnala', 'Chandigarh'], ['Bathinda', 'Delhi'], ['Ludhiana', 'Amritsar'], ['Patiala', 'Chandigarh']];

  app.innerHTML = `
    <section class="hero">
      <h1>Pick a bus. Pick your seat. Done.</h1>
      <p class="lede">Search intercity routes across Punjab, Chandigarh and Delhi, choose your exact seat, and cancel with a clear refund if plans change.</p>

      <form class="sign" id="searchForm" novalidate>
        <h2>Where to?</h2>
        <div class="sign-grid">
          <label class="f">From<input name="from" list="cities" placeholder="Barnala" autocomplete="off" required></label>
          <button class="swap" type="button" id="swapBtn" aria-label="Swap from and to">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M7 4 3 8l4 4M3 8h14M17 12l4 4-4 4M21 16H7"/></svg>
          </button>
          <label class="f">To<input name="to" list="cities" placeholder="Chandigarh" autocomplete="off" required></label>
          <label class="f">Travel date<input name="date" type="date" min="${today}" value="${tomorrow}" required></label>
          <button class="btn gold" type="submit">Search buses</button>
        </div>
        <p class="err" id="searchErr" role="alert" style="color:#FFD9D5"></p>
        <datalist id="cities">${state.cities.map((c) => `<option value="${esc(c)}">`).join('')}</datalist>
      </form>

      <div class="chips"><span>Popular routes</span>
        ${popular.map(([a, b]) => `<button class="chip" type="button" data-from="${a}" data-to="${b}">${a} to ${b}</button>`).join('')}
      </div>

      <div class="pnr-lookup-card">
        <div class="pnr-lookup-title">
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="2"/><line x1="3" y1="10" x2="21" y2="10"/><line x1="7" y1="15" x2="7.01" y2="15"/><line x1="11" y1="15" x2="13" y2="15"/></svg>
          Already have a ticket? Check status or print
        </div>
        <form id="pnrLookupForm" class="pnr-form" novalidate>
          <div class="pnr-input-wrap">
            <input name="pnr" placeholder="Enter PNR (e.g. FC...)" maxlength="12" autocomplete="off" required>
            <button class="btn primary small" type="submit">View ticket</button>
          </div>
          <p class="err" id="pnrErr" role="alert"></p>
        </form>
      </div>
    </section>

    <section class="policy">
      <h2>Cancellation and refunds</h2>
      <dl>
        <div class="row"><dt>More than 24 hours before departure</dt><dd>90% back</dd></div>
        <div class="row"><dt>Between 6 and 24 hours before departure</dt><dd>50% back</dd></div>
        <div class="row zero"><dt>Less than 6 hours before departure</dt><dd>No refund</dd></div>
      </dl>
    </section>`;

  const form = $('#searchForm');
  $('#swapBtn').onclick = () => { const el = form.elements, a = el.from.value; el.from.value = el.to.value; el.to.value = a; };
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const el = form.elements, from = el.from.value.trim(), to = el.to.value.trim(), date = el.date.value;
    const err = $('#searchErr');
    if (!from || !to) { err.textContent = 'Enter both a starting city and a destination.'; return; }
    if (from.toLowerCase() === to.toLowerCase()) { err.textContent = 'Starting city and destination must be different.'; return; }
    if (!date || date < today) { err.textContent = 'Pick a travel date from today onwards.'; return; }
    location.hash = `#/results?from=${enc(from)}&to=${enc(to)}&date=${date}`;
  });
  $$('.chip[data-from]').forEach((c) => {
    c.onclick = () => {
      const curDate = form.elements.date.value;
      const d = (curDate && curDate >= today) ? curDate : tomorrow;
      location.hash = `#/results?from=${enc(c.dataset.from)}&to=${enc(c.dataset.to)}&date=${d}`;
    };
  });

  const pnrForm = $('#pnrLookupForm');
  if (pnrForm) {
    pnrForm.addEventListener('submit', (e) => {
      e.preventDefault();
      const pnrInput = pnrForm.elements.pnr.value.trim().toUpperCase();
      const pnrErr = $('#pnrErr');
      if (!pnrInput) { pnrErr.textContent = 'Please enter your PNR.'; return; }
      pnrErr.textContent = '';
      location.hash = '#/ticket/' + enc(pnrInput);
    });
  }
}

/* =====================================================================
   RESULTS
   ===================================================================== */
async function results(q, id) {
  if (!q.from || !q.to || !q.date) { location.hash = '#/'; return; }
  app.innerHTML = '<p class="loading">Searching buses…</p>';
  let list;
  try { list = await api(`/search?from=${enc(q.from)}&to=${enc(q.to)}&date=${enc(q.date)}`); }
  catch (e) { return showError(e.message, id); }
  if (id !== navId) return;

  const today = ymd(new Date());
  const day = (n) => {
    const d = parseLocalDate(q.date);
    d.setDate(d.getDate() + n);
    return `#/results?from=${enc(q.from)}&to=${enc(q.to)}&date=${ymd(d)}`;
  };
  let type = 'ALL', sort = 'time';

  app.innerHTML = `
    <div class="page-head">
      <div>
        <h1 style="font-size:2rem">${esc(q.from)} to ${esc(q.to)}</h1>
        <p>${esc(fmtDateLong(q.date))}</p>
      </div>
      <div class="daynav">
        <a class="btn small" href="${day(-1)}" ${q.date <= today ? 'aria-disabled="true" tabindex="-1"' : ''}>Previous day</a>
        <a class="btn small" href="${day(1)}">Next day</a>
        <a class="btn small" href="#/">Change search</a>
      </div>
    </div>
    <div class="toolbar">
      <div class="chips" role="group" aria-label="Filter by bus type">
        ${[['ALL', 'All buses'], ['AC', 'AC'], ['NON_AC', 'Non-AC'], ['SLEEPER', 'Sleeper']].map(([k, l]) => `<button class="chip" type="button" data-type="${k}" aria-pressed="${k === 'ALL'}">${l}</button>`).join('')}
      </div>
      <label class="f">Sort by
        <select id="sortSel"><option value="time">Earliest departure</option><option value="fare">Lowest fare</option></select>
      </label>
    </div>
    <div id="tripList" aria-live="polite"></div>`;

  const draw = () => {
    let rows = list.filter((s) => type === 'ALL' || s.busType === type);
    rows = [...rows].sort((a, b) => sort === 'fare' ? a.fare - b.fare : a.departure.localeCompare(b.departure));
    $('#tripList').innerHTML = rows.length ? rows.map((s) => {
      const isNextDay = s.arrival.slice(0, 10) !== s.departure.slice(0, 10);
      return `
      <article class="trip">
        <div class="trip-times">
          <strong>${fmtTime(s.departure)}</strong>
          <div class="span">${duration(s.departure, s.arrival)}<i></i></div>
          <strong>${fmtTime(s.arrival)}${isNextDay ? ' <small style="font-size:0.75rem;color:var(--marigold-deep)">+1d</small>' : ''}</strong>
        </div>
        <div class="trip-bus"><b>${esc(s.busName)}</b><span class="badge">${typeLabel(s.busType)}</span> <small>${esc(s.busNumber)}</small></div>
        <div>${s.freeSeats === 0 ? '<span class="low">Sold out</span>' : s.freeSeats <= 6 ? `<span class="low">${s.freeSeats} seats left</span>` : `${s.freeSeats} seats left`}</div>
        <div class="trip-fare"><b>${money(s.fare)}</b><small>per seat</small>
          <a class="btn primary small" href="#/seats/${s.id}" ${s.freeSeats === 0 ? 'aria-disabled="true"' : ''}>Select seats</a>
        </div>
      </article>`;
    }).join('')
      : `<div class="empty">
          <h2>No buses match</h2>
          <p>${list.length ? 'Try another bus type.' : 'No buses run this route on this date. You can try the next day or search another route.'}</p>
          <div style="display:flex;gap:10px;justify-content:center;margin-top:10px;flex-wrap:wrap">
            <a class="btn primary small" href="${day(1)}">Check next day</a>
            <a class="btn small" href="#/">Change search</a>
          </div>
        </div>`;
  };
  draw();
  $$('.chip[data-type]').forEach((c) => { c.onclick = () => { type = c.dataset.type; $$('.chip[data-type]').forEach((x) => x.setAttribute('aria-pressed', x === c)); draw(); }; });
  $('#sortSel').onchange = (e) => { sort = e.target.value; draw(); };
}

/* =====================================================================
   SEATS
   ===================================================================== */
async function seats(sid, id) {
  app.innerHTML = '<p class="loading">Loading seat map…</p>';
  let s;
  try { s = await api('/schedules/' + sid); } catch (e) { return showError(e.message, id); }
  if (id !== navId) return;

  if (!state.trip || state.trip.schedule.id !== sid) state.trip = { schedule: s, selected: [] };
  else { state.trip.schedule = s; state.trip.selected = state.trip.selected.filter((n) => !s.bookedSeats.includes(n)); }
  const t = state.trip;
  const booked = new Set(s.bookedSeats);

  let grid = '';
  for (let i = 1; i <= s.totalSeats; i++) {
    const isBooked = booked.has(i);
    grid += `<button type="button" class="seat" data-seat="${i}" ${isBooked ? 'disabled' : ''} aria-pressed="${t.selected.includes(i)}" aria-label="Seat ${i}, ${isBooked ? 'booked' : 'available'}">${i}</button>`;
    if (i % 4 === 2) grid += '<span aria-hidden="true"></span>';
  }

  app.innerHTML = `
    <a class="back-link" href="#/results?from=${enc(s.source)}&to=${enc(s.destination)}&date=${s.departure.slice(0, 10)}">Back to results</a>
    <div class="split">
      <section class="panel" aria-label="Seat map">
        <h2>Choose your seats</h2>
        <p style="color:var(--muted);margin-top:6px">Select up to 6 seats.</p>
        <div class="bus">
          <div class="bus-front"><span>Front</span>
            <svg class="wheel" viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-label="Driver"><circle cx="16" cy="16" r="12"/><circle cx="16" cy="16" r="3"/><path d="M4 16h9M19 16h9M16 19v9"/></svg>
          </div>
          <div class="seat-grid" id="seatGrid">${grid}</div>
        </div>
        <div class="legend"><span><i></i>Available</span><span><i class="sel"></i>Your choice</span><span><i class="bk"></i>Booked</span></div>
      </section>

      <aside class="panel sticky" aria-label="Trip summary">
        <h3>Your trip</h3>
        <div class="sum-route">${esc(s.source)} to ${esc(s.destination)}</div>
        <div class="sum-meta">
          <span>${fmtDate(s.departure)}, ${fmtTime(s.departure)} to ${fmtTime(s.arrival)}</span>
          <span>${esc(s.busName)} (${typeLabel(s.busType)})</span>
        </div>
        <div class="sum-actions" id="sumActions" style="display:none">
          <button class="linklike" type="button" id="clearSeatsBtn">Clear selection</button>
        </div>
        <div class="sum-seats" id="sumSeats" aria-live="polite"></div>
        <div class="sum-total"><span>Total</span><b id="sumTotal">₹0</b></div>
        <button class="btn primary wide" id="contBtn" type="button" disabled>Continue to passenger details</button>
      </aside>
    </div>`;

  const update = () => {
    t.selected.sort((a, b) => a - b);
    $('#sumSeats').innerHTML = t.selected.length ? t.selected.map((n) => `<span>Seat ${n}</span>`).join('') : '<em>No seats selected yet.</em>';
    const totalFare = t.selected.length * s.fare;
    $('#sumTotal').innerHTML = t.selected.length > 1
      ? `${money(totalFare)} <small style="display:block;font-size:0.8rem;font-weight:400;color:var(--muted)">(${t.selected.length} × ${money(s.fare)})</small>`
      : money(totalFare);
    $('#contBtn').disabled = !t.selected.length;
    const actions = $('#sumActions');
    if (actions) actions.style.display = t.selected.length ? 'flex' : 'none';
    $$('.seat', app).forEach((b) => b.setAttribute('aria-pressed', t.selected.includes(Number(b.dataset.seat))));
  };
  update();

  const clearBtn = $('#clearSeatsBtn');
  if (clearBtn) {
    clearBtn.onclick = () => { t.selected = []; update(); };
  }

  $('#seatGrid').addEventListener('click', (e) => {
    const b = e.target.closest('.seat');
    if (!b || b.disabled) return;
    const n = Number(b.dataset.seat);
    if (t.selected.includes(n)) t.selected = t.selected.filter((x) => x !== n);
    else if (t.selected.length >= 6) return toast('You can book at most 6 seats at a time.', 'error');
    else t.selected.push(n);
    update();
  });
  $('#contBtn').onclick = () => {
    if (!state.user) return openAuth(() => { location.hash = '#/passengers/' + sid; route(); });
    location.hash = '#/passengers/' + sid;
  };
}

/* =====================================================================
   PASSENGERS + (simulated) PAYMENT
   ===================================================================== */
function passengers(sid, id) {
  if (needLogin(id, 'your booking')) return;
  const t = state.trip;
  if (!t || t.schedule.id !== sid || !t.selected.length) { location.hash = '#/seats/' + sid; return; }
  const s = t.schedule, total = t.selected.length * s.fare;

  app.innerHTML = `
    <a class="back-link" href="#/seats/${sid}">Back to seats</a>
    <div class="split">
      <form id="paxForm" novalidate>
        <h2 style="margin-bottom:16px">Passenger details</h2>
        ${t.selected.map((n, i) => `
          <fieldset class="pax">
            <legend>Seat ${n}</legend>
            <div class="pax-grid">
              <label class="f">Full name<input name="name${i}" autocomplete="off" value="${i === 0 ? esc(state.user.name) : ''}" required></label>
              <label class="f">Age<input name="age${i}" type="number" min="1" max="120" required></label>
              <label class="f">Gender<select name="gender${i}"><option value="M">Male</option><option value="F">Female</option><option value="O">Other</option></select></label>
            </div>
          </fieldset>`).join('')}
        <p class="note">This is a demo payment. No money is charged and no card details are needed.</p>
        <p class="err" id="paxErr" role="alert"></p>
        <button class="btn gold wide" id="payBtn" type="submit">Pay ${money(total)}</button>
      </form>

      <aside class="panel sticky" aria-label="Trip summary">
        <h3>Your trip</h3>
        <div class="sum-route">${esc(s.source)} to ${esc(s.destination)}</div>
        <div class="sum-meta">
          <span>${fmtDate(s.departure)}, ${fmtTime(s.departure)} to ${fmtTime(s.arrival)}</span>
          <span>${esc(s.busName)} (${typeLabel(s.busType)})</span>
        </div>
        <div class="sum-seats">${t.selected.map((n) => `<span>Seat ${n}</span>`).join('')}</div>
        <div class="sum-total"><span>${t.selected.length} × ${money(s.fare)}</span><b>${money(total)}</b></div>
      </aside>
    </div>`;

  $('#paxForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const f = e.target, err = $('#paxErr'), btn = $('#payBtn');
    const pax = [];
    for (let i = 0; i < t.selected.length; i++) {
      const el = f.elements, name = el['name' + i].value.trim(), age = Number(el['age' + i].value);
      if (!name) { err.textContent = `Enter the passenger name for seat ${t.selected[i]}.`; el['name' + i].focus(); return; }
      if (!Number.isInteger(age) || age < 1 || age > 120) { err.textContent = `Enter a valid age (1 to 120) for seat ${t.selected[i]}.`; el['age' + i].focus(); return; }
      pax.push({ seatNo: t.selected[i], name, age, gender: el['gender' + i].value });
    }
    err.textContent = '';
    btn.disabled = true;
    btn.textContent = 'Processing payment…';
    try {
      await sleep(900);   // simulated payment gateway
      const b = await api('/bookings', { method: 'POST', body: { scheduleId: sid, passengers: pax } });
      state.trip = null;
      toast('Payment received. Your seats are booked.');
      location.hash = '#/ticket/' + b.pnr;
    } catch (ex) {
      toast(ex.message, 'error');
      if (/already booked|departed|exist/i.test(ex.message)) { location.hash = '#/seats/' + sid; route(); return; }
      err.textContent = ex.message;
      btn.disabled = false;
      btn.textContent = 'Pay ' + money(total);
    }
  });
}

/* =====================================================================
   TICKET
   ===================================================================== */
async function ticket(pnr, id) {
  if (needLogin(id, 'your ticket')) return;
  app.innerHTML = '<p class="loading">Loading ticket…</p>';
  let b;
  try { b = await api('/bookings/' + enc(pnr)); } catch (e) { return showError(e.message, id); }
  if (id !== navId) return;

  const s = b.schedule, cancelled = b.status === 'CANCELLED';
  app.innerHTML = `
    <a class="back-link" href="#/trips">All my trips</a>
    <article class="ticket" style="margin-top:14px" aria-label="Ticket ${esc(b.pnr)}">
      <div class="tk-main">
        <div class="tk-head">
          <span class="pill ${cancelled ? 'cx' : 'ok'}">${cancelled ? 'Cancelled' : 'Confirmed'}</span>
          <span class="tk-pnr">
            PNR<b>${esc(b.pnr)}</b>
            <button class="btn small" type="button" id="copyPnrBtn" title="Copy PNR to clipboard">Copy</button>
          </span>
        </div>
        <div class="tk-route">
          <div><small>From</small><strong>${esc(s.source)}</strong><span>${fmtTime(s.departure)}</span></div>
          <div class="tk-line" aria-hidden="true"></div>
          <div><small>To</small><strong>${esc(s.destination)}</strong><span>${fmtTime(s.arrival)}</span></div>
        </div>
        <dl class="tk-meta">
          <div><dt>Date</dt><dd>${esc(fmtDateLong(s.departure))}</dd></div>
          <div><dt>Bus</dt><dd>${esc(s.busName)} (${typeLabel(s.busType)})</dd></div>
          <div><dt>Bus number</dt><dd>${esc(s.busNumber)}</dd></div>
        </dl>
        <table class="tk-pass">
          <thead><tr><th>Seat</th><th>Passenger</th><th>Age</th><th>Gender</th></tr></thead>
          <tbody>${b.seats.map((p) => `<tr><td><b>${p.seatNo}</b></td><td>${esc(p.name)}</td><td>${p.age}</td><td>${{ M: 'Male', F: 'Female', O: 'Other' }[p.gender] || esc(p.gender)}</td></tr>`).join('')}</tbody>
        </table>
      </div>
      <div class="tk-stub">
        <div><small>Total paid</small><strong>${money(b.totalAmount)}</strong></div>
        ${cancelled ? `<div><small>Refund issued</small><strong>${money(b.refundAmount)}</strong></div>` : ''}
        <small>Show this PNR to the conductor when you board.</small>
      </div>
    </article>
    <div class="tk-actions">
      <button class="btn" type="button" id="printBtn">Print ticket</button>
      ${b.canCancel ? '<button class="btn danger" type="button" id="cancelBtn">Cancel ticket</button>' : ''}
    </div>`;

  $('#printBtn').onclick = () => window.print();
  const copyBtn = $('#copyPnrBtn');
  if (copyBtn) {
    copyBtn.onclick = () => {
      if (navigator.clipboard) {
        navigator.clipboard.writeText(b.pnr)
          .then(() => toast('PNR ' + b.pnr + ' copied to clipboard.'))
          .catch(() => toast('PNR: ' + b.pnr));
      } else {
        toast('PNR: ' + b.pnr);
      }
    };
  }
  const cb = $('#cancelBtn');
  if (cb) cb.onclick = async () => {
    const msg = b.refundPercentNow > 0
      ? `Cancel this ticket? You will get ${money(b.refundIfCancelledNow)} back (${b.refundPercentNow}% of ${money(b.totalAmount)}).`
      : 'Cancel this ticket? The bus leaves in less than 6 hours, so no refund applies.';
    if (!confirm(msg)) return;
    cb.disabled = true;
    try {
      const r = await api(`/bookings/${enc(b.pnr)}/cancel`, { method: 'POST' });
      toast(r.refundAmount > 0 ? `Ticket cancelled. ${money(r.refundAmount)} will be refunded.` : 'Ticket cancelled. No refund applies.');
      ticket(pnr, id);
    } catch (e) { toast(e.message, 'error'); cb.disabled = false; }
  };
}

/* =====================================================================
   MY TRIPS
   ===================================================================== */
async function trips(id) {
  if (needLogin(id, 'your trips')) return;
  app.innerHTML = '<p class="loading">Loading your trips…</p>';
  let list;
  try { list = await api('/bookings'); } catch (e) { return showError(e.message, id); }
  if (id !== navId) return;

  app.innerHTML = `
    <div class="page-head"><h1 style="font-size:2rem">My trips</h1><a class="btn primary" href="#/">Book a bus</a></div>
    ${list.length > 2 ? `
      <div class="admin-toolbar">
        <input class="admin-search" id="tripsSearch" placeholder="Search by PNR or city…" autocomplete="off">
      </div>` : ''}
    <div id="tripsList">
      ${list.length ? list.map((b) => `
        <article class="trip-card" data-search="${esc(b.pnr)} ${esc(b.schedule.source)} ${esc(b.schedule.destination)}">
          <div>
            <div class="r">${esc(b.schedule.source)} to ${esc(b.schedule.destination)}</div>
            <div class="m">${fmtDate(b.schedule.departure)}, ${fmtTime(b.schedule.departure)} · ${b.seats.length} seat${b.seats.length > 1 ? 's' : ''} (${b.seats.map((p) => p.seatNo).join(', ')}) · PNR ${esc(b.pnr)}</div>
          </div>
          <div style="display:flex;align-items:center;gap:14px">
            <span class="pill ${b.status === 'CANCELLED' ? 'cx' : 'ok'}">${b.status === 'CANCELLED' ? 'Cancelled' : 'Confirmed'}</span>
            <b>${money(b.totalAmount)}</b>
            <a class="btn small" href="#/ticket/${esc(b.pnr)}">View ticket</a>
          </div>
        </article>`).join('')
        : `<div class="empty"><h2>No trips yet</h2><p>Search for a bus and book your first seat. Your tickets will show up here.</p><a class="btn primary" href="#/">Find a bus</a></div>`}
    </div>`;

  const ts = $('#tripsSearch');
  if (ts) {
    ts.addEventListener('input', (e) => {
      const term = e.target.value.toLowerCase().trim();
      $$('#tripsList .trip-card').forEach((card) => {
        card.style.display = card.dataset.search.toLowerCase().includes(term) ? '' : 'none';
      });
    });
  }
}

/* =====================================================================
   ADMIN
   ===================================================================== */
async function adminView(tab, id) {
  if (needLogin(id, 'the admin area')) return;
  if (state.user.role !== 'ADMIN') return showError('This area is only for administrators.', id);
  const tabs = [['report', 'Occupancy and revenue'], ['schedules', 'Schedules'], ['bookings', 'Bookings'], ['add', 'Add a schedule']];
  const head = `
    <div class="page-head"><h1 style="font-size:2rem">Admin</h1></div>
    <nav class="tabs" aria-label="Admin sections">${tabs.map(([k, l]) => `<a href="#/admin/${k}"${k === tab ? ' aria-current="page"' : ''}>${l}</a>`).join('')}</nav>
    <div id="adminBody"><p class="loading">Loading…</p></div>`;
  app.innerHTML = head;
  const body = $('#adminBody');
  const dt = (iso) => `${fmtDate(iso)}, ${fmtTime(iso)}`;

  try {
    if (tab === 'report') {
      const r = await api('/admin/report');
      if (id !== navId) return;
      body.innerHTML = `
        <div class="table-wrap"><table class="t">
          <thead><tr><th>ID</th><th>Route</th><th>Bus</th><th>Departure</th><th>Occupancy</th><th class="num">Revenue</th></tr></thead>
          <tbody>${r.rows.map((x) => {
            const pct = Math.round(100 * x.sold / x.totalSeats);
            const barColor = pct >= 75 ? 'var(--green)' : pct >= 35 ? 'var(--marigold)' : '#8A9791';
            return `
            <tr><td>${x.id}</td><td>${esc(x.source)} to ${esc(x.destination)}</td><td>${esc(x.busName)}</td><td>${dt(x.departure)}</td>
            <td><span class="bar"><i style="width:${pct}%;background:${barColor}"></i></span>${x.sold}/${x.totalSeats} (${pct}%)</td><td class="num">${money(x.revenue)}</td></tr>`;
          }).join('')}</tbody>
        </table></div>
        <p class="total-line">Revenue from confirmed seats: <b>${money(r.totalRevenue)}</b></p>`;
    } else if (tab === 'schedules') {
      const r = await api('/admin/schedules');
      if (id !== navId) return;
      body.innerHTML = `
        <div class="admin-toolbar">
          <input class="admin-search" id="schedSearch" placeholder="Filter schedules by city or bus…" autocomplete="off">
        </div>
        <div class="table-wrap"><table class="t" id="schedTable">
          <thead><tr><th>ID</th><th>Route</th><th>Bus</th><th>Type</th><th>Departure</th><th>Arrival</th><th class="num">Fare</th><th class="num">Free seats</th></tr></thead>
          <tbody>${r.map((x) => `<tr data-search="${esc(x.source)} ${esc(x.destination)} ${esc(x.busName)} ${esc(x.busNumber)}"><td>${x.id}</td><td>${esc(x.source)} to ${esc(x.destination)}</td><td>${esc(x.busName)}</td><td>${typeLabel(x.busType)}</td><td>${dt(x.departure)}</td><td>${dt(x.arrival)}</td><td class="num">${money(x.fare)}</td><td class="num">${x.freeSeats}/${x.totalSeats}</td></tr>`).join('')}</tbody>
        </table></div>`;
      const sInput = $('#schedSearch');
      if (sInput) {
        sInput.addEventListener('input', (e) => {
          const q = e.target.value.toLowerCase().trim();
          $$('#schedTable tbody tr').forEach((tr) => {
            tr.style.display = tr.dataset.search.toLowerCase().includes(q) ? '' : 'none';
          });
        });
      }
    } else if (tab === 'bookings') {
      const r = await api('/admin/bookings');
      if (id !== navId) return;
      body.innerHTML = r.length ? `
        <div class="admin-toolbar">
          <input class="admin-search" id="bookingSearch" placeholder="Filter bookings by PNR or city…" autocomplete="off">
        </div>
        <div class="table-wrap"><table class="t" id="bookingTable">
          <thead><tr><th>PNR</th><th>Route</th><th>Departure</th><th>Seats</th><th class="num">Amount</th><th>Status</th></tr></thead>
          <tbody>${r.map((x) => `<tr data-search="${esc(x.pnr)} ${esc(x.schedule.source)} ${esc(x.schedule.destination)}"><td><a href="#/ticket/${esc(x.pnr)}">${esc(x.pnr)}</a></td><td>${esc(x.schedule.source)} to ${esc(x.schedule.destination)}</td><td>${dt(x.schedule.departure)}</td><td>${x.seats.map((p) => p.seatNo).join(', ')}</td><td class="num">${money(x.totalAmount)}</td><td><span class="pill ${x.status === 'CANCELLED' ? 'cx' : 'ok'}">${x.status === 'CANCELLED' ? 'Cancelled' : 'Confirmed'}</span></td></tr>`).join('')}</tbody>
        </table></div>`
        : '<div class="empty"><h2>No bookings yet</h2><p>Bookings made by passengers will appear here.</p></div>';
      const bInput = $('#bookingSearch');
      if (bInput) {
        bInput.addEventListener('input', (e) => {
          const q = e.target.value.toLowerCase().trim();
          $$('#bookingTable tbody tr').forEach((tr) => {
            tr.style.display = tr.dataset.search.toLowerCase().includes(q) ? '' : 'none';
          });
        });
      }
    } else {
      body.innerHTML = `
        <form id="addForm" class="form-grid" novalidate>
          <label class="f">Bus number<input name="busNumber" placeholder="PB-10-AC-4321" required></label>
          <label class="f">Bus name<input name="busName" placeholder="Doaba Deluxe" required></label>
          <label class="f">Bus type<select name="busType"><option value="AC">AC</option><option value="NON_AC">Non-AC</option><option value="SLEEPER">Sleeper</option></select></label>
          <label class="f">From<input name="from" list="cityList" required></label>
          <label class="f">To<input name="to" list="cityList" required></label>
          <label class="f">Total seats<input name="totalSeats" type="number" min="1" max="60" value="40" required></label>
          <label class="f">Departure<input name="departure" type="datetime-local" required></label>
          <label class="f">Arrival<input name="arrival" type="datetime-local" required></label>
          <label class="f">Fare per seat (₹)<input name="fare" type="number" min="1" step="1" required></label>
          <datalist id="cityList">${state.cities.map((c) => `<option value="${esc(c)}">`).join('')}</datalist>
          <p class="err full" id="addErr" role="alert"></p>
          <div class="full"><button class="btn primary" type="submit">Add schedule</button></div>
        </form>`;
      $('#addForm').addEventListener('submit', async (e) => {
        e.preventDefault();
        const f = e.target, err = $('#addErr');
        const v = Object.fromEntries(new FormData(f).entries());
        if (Object.values(v).some((x) => !String(x).trim())) { err.textContent = 'Fill in every field.'; return; }
        try {
          const s = await api('/admin/schedules', { method: 'POST', body: v });
          toast(`Schedule #${s.id} added for ${s.source} to ${s.destination}.`);
          err.textContent = '';
          f.reset();
          loadCities();
        } catch (ex) { err.textContent = ex.message; }
      });
    }
  } catch (e) { showError(e.message, id); }
}

/* =====================================================================
   START
   ===================================================================== */
async function loadCities() {
  try { state.cities = await api('/cities'); } catch { /* the form still works without suggestions */ }
}

(async function init() {
  if (state.token) {
    try { setSession({ ...(await api('/me')), token: state.token }); } catch { setSession(null); }
  }
  await loadCities();
  route();
})();
