'use strict';

/* =====================================================================
   First Choice - website (plain JavaScript, no framework)
   Passenger screens live here, admin screens in admin.js.
   ===================================================================== */

/* ---------- helpers ---------- */
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const enc = encodeURIComponent;
const money = (n) => '₹' + Number(n).toLocaleString('en-IN', { maximumFractionDigits: 2 });
const pad = (n) => String(n).padStart(2, '0');
const ymd = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const addDays = (d, n) => { const x = new Date(d); x.setDate(x.getDate() + n); return x; };
const fmtTime = (iso) => new Date(iso).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: true });
const fmtDate = (iso) => new Date(iso).toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short' });
const fmtDateLong = (iso) => new Date(iso).toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
const duration = (a, b) => { const m = Math.round((new Date(b) - new Date(a)) / 60000); return `${Math.floor(m / 60)} h ${pad(m % 60)} m`; };
const typeLabel = (t) => ({ AC: 'AC', NON_AC: 'Non-AC' }[t] || t);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const stars = (n) => '★'.repeat(n) + '☆'.repeat(5 - n);
const genderName = (g) => ({ M: 'Male', F: 'Female', O: 'Other' }[g] || g);

const store = {
  get(k) { try { return localStorage.getItem(k) || ''; } catch { return ''; } },
  set(k, v) { try { v ? localStorage.setItem(k, v) : localStorage.removeItem(k); } catch { /* storage blocked */ } },
};

/* ---------- state ---------- */
const state = {
  user: null, token: store.get('fc_token'), cities: [],
  config: { paymentProvider: 'DEMO', holdMinutes: 10, emailLogMode: true },
  sel: null,            // seats chosen on the seat page: { tripId, labels, boarding, dropping }
  cleanup: null,        // function that stops timers of the current page
  afterLogin: null,
};
let navId = 0;
const app = $('#app');

function toast(msg, kind = '') {
  const t = $('#toast');
  t.textContent = msg;
  t.className = 'show ' + kind;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { t.className = ''; }, 4200);
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
  if (res.status === 401 && state.token && !path.startsWith('/auth/')) setSession(null);
  if (!res.ok) { const e = new Error(data.error || 'Something went wrong. Please try again.'); e.status = res.status; throw e; }
  return data;
}

function setSession(s) {
  if (s && s.token) { state.token = s.token; }
  if (s) state.user = { id: s.id, name: s.name, email: s.email, phone: s.phone, role: s.role, emailVerified: s.emailVerified };
  else { state.user = null; state.token = ''; }
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
        ${link('#/offers', 'Offers', 'offers')}
        ${link('#/track', 'Track booking', 'track')}
        ${link('#/trips', 'My trips', 'trips')}
        ${u && u.role === 'ADMIN' ? link('#/admin', 'Admin', 'admin') : ''}
      </nav>
      <div class="acct">
        ${u ? `<a class="who" href="#/profile">Hi, ${esc(u.name.split(' ')[0])}</a><button class="btn small" id="logoutBtn" type="button">Log out</button>`
            : '<button class="btn small primary" id="loginBtn" type="button">Log in</button>'}
      </div>
    </div>
    ${u && !u.emailVerified ? '<div class="banner"><div>Please confirm your email address so we can send your tickets. <button class="linklike" id="resendBtn" type="button">Resend the email</button></div></div>' : ''}`;
  const lo = $('#logoutBtn'), li = $('#loginBtn'), rs = $('#resendBtn');
  if (lo) lo.onclick = async () => { try { await api('/auth/logout', { method: 'POST' }); } catch { /* ignore */ } setSession(null); state.sel = null; toast('You are logged out.'); location.hash = '#/'; route(); };
  if (li) li.onclick = () => openAuth();
  if (rs) rs.onclick = async () => { try { await api('/auth/resend-verification', { method: 'POST' }); toast('Verification email sent. Check your inbox.'); } catch (e) { toast(e.message, 'error'); } };
}

/* ---------- login / register dialog ---------- */
const dlg = $('#authDialog');
let authMode = 'login';

function setAuthMode(mode) {
  authMode = mode;
  $$('.seg button', dlg).forEach((b) => b.classList.toggle('on', b.dataset.mode === mode));
  $$('.reg-only', dlg).forEach((e) => { e.hidden = mode !== 'register'; });
  $$('.login-only', dlg).forEach((e) => { e.hidden = mode !== 'login'; });
  $('#authTitle').textContent = mode === 'login' ? 'Log in' : 'Create your account';
  $('#authSubmit').textContent = mode === 'login' ? 'Log in' : 'Create account';
  $('input[name=password]', dlg).autocomplete = mode === 'login' ? 'current-password' : 'new-password';
  $('#authErr').textContent = '';
}

function openAuth(afterLogin, mode = 'login') {
  state.afterLogin = afterLogin || null;
  $('#authForm').reset();
  setAuthMode(mode);
  if (!dlg.open) dlg.showModal();
  $('input[name=email]', dlg).focus();
}

$$('.seg button', dlg).forEach((b) => { b.onclick = () => setAuthMode(b.dataset.mode); });
$('#forgotLink').addEventListener('click', () => { dlg.close(); });
$('#authForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const el = e.target.elements, err = $('#authErr'), btn = $('#authSubmit');
  const body = { email: el.email.value.trim(), password: el.password.value };
  if (authMode === 'register') { body.name = el.name.value.trim(); body.phone = el.phone.value.trim(); }
  if (!body.email || !body.password || (authMode === 'register' && (!body.name || !body.phone))) { err.textContent = 'Fill in all the fields.'; return; }
  btn.disabled = true;
  try {
    const s = await api(authMode === 'login' ? '/auth/login' : '/auth/register', { method: 'POST', body });
    setSession(s);
    dlg.close();
    toast(authMode === 'login' ? `Welcome back, ${s.name.split(' ')[0]}.` : 'Account created. Check your email to confirm it.');
    const next = state.afterLogin;
    state.afterLogin = null;
    next ? next() : route();
  } catch (ex) { err.textContent = ex.message; }
  finally { btn.disabled = false; }
});

/* ---------- router ---------- */
function route() {
  const id = ++navId;
  if (state.cleanup) { state.cleanup(); state.cleanup = null; }
  renderHeader();
  const [pathPart, queryPart] = (location.hash.slice(1) || '/').split('?');
  const parts = pathPart.split('/').filter(Boolean);
  const q = Object.fromEntries(new URLSearchParams(queryPart || ''));
  const views = {
    undefined: () => home(id),
    results: () => results(q, id),
    seats: () => seats(parts[1], id),
    passengers: () => passengers(parts[1], id),
    checkout: () => checkout(parts[1], id),
    ticket: () => ticket(parts[1], id),
    trips: () => trips(id),
    profile: () => profile(id),
    track: () => trackPage(id),
    offers: () => offersPage(id),
    help: () => helpPage(),
    forgot: () => forgotPage(),
    reset: () => resetPage(q.token || ''),
    verify: () => verifyPage(q.token || '', id),
    admin: () => adminView(parts[1] || 'report', id),
  };
  (views[parts[0]] || views.undefined)();
  window.scrollTo(0, 0);
}
window.addEventListener('hashchange', route);

function showError(message, id) {
  if (id !== undefined && id !== navId) return;
  app.innerHTML = `<div class="empty"><h2>That did not work</h2><p>${esc(message)}</p><a class="btn primary" href="#/">Back to search</a></div>`;
}

/** Returns true (and shows a login prompt) when the page needs a logged-in user. */
function needLogin(id, what) {
  if (id !== navId) return true;
  if (state.user) return false;
  app.innerHTML = `<div class="empty"><h2>Log in to see ${esc(what)}</h2><p>Your trips and tickets are linked to your account.</p>
    <button class="btn primary" id="needLogin" type="button">Log in or create an account</button></div>`;
  $('#needLogin').onclick = () => openAuth(() => route());
  return true;
}

const policyRows = `
  <div class="row"><dt>More than 24 hours before departure</dt><dd>90% back</dd></div>
  <div class="row"><dt>Between 6 and 24 hours before departure</dt><dd>50% back</dd></div>
  <div class="row zero"><dt>Less than 6 hours before departure</dt><dd>No refund</dd></div>`;

const amenityTags = (list) => (list || []).map((a) => `<span class="tag">${esc(a)}</span>`).join('');
const ratingBadge = (r, n) => r ? `<span class="rate">★ ${r.toFixed(1)}</span> <small>${n} review${n === 1 ? '' : 's'}</small>` : '<span class="rate none">New</span>';
const delayNote = (m) => m > 0 ? `<span class="late">Running about ${m} min late</span>` : '';

/* =====================================================================
   HOME
   ===================================================================== */
async function home(id) {
  const today = ymd(new Date()), tomorrow = ymd(addDays(new Date(), 1));
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
    </section>

    <section class="section" id="offerSlot"></section>

    <section class="section">
      <h2>Why book here</h2>
      <div class="facts">
        <div class="fact"><h3>Your exact seat</h3><p>See the real seat plan, including lower and upper sleeper berths and seats kept for women, and choose where you sit.</p></div>
        <div class="fact"><h3>Seats held while you pay</h3><p>Once you pick seats we hold them for ${state.config.holdMinutes} minutes, so nobody can take them while you check out.</p></div>
        <div class="fact"><h3>Cancel online</h3><p>Cancel the whole booking or just some seats. The refund is shown before you confirm.</p></div>
      </div>
    </section>

    <section class="policy section">
      <h2>Cancellation and refunds</h2>
      <dl>${policyRows}</dl>
    </section>`;

  const form = $('#searchForm');
  $('#swapBtn').onclick = () => { const el = form.elements, a = el.from.value; el.from.value = el.to.value; el.to.value = a; };
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    const el = form.elements, from = el.from.value.trim(), to = el.to.value.trim(), date = el.date.value, err = $('#searchErr');
    if (!from || !to) { err.textContent = 'Enter both a starting city and a destination.'; return; }
    if (from.toLowerCase() === to.toLowerCase()) { err.textContent = 'Starting city and destination must be different.'; return; }
    if (!date || date < today) { err.textContent = 'Pick a travel date from today onwards.'; return; }
    location.hash = `#/results?from=${enc(from)}&to=${enc(to)}&date=${date}`;
  });
  $$('.chip[data-from]').forEach((c) => { c.onclick = () => { location.hash = `#/results?from=${enc(c.dataset.from)}&to=${enc(c.dataset.to)}&date=${tomorrow}`; }; });

  try {
    const offers = await api('/offers');
    if (id === navId && offers.length) $('#offerSlot').innerHTML = `<h2>Offers</h2><div class="offer-row">${offers.slice(0, 3).map((o) => `<div class="offer"><b>${esc(o.code)}</b><span>${esc(o.description)}</span></div>`).join('')}</div>`;
  } catch { /* offers are optional */ }
}

/* =====================================================================
   RESULTS (filters + sort)
   ===================================================================== */
async function results(q, id) {
  if (!q.from || !q.to || !q.date) { location.hash = '#/'; return; }
  app.innerHTML = '<p class="loading">Searching buses…</p>';
  let list;
  try { list = await api(`/search?from=${enc(q.from)}&to=${enc(q.to)}&date=${enc(q.date)}`); }
  catch (e) { return showError(e.message, id); }
  if (id !== navId) return;

  const today = ymd(new Date());
  const day = (n) => `#/results?from=${enc(q.from)}&to=${enc(q.to)}&date=${ymd(addDays(new Date(q.date + 'T00:00'), n))}`;
  const active = { type: new Set(), layout: new Set(), time: new Set(), wifi: false };
  let sort = 'time';
  const slot = (h) => (h < 6 ? 'night' : h < 12 ? 'morning' : h < 18 ? 'afternoon' : 'evening');
  const chip = (group, val, label) => `<button class="chip" type="button" data-group="${group}" data-val="${val}" aria-pressed="false">${label}</button>`;

  app.innerHTML = `
    <div class="page-head">
      <div><h1 style="font-size:2rem">${esc(q.from)} to ${esc(q.to)}</h1><p>${esc(fmtDateLong(q.date + 'T00:00'))} · <span id="count"></span></p></div>
      <div class="daynav">
        <a class="btn small" href="${day(-1)}" ${q.date <= today ? 'aria-disabled="true" tabindex="-1"' : ''}>Previous day</a>
        <a class="btn small" href="${day(1)}">Next day</a>
        <a class="btn small" href="#/">Change search</a>
      </div>
    </div>
    <div class="filters">
      <div class="row"><span class="lbl">Bus type</span>${chip('type', 'AC', 'AC')}${chip('type', 'NON_AC', 'Non-AC')}${chip('layout', 'SEATER', 'Seater')}${chip('layout', 'SLEEPER', 'Sleeper')}${chip('wifi', 'on', 'WiFi')}</div>
      <div class="row"><span class="lbl">Departure</span>${chip('time', 'night', 'Before 6 am')}${chip('time', 'morning', '6 am to 12 pm')}${chip('time', 'afternoon', '12 pm to 6 pm')}${chip('time', 'evening', 'After 6 pm')}
        <label class="f" style="grid-auto-flow:column;align-items:center;margin-left:auto">Sort by
          <select id="sortSel" style="width:auto;padding:8px 10px"><option value="time">Earliest departure</option><option value="fare">Lowest fare</option><option value="duration">Shortest trip</option><option value="rating">Best rated</option><option value="seats">Most seats left</option></select></label></div>
    </div>
    <div id="tripList" aria-live="polite"></div>`;

  const draw = () => {
    let rows = list.filter((t) => (!active.type.size || active.type.has(t.busType)) && (!active.layout.size || active.layout.has(t.layout))
      && (!active.time.size || active.time.has(slot(new Date(t.departure).getHours()))) && (!active.wifi || t.amenities.includes('WiFi')));
    const dur = (t) => new Date(t.arrival) - new Date(t.departure);
    const cmp = { time: (a, b) => a.departure.localeCompare(b.departure), fare: (a, b) => a.fare - b.fare, duration: (a, b) => dur(a) - dur(b),
      rating: (a, b) => (b.rating || 0) - (a.rating || 0), seats: (a, b) => b.freeSeats - a.freeSeats }[sort];
    rows = [...rows].sort(cmp);
    $('#count').textContent = `${rows.length} of ${list.length} bus${list.length === 1 ? '' : 'es'}`;
    $('#tripList').innerHTML = rows.length ? rows.map((t) => `
      <article class="trip">
        <div class="trip-times"><strong>${fmtTime(t.departure)}</strong><div class="span">${duration(t.departure, t.arrival)}<i></i></div><strong>${fmtTime(t.arrival)}</strong></div>
        <div class="trip-bus"><b>${esc(t.busName)}</b>
          <span class="badge">${typeLabel(t.busType)}</span> <span class="badge">${t.layout === 'SLEEPER' ? 'Sleeper' : 'Seater'}</span>
          <small>${esc(t.operatorName)}</small></div>
        <div>${t.freeSeats === 0 ? '<span class="low">Sold out</span>' : t.freeSeats <= 6 ? `<span class="low">${t.freeSeats} seats left</span>` : `${t.freeSeats} seats left`}</div>
        <div class="trip-fare"><b>${money(t.fare)}</b><small>per seat</small>
          <a class="btn primary small" href="#/seats/${t.id}" ${t.freeSeats === 0 ? 'aria-disabled="true"' : ''}>Select seats</a></div>
        <div class="trip-extra">${ratingBadge(t.rating, t.ratingCount)} ${amenityTags(t.amenities)} ${delayNote(t.delayMin)}</div>
      </article>`).join('')
      : `<div class="empty"><h2>${list.length ? 'No buses match these filters' : 'No buses on this day'}</h2><p>${list.length ? 'Clear a filter to see more buses.' : 'We have no buses on this route for this date. Try the next day or another route.'}</p></div>`;
  };
  draw();
  $$('.chip[data-group]').forEach((c) => {
    c.onclick = () => {
      const g = c.dataset.group, v = c.dataset.val;
      if (g === 'wifi') active.wifi = !active.wifi;
      else if (active[g].has(v)) active[g].delete(v); else active[g].add(v);
      c.setAttribute('aria-pressed', g === 'wifi' ? active.wifi : active[g].has(v));
      draw();
    };
  });
  $('#sortSel').onchange = (e) => { sort = e.target.value; draw(); };
}

/* =====================================================================
   SEATS: live seat map + boarding / dropping points
   ===================================================================== */
const wheelSvg = '<svg class="wheel" viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-label="Driver"><circle cx="16" cy="16" r="12"/><circle cx="16" cy="16" r="3"/><path d="M4 16h9M19 16h9M16 19v9"/></svg>';

function seatMapHtml(plan, picked) {
  const decks = [...new Set(plan.seats.map((s) => s.deck))];
  const sleeper = plan.type === 'SLEEPER';
  return decks.map((d) => `
    <div class="deck">${sleeper ? `<h4>${d === 'L' ? 'Lower deck' : 'Upper deck'}</h4>` : ''}
      <div class="bus"><div class="bus-front"><span>Front</span>${d === 'L' ? wheelSvg : '<span></span>'}</div>
        <div class="seat-grid cols${plan.cols}">${plan.seats.filter((s) => s.deck === d).map((s) =>
          `<button type="button" class="seat${sleeper ? ' berth' : ''}${s.ladies ? ' ladies' : ''}" data-label="${esc(s.label)}" style="grid-row:${s.row + 1};grid-column:${s.col + 1}" ${s.available ? '' : 'disabled'} aria-pressed="${picked.has(s.label)}" aria-label="Seat ${esc(s.label)}, ${s.available ? 'available' : 'booked'}${s.ladies ? ', women only' : ''}">${esc(s.label)}</button>`).join('')}
        </div></div></div>`).join('');
}

async function seats(tripId, id) {
  app.innerHTML = '<p class="loading">Loading seat map…</p>';
  let t;
  try { t = await api('/trips/' + enc(tripId)); } catch (e) { return showError(e.message, id); }
  if (id !== navId) return;

  const keep = state.sel && state.sel.tripId === tripId ? state.sel : null;
  const picked = new Set((keep ? keep.labels : []).filter((l) => t.seatPlan.seats.some((s) => s.label === l && s.available)));
  const dateStr = t.departure.slice(0, 10);

  app.innerHTML = `
    <a class="back-link" href="#/results?from=${enc(t.source)}&to=${enc(t.destination)}&date=${dateStr}">Back to results</a>
    <div class="split">
      <div class="stack">
        <section class="panel" aria-label="Seat map">
          <div style="display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;align-items:baseline">
            <h2>Choose your seats</h2><span class="live"><i></i> Live availability</span></div>
          <p class="h-sub">Select up to 6 seats. Seats marked W are for women passengers.</p>
          ${t.bookingOpen ? '' : '<p class="ticket-note bad">Booking for this bus is closed.</p>'}
          <div class="decks" id="decks">${seatMapHtml(t.seatPlan, picked)}</div>
          <div class="legend"><span><i></i>Available</span><span><i class="sel"></i>Your choice</span><span><i class="bk"></i>Booked</span><span><i class="lad"></i>Women only</span></div>
        </section>
        <section class="panel">
          <h3>About this bus</h3>
          <p><b>${esc(t.busName)}</b> (${typeLabel(t.busType)}, ${t.layout === 'SLEEPER' ? 'sleeper' : 'seater'}) · ${esc(t.busNumber)}</p>
          <p class="h-sub">Operated by ${esc(t.operatorName)}${t.operatorPhone ? ' · ' + esc(t.operatorPhone) : ''}</p>
          <div class="amen">${amenityTags(t.amenities)}</div>
          <p style="margin-top:12px">${ratingBadge(t.rating, t.ratingCount)} ${delayNote(t.delayMin)}</p>
          <div class="reviews">${t.reviews.map((r) => `<div class="review"><span class="stars">${stars(r.rating)}</span> <b>${esc(r.name)}</b> <small>${esc(r.date)}</small><p>${esc(r.text)}</p></div>`).join('') || '<p class="h-sub">No reviews yet.</p>'}</div>
        </section>
        <section class="panel policy"><h3>Cancellation policy</h3><dl>${policyRows}</dl></section>
      </div>

      <aside class="panel sticky" aria-label="Trip summary">
        <h3>Your trip</h3>
        <div class="sum-route">${esc(t.source)} to ${esc(t.destination)}</div>
        <div class="sum-meta"><span>${fmtDate(t.departure)}, ${fmtTime(t.departure)} to ${fmtTime(t.arrival)}</span></div>
        <div class="pick"><b>Boarding point</b><div class="points">${t.boardingPoints.map((p, i) => `<label class="pt"><input type="radio" name="boarding" value="${esc(p.name)}" ${(keep ? keep.boarding === p.name : i === 0) ? 'checked' : ''}><span>${esc(p.name)}</span><time>${fmtTime(p.time)}</time></label>`).join('') || '<small>Not applicable</small>'}</div></div>
        <div class="pick"><b>Dropping point</b><div class="points">${t.droppingPoints.map((p, i) => `<label class="pt"><input type="radio" name="dropping" value="${esc(p.name)}" ${(keep ? keep.dropping === p.name : i === 0) ? 'checked' : ''}><span>${esc(p.name)}</span><time>${fmtTime(p.time)}</time></label>`).join('') || '<small>Not applicable</small>'}</div></div>
        <div class="sum-seats" id="sumSeats" aria-live="polite"></div>
        <div class="sum-total"><span>Fare</span><b id="sumTotal">₹0</b></div>
        <button class="btn primary wide" id="contBtn" type="button" disabled>Continue</button>
      </aside>
    </div>`;

  const update = () => {
    const labels = [...picked];
    $('#sumSeats').innerHTML = labels.length ? labels.map((n) => `<span>Seat ${esc(n)}</span>`).join('') : '<em>No seats selected yet.</em>';
    $('#sumTotal').textContent = money(labels.length * t.fare);
    $('#contBtn').disabled = !labels.length || !t.bookingOpen;
    $$('.seat', app).forEach((b) => b.setAttribute('aria-pressed', picked.has(b.dataset.label)));
  };
  update();

  $('#decks').addEventListener('click', (e) => {
    const b = e.target.closest('.seat');
    if (!b || b.disabled) return;
    const l = b.dataset.label;
    if (picked.has(l)) picked.delete(l);
    else if (picked.size >= 6) return toast('You can book at most 6 seats at a time.', 'error');
    else picked.add(l);
    update();
  });

  $('#contBtn').onclick = () => {
    const rb = (n) => { const x = $(`input[name=${n}]:checked`); return x ? x.value : ''; };
    state.sel = { tripId, labels: [...picked], boarding: rb('boarding'), dropping: rb('dropping') };
    if (!state.user) return openAuth(() => { location.hash = '#/passengers/' + tripId; route(); });
    location.hash = '#/passengers/' + tripId;
  };

  // live availability: refresh every 6 seconds, drop seats someone else just took
  const timer = setInterval(async () => {
    try {
      const fresh = await api('/trips/' + enc(tripId));
      if (id !== navId) return;
      const lost = [];
      fresh.seatPlan.seats.forEach((s) => {
        const btn = $(`.seat[data-label="${CSS.escape(s.label)}"]`);
        if (!btn) return;
        if (!s.available && !btn.disabled) { btn.disabled = true; if (picked.delete(s.label)) lost.push(s.label); }
        if (s.available && btn.disabled) btn.disabled = false;
      });
      if (lost.length) { toast(`Seat ${lost.join(', ')} was just taken by someone else.`, 'error'); }
      update();
    } catch { /* try again on the next tick */ }
  }, 6000);
  state.cleanup = () => clearInterval(timer);
}

/* =====================================================================
   PASSENGER DETAILS + COUPON  ->  creates the booking and holds the seats
   ===================================================================== */
async function passengers(tripId, id) {
  if (needLogin(id, 'your booking')) return;
  const sel = state.sel;
  if (!sel || sel.tripId !== tripId || !sel.labels.length) { location.hash = '#/seats/' + tripId; return; }
  let t;
  try { t = await api('/trips/' + enc(tripId)); } catch (e) { return showError(e.message, id); }
  if (id !== navId) return;
  const gone = sel.labels.filter((l) => !t.seatPlan.seats.some((s) => s.label === l && s.available));
  if (gone.length) { toast(`Seat ${gone.join(', ')} is no longer available. Please choose again.`, 'error'); location.hash = '#/seats/' + tripId; return; }
  const ladies = new Set(t.seatPlan.seats.filter((s) => s.ladies).map((s) => s.label));
  const base = sel.labels.length * t.fare;
  let coupon = null;

  app.innerHTML = `
    <a class="back-link" href="#/seats/${enc(tripId)}">Back to seats</a>
    <div class="split">
      <form id="paxForm" class="stack" novalidate>
        <h2>Passenger details</h2>
        ${sel.labels.map((l, i) => `
          <fieldset class="pax"><legend>Seat ${esc(l)}${ladies.has(l) ? ' (women only)' : ''}</legend>
            <div class="pax-grid">
              <label class="f">Full name<input name="name${i}" autocomplete="off" value="${i === 0 ? esc(state.user.name) : ''}"></label>
              <label class="f">Age<input name="age${i}" type="number" min="1" max="120" inputmode="numeric"></label>
              <label class="f">Gender<select name="gender${i}">${ladies.has(l) ? '<option value="F">Female</option>' : '<option value="M">Male</option><option value="F">Female</option><option value="O">Other</option>'}</select></label>
            </div></fieldset>`).join('')}
        <h2 style="margin-top:8px">Contact for your ticket</h2>
        <div class="pax-grid" style="grid-template-columns:1fr 1fr">
          <label class="f">Mobile number<input name="phone" type="tel" inputmode="numeric" value="${esc(state.user.phone || '')}"></label>
          <label class="f">Email<input name="email" type="email" value="${esc(state.user.email)}"></label>
        </div>
        <p class="err" id="paxErr" role="alert"></p>
        <button class="btn gold wide" id="goPay" type="submit">Continue to payment</button>
        <p class="hint">Your seats are held for ${t.holdMinutes} minutes once you continue.</p>
      </form>

      <aside class="panel sticky" aria-label="Fare summary">
        <h3>Your trip</h3>
        <div class="sum-route">${esc(t.source)} to ${esc(t.destination)}</div>
        <div class="sum-meta"><span>${fmtDate(t.departure)}, ${fmtTime(t.departure)} to ${fmtTime(t.arrival)}</span><span>${esc(t.busName)} (${typeLabel(t.busType)})</span>
          <span>Boarding: ${esc(sel.boarding || '-')}</span><span>Dropping: ${esc(sel.dropping || '-')}</span></div>
        <div class="sum-seats">${sel.labels.map((n) => `<span>Seat ${esc(n)}</span>`).join('')}</div>
        <div class="fare-lines" id="fareLines"></div>
        <label class="f">Coupon code
          <span class="coupon-row"><input id="couponIn" placeholder="e.g. SAVE10" autocomplete="off"><button class="btn" type="button" id="applyBtn">Apply</button></span></label>
        <p class="err" id="couponMsg" role="status"></p>
        <div id="offerChips" class="chips" style="margin-top:6px"></div>
      </aside>
    </div>`;

  const drawFare = () => {
    const disc = coupon ? coupon.discount : 0;
    $('#fareLines').innerHTML = `<div><span>${sel.labels.length} × ${money(t.fare)}</span><span>${money(base)}</span></div>
      ${coupon ? `<div class="disc"><span>Coupon ${esc(coupon.code)}</span><span>− ${money(disc)}</span></div>` : ''}
      <div class="sum-total" style="margin:6px 0 0"><span>Total</span><b>${money(base - disc)}</b></div>`;
    $('#goPay').textContent = `Continue to payment · ${money(base - disc)}`;
  };
  drawFare();

  const applyCoupon = async (code) => {
    const msg = $('#couponMsg');
    try {
      const r = await api('/coupons', { method: 'POST', body: { code, tripId, seatCount: sel.labels.length } });
      coupon = { code: r.code, discount: r.discount };
      msg.style.color = '#1E7B4F'; msg.textContent = `Coupon applied. You save ${money(r.discount)}.`;
    } catch (e) { coupon = null; msg.style.color = ''; msg.textContent = e.message; }
    drawFare();
  };
  $('#applyBtn').onclick = () => { const c = $('#couponIn').value.trim(); if (c) applyCoupon(c); };
  api('/offers').then((list) => {
    if (id !== navId) return;
    $('#offerChips').innerHTML = list.map((o) => `<button class="chip" type="button" data-code="${esc(o.code)}" title="${esc(o.description)}">${esc(o.code)}</button>`).join('');
    $$('#offerChips .chip').forEach((c) => { c.onclick = () => { $('#couponIn').value = c.dataset.code; applyCoupon(c.dataset.code); }; });
  }).catch(() => {});

  $('#paxForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const el = e.target.elements, err = $('#paxErr'), btn = $('#goPay');
    const seatsReq = [];
    for (let i = 0; i < sel.labels.length; i++) {
      const name = el['name' + i].value.trim(), age = Number(el['age' + i].value);
      if (name.length < 2) { err.textContent = `Enter the passenger name for seat ${sel.labels[i]}.`; el['name' + i].focus(); return; }
      if (!Number.isInteger(age) || age < 1 || age > 120) { err.textContent = `Enter a valid age (1 to 120) for seat ${sel.labels[i]}.`; el['age' + i].focus(); return; }
      seatsReq.push({ label: sel.labels[i], name, age, gender: el['gender' + i].value });
    }
    if (!/^(\+?91)?[\s-]?[6-9][0-9]{4}[\s-]?[0-9]{5}$/.test(el.phone.value.trim())) { err.textContent = 'Enter a valid 10-digit mobile number.'; el.phone.focus(); return; }
    err.textContent = '';
    btn.disabled = true;
    try {
      const b = await api('/bookings', { method: 'POST', body: { tripId, seats: seatsReq, boarding: sel.boarding, dropping: sel.dropping, phone: el.phone.value.trim(), email: el.email.value.trim(), coupon: coupon ? coupon.code : '' } });
      location.hash = '#/checkout/' + b.pnr;
    } catch (ex) {
      err.textContent = ex.message;
      btn.disabled = false;
      if (ex.status === 409) { toast(ex.message, 'error'); location.hash = '#/seats/' + tripId; }
    }
  });
}

/* =====================================================================
   CHECKOUT: countdown + payment (demo gateway or Razorpay)
   ===================================================================== */
function loadScript(src) {
  return new Promise((resolve, reject) => {
    if (window.Razorpay) return resolve();
    const s = document.createElement('script');
    s.src = src; s.onload = resolve;
    s.onerror = () => reject(new Error('Could not load the payment window. Check your connection and try again.'));
    document.head.appendChild(s);
  });
}

async function runPayment(b, demoOutcome) {
  const p = b.payment;
  if (p.provider !== 'RAZORPAY') return api(`/bookings/${enc(b.pnr)}/pay`, { method: 'POST', body: { outcome: demoOutcome } });
  await loadScript('https://checkout.razorpay.com/v1/checkout.js');
  const resp = await new Promise((resolve, reject) => {
    const rz = new window.Razorpay({
      key: p.keyId, amount: p.amountPaise, currency: 'INR', order_id: p.orderId, name: 'First Choice', description: `Bus ticket ${b.pnr}`,
      prefill: { name: state.user.name, email: b.contactEmail, contact: b.contactPhone }, theme: { color: '#0E5A4A' },
      handler: resolve, modal: { ondismiss: () => reject(new Error('The payment window was closed before paying.')) },
    });
    rz.on('payment.failed', (r) => reject(new Error(r.error && r.error.description ? r.error.description : 'The payment failed.')));
    rz.open();
  });
  return api(`/bookings/${enc(b.pnr)}/pay`, { method: 'POST', body: resp });
}

async function checkout(pnr, id) {
  if (needLogin(id, 'your payment')) return;
  app.innerHTML = '<p class="loading">Loading your booking…</p>';
  let b;
  try { b = await api('/bookings/' + enc(pnr)); } catch (e) { return showError(e.message, id); }
  if (id !== navId) return;
  if (b.status === 'CONFIRMED') { location.hash = '#/ticket/' + pnr; return; }
  if (b.status !== 'PENDING_PAYMENT' || !b.payment) {
    app.innerHTML = `<div class="empty"><h2>This seat hold has ended</h2><p>The seats were released because the payment was not completed in time. No money was taken.</p><a class="btn primary" href="#/results?from=${enc(b.source)}&to=${enc(b.destination)}&date=${b.departure.slice(0, 10)}">Choose seats again</a></div>`;
    return;
  }

  const demo = b.payment.provider !== 'RAZORPAY';
  app.innerHTML = `
    <div class="split">
      <section class="panel stack">
        <div><h2>Complete your payment</h2><p class="h-sub">Your seats are held for</p><div class="countdown" id="cd" role="timer" aria-live="off">--:--</div></div>
        <div class="fare-lines">
          <div><span>Fare (${b.seats.length} seat${b.seats.length > 1 ? 's' : ''})</span><span>${money(b.base)}</span></div>
          ${b.discount > 0 ? `<div class="disc"><span>Coupon ${esc(b.coupon)}</span><span>− ${money(b.discount)}</span></div>` : ''}
          <div class="sum-total" style="margin:6px 0 0"><span>Total to pay</span><b>${money(b.total)}</b></div>
        </div>
        ${demo ? '<p class="note">This site is running in demo payment mode. No money is charged and no card details are needed.</p>' : '<p class="note">You will pay securely through Razorpay (UPI, cards, net banking, wallets).</p>'}
        <p class="err" id="payErr" role="alert"></p>
        <div class="pay-opts">
          <button class="btn gold wide" id="payBtn" type="button">Pay ${money(b.total)}</button>
          ${demo ? '<button class="linklike small-link" id="failBtn" type="button">Simulate a failed payment (demo only)</button>' : ''}
          <button class="linklike small-link" id="relBtn" type="button">Cancel and release my seats</button>
        </div>
      </section>
      <aside class="panel" aria-label="Trip summary">
        <h3>Your trip</h3>
        <div class="sum-route">${esc(b.source)} to ${esc(b.destination)}</div>
        <div class="sum-meta"><span>${fmtDate(b.departure)}, ${fmtTime(b.departure)} to ${fmtTime(b.arrival)}</span><span>${esc(b.busName)} (${typeLabel(b.busType)})</span>
          <span>Boarding: ${esc(b.boarding || '-')}</span><span>Dropping: ${esc(b.dropping || '-')}</span></div>
        ${b.seats.map((s) => `<div style="display:flex;justify-content:space-between;padding:6px 0;border-top:1px solid var(--line)"><span>Seat ${esc(s.label)}: ${esc(s.name)} (${s.age}, ${genderName(s.gender)})</span></div>`).join('')}
        <p class="h-sub" style="margin-top:12px">Ticket and updates go to ${esc(b.contactEmail)} and ${esc(b.contactPhone)}.</p>
      </aside>
    </div>`;

  let left = b.secondsLeft;
  const buttons = () => $$('#payBtn, #failBtn, #relBtn');
  const expired = () => {
    clearInterval(timer);
    $('#cd').textContent = '00:00';
    buttons().forEach((x) => { x.disabled = true; });
    $('#payErr').innerHTML = 'Your seat hold has expired and the seats were released. <a href="#/">Search again</a>';
  };
  const tick = () => {
    if (left <= 0) return expired();
    $('#cd').textContent = `${pad(Math.floor(left / 60))}:${pad(left % 60)}`;
    $('#cd').classList.toggle('low', left < 60);
    left -= 1;
  };
  const timer = setInterval(tick, 1000);
  tick();
  state.cleanup = () => clearInterval(timer);

  const pay = async (outcome) => {
    buttons().forEach((x) => { x.disabled = true; });
    $('#payErr').textContent = '';
    $('#payBtn').textContent = 'Processing payment…';
    try {
      if (demo) await sleep(800);
      await runPayment(b, outcome);
      toast('Payment received. Your ticket is confirmed.');
      location.hash = '#/ticket/' + pnr;
    } catch (e) {
      $('#payErr').textContent = e.message;
      if (e.status === 410 || e.status === 409) return expired();
      buttons().forEach((x) => { x.disabled = false; });
      $('#payBtn').textContent = `Pay ${money(b.total)}`;
    }
  };
  $('#payBtn').onclick = () => pay('success');
  if (demo) $('#failBtn').onclick = () => pay('fail');
  $('#relBtn').onclick = async () => {
    try { await api(`/bookings/${enc(pnr)}/abandon`, { method: 'POST' }); } catch { /* already released */ }
    toast('Your seats were released.');
    location.hash = '#/';
  };
}

/* =====================================================================
   TICKET: view, print, cancel (all or some seats), review
   ===================================================================== */
function ticketHtml(b) {
  const cancelled = b.status === 'CANCELLED';
  const active = b.seats.filter((s) => s.status === 'ACTIVE');
  return `
    <article class="ticket" aria-label="Ticket ${esc(b.pnr)}">
      <div class="tk-main">
        <div class="tk-head">
          <span class="pill ${cancelled ? 'cx' : b.status === 'CONFIRMED' ? 'ok' : 'warn'}">${cancelled ? 'Cancelled' : b.status === 'CONFIRMED' ? 'Confirmed' : 'Awaiting payment'}</span>
          <span class="tk-pnr">PNR<b>${esc(b.pnr)}</b></span>
        </div>
        <div class="tk-route">
          <div><small>From</small><strong>${esc(b.source)}</strong><span>${fmtTime(b.departure)}</span></div>
          <div class="tk-line" aria-hidden="true"></div>
          <div><small>To</small><strong>${esc(b.destination)}</strong><span>${fmtTime(b.arrival)}</span></div>
        </div>
        <dl class="tk-meta">
          <div><dt>Date</dt><dd>${esc(fmtDateLong(b.departure))}</dd></div>
          <div><dt>Bus</dt><dd>${esc(b.busName)} (${typeLabel(b.busType)})</dd></div>
          <div><dt>Bus number</dt><dd>${esc(b.busNumber)}</dd></div>
          <div><dt>Boarding point</dt><dd>${esc(b.boarding || '-')}</dd></div>
          <div><dt>Dropping point</dt><dd>${esc(b.dropping || '-')}</dd></div>
          <div><dt>Operator</dt><dd>${esc(b.operatorName)}${b.operatorPhone ? '<br><small>' + esc(b.operatorPhone) + '</small>' : ''}</dd></div>
        </dl>
        <table class="tk-pass">
          <thead><tr><th>Seat</th><th>Passenger</th><th>Age</th><th>Gender</th><th>Status</th></tr></thead>
          <tbody>${b.seats.map((p) => `<tr class="${p.status === 'CANCELLED' ? 'tk-seat-cx' : ''}"><td><b>${esc(p.label)}</b></td><td>${esc(p.name)}</td><td>${p.age}</td><td>${genderName(p.gender)}</td><td>${p.status === 'CANCELLED' ? 'Cancelled' : 'Booked'}</td></tr>`).join('')}</tbody>
        </table>
        ${b.tripStatus === 'CANCELLED' ? '<p class="ticket-note bad">The operator cancelled this trip. Your full payment is being refunded.</p>' : ''}
        ${b.delayMin > 0 && b.tripStatus !== 'CANCELLED' ? `<p class="ticket-note">This bus is running about ${b.delayMin} minutes late.</p>` : ''}
      </div>
      <div class="tk-stub">
        <div><small>Total paid</small><strong>${money(b.total)}</strong>${b.discount > 0 ? `<small>after ${money(b.discount)} coupon discount</small>` : ''}</div>
        ${b.refunded > 0 ? `<div><small>Refunded</small><strong>${money(b.refunded)}</strong><small>To your original payment method, usually in 5 to 7 working days.</small></div>` : ''}
        <small>${active.length ? 'Show this PNR and a photo ID when you board.' : 'This ticket is no longer valid for travel.'}</small>
      </div>
    </article>`;
}

async function ticket(pnr, id) {
  if (needLogin(id, 'your ticket')) return;
  app.innerHTML = '<p class="loading">Loading ticket…</p>';
  let b;
  try { b = await api('/bookings/' + enc(pnr)); } catch (e) { return showError(e.message, id); }
  if (id !== navId) return;
  if (b.status === 'PENDING_PAYMENT') { location.hash = '#/checkout/' + pnr; return; }

  app.innerHTML = `
    <a class="back-link" href="#/trips">All my trips</a>
    <div style="margin-top:14px">${ticketHtml(b)}</div>
    <div class="tk-actions">
      <button class="btn" type="button" id="printBtn">Print ticket</button>
      ${b.canCancel ? '<button class="btn danger" type="button" id="cancelBtn">Cancel ticket</button>' : ''}
    </div>
    <div id="cancelSlot"></div>
    <div id="reviewSlot"></div>`;
  $('#printBtn').onclick = () => window.print();

  const cb = $('#cancelBtn');
  if (cb) cb.onclick = () => {
    const act = b.seats.filter((s) => s.status === 'ACTIVE');
    $('#cancelSlot').innerHTML = `
      <section class="cancel-panel" aria-label="Cancel seats">
        <h3>Which seats do you want to cancel?</h3>
        <p class="h-sub">Refund rate right now: <b>${b.refundPercentNow}%</b> of what you paid for each seat.</p>
        ${act.map((s) => `<label><input type="checkbox" value="${esc(s.label)}" data-refund="${s.refundIfCancelledNow}" checked> Seat ${esc(s.label)}: ${esc(s.name)} <span style="margin-left:auto">${money(s.refundIfCancelledNow)} back</span></label>`).join('')}
        <div class="sum"><span>Refund</span><span id="cxTotal"></span></div>
        <p class="err" id="cxErr" role="alert"></p>
        <button class="btn danger" id="cxConfirm" type="button"></button>
        <button class="btn" id="cxClose" type="button">Keep my ticket</button>
      </section>`;
    const boxes = $$('#cancelSlot input[type=checkbox]');
    const upd = () => {
      const chosen = boxes.filter((x) => x.checked);
      $('#cxTotal').textContent = money(chosen.reduce((a, x) => a + Number(x.dataset.refund), 0));
      $('#cxConfirm').textContent = chosen.length === boxes.length ? 'Cancel the whole booking' : `Cancel ${chosen.length} seat${chosen.length === 1 ? '' : 's'}`;
      $('#cxConfirm').disabled = !chosen.length;
    };
    boxes.forEach((x) => { x.onchange = upd; });
    upd();
    $('#cxClose').onclick = () => { $('#cancelSlot').innerHTML = ''; };
    $('#cxConfirm').onclick = async () => {
      $('#cxConfirm').disabled = true;
      try {
        const r = await api(`/bookings/${enc(pnr)}/cancel`, { method: 'POST', body: { seats: boxes.filter((x) => x.checked).map((x) => x.value) } });
        toast(r.refunded > b.refunded ? `Cancelled. ${money(r.refunded - b.refunded)} will be refunded.` : 'Cancelled. No refund applies.');
        ticket(pnr, id);
      } catch (e) { $('#cxErr').textContent = e.message; $('#cxConfirm').disabled = false; }
    };
  };

  if (b.canReview) {
    let rating = 0;
    $('#reviewSlot').innerHTML = `
      <section class="cancel-panel" aria-label="Rate your trip"><h3>How was your trip with ${esc(b.operatorName)}?</h3>
        <div class="starpick" role="group" aria-label="Rating">${[1, 2, 3, 4, 5].map((n) => `<button type="button" data-n="${n}" aria-label="${n} star${n > 1 ? 's' : ''}">★</button>`).join('')}</div>
        <textarea id="rvText" maxlength="500" placeholder="Tell other travellers about the bus, the driver and the journey (optional)"></textarea>
        <p class="err" id="rvErr" role="alert"></p>
        <button class="btn primary" id="rvSend" type="button">Submit review</button></section>`;
    $$('.starpick button').forEach((s) => { s.onclick = () => { rating = Number(s.dataset.n); $$('.starpick button').forEach((x) => x.classList.toggle('on', Number(x.dataset.n) <= rating)); }; });
    $('#rvSend').onclick = async () => {
      if (!rating) { $('#rvErr').textContent = 'Choose a star rating first.'; return; }
      try { await api(`/bookings/${enc(pnr)}/review`, { method: 'POST', body: { rating, text: $('#rvText').value } }); toast('Thank you for your review.'); ticket(pnr, id); }
      catch (e) { $('#rvErr').textContent = e.message; }
    };
  } else if (b.review) {
    $('#reviewSlot').innerHTML = `<section class="cancel-panel"><h3>Your review</h3><p><span class="stars">${stars(b.review.rating)}</span></p><p>${esc(b.review.text)}</p></section>`;
  }
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

  const groups = {
    upcoming: list.filter((b) => b.status === 'CONFIRMED' && b.canCancel).sort((a, b) => a.departure.localeCompare(b.departure)),
    completed: list.filter((b) => b.status === 'CONFIRMED' && !b.canCancel),
    cancelled: list.filter((b) => b.status === 'CANCELLED'),
  };
  let tab = groups.upcoming.length || !list.length ? 'upcoming' : groups.completed.length ? 'completed' : 'cancelled';

  app.innerHTML = `
    <div class="page-head"><h1 style="font-size:2rem">My trips</h1><a class="btn primary" href="#/">Book a bus</a></div>
    <div class="tabs" role="tablist">${['upcoming', 'completed', 'cancelled'].map((k) => `<a href="javascript:void(0)" data-tab="${k}" role="tab">${k[0].toUpperCase() + k.slice(1)} (${groups[k].length})</a>`).join('')}</div>
    <div id="tripBody"></div>`;
  const draw = () => {
    $$('.tabs a').forEach((a) => { if (a.dataset.tab === tab) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current'); });
    const rows = groups[tab];
    $('#tripBody').innerHTML = rows.length ? rows.map((b) => `
      <article class="trip-card">
        <div><div class="r">${esc(b.source)} to ${esc(b.destination)}</div>
          <div class="m">${fmtDate(b.departure)}, ${fmtTime(b.departure)} · ${b.seats.filter((s) => s.status === 'ACTIVE').length || b.seats.length} seat(s): ${b.seats.map((p) => esc(p.label)).join(', ')} · PNR ${esc(b.pnr)}</div>
          ${b.delayMin > 0 && tab === 'upcoming' ? `<div class="late">Running about ${b.delayMin} min late</div>` : ''}</div>
        <div style="display:flex;align-items:center;gap:14px;flex-wrap:wrap">
          <span class="pill ${b.status === 'CANCELLED' ? 'cx' : 'ok'}">${b.status === 'CANCELLED' ? 'Cancelled' : tab === 'completed' ? 'Completed' : 'Confirmed'}</span>
          <b>${money(b.total)}</b><a class="btn small" href="#/ticket/${esc(b.pnr)}">${b.canReview ? 'View and review' : 'View ticket'}</a></div>
      </article>`).join('')
      : `<div class="empty"><h2>Nothing here yet</h2><p>${tab === 'upcoming' ? 'Search for a bus and book your next seat.' : 'Trips will show up here as they happen.'}</p>${tab === 'upcoming' ? '<a class="btn primary" href="#/">Find a bus</a>' : ''}</div>`;
  };
  draw();
  $$('.tabs a').forEach((a) => { a.onclick = () => { tab = a.dataset.tab; draw(); }; });
}

/* =====================================================================
   PROFILE
   ===================================================================== */
async function profile(id) {
  if (needLogin(id, 'your profile')) return;
  const u = state.user;
  app.innerHTML = `
    <h1 style="font-size:2rem;margin-bottom:18px">My account</h1>
    <div class="cols-2">
      <form id="profForm" class="card-form panel" novalidate>
        <h3>Your details</h3>
        <label class="f">Full name<input name="name" value="${esc(u.name)}"></label>
        <label class="f">Mobile number<input name="phone" type="tel" value="${esc(u.phone || '')}"></label>
        <label class="f">Email<input value="${esc(u.email)}" disabled></label>
        <p>${u.emailVerified ? '<span class="pill ok">Email confirmed</span>' : '<span class="pill warn">Email not confirmed yet</span>'}</p>
        <p class="err" id="profErr" role="alert"></p>
        <button class="btn primary" type="submit">Save details</button>
      </form>
      <div class="stack">
        <form id="pwForm" class="card-form panel" novalidate>
          <h3>Change password</h3>
          <label class="f">Current password<input name="current" type="password" autocomplete="current-password"></label>
          <label class="f">New password<input name="next" type="password" autocomplete="new-password"></label>
          <p class="hint">At least 8 characters, with a letter and a number.</p>
          <p class="err" id="pwErr" role="alert"></p>
          <button class="btn primary" type="submit">Change password</button>
        </form>
        <div class="panel card-form"><h3>Security</h3><p class="h-sub">Signed in on a shared or lost device? Log out everywhere.</p>
          <button class="btn danger" id="outAll" type="button">Log out of all devices</button></div>
      </div>
    </div>`;
  $('#profForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    try { const r = await api('/me', { method: 'PUT', body: { name: e.target.elements.name.value, phone: e.target.elements.phone.value } }); setSession({ ...r }); toast('Details saved.'); $('#profErr').textContent = ''; }
    catch (ex) { $('#profErr').textContent = ex.message; }
  });
  $('#pwForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const el = e.target.elements;
    try { await api('/me/password', { method: 'POST', body: { current: el.current.value, next: el.next.value } }); toast('Password changed. Other devices were logged out.'); e.target.reset(); $('#pwErr').textContent = ''; }
    catch (ex) { $('#pwErr').textContent = ex.message; }
  });
  $('#outAll').onclick = async () => { try { await api('/me/logout-everywhere', { method: 'POST' }); } catch { /* ignore */ } setSession(null); toast('Logged out everywhere.'); location.hash = '#/'; route(); };
}

/* =====================================================================
   TRACK (no login), OFFERS, HELP
   ===================================================================== */
function trackPage(id) {
  app.innerHTML = `
    <h1 style="font-size:2rem">Track a booking</h1>
    <p class="h-sub">Enter the PNR and the email or mobile number used while booking. No login needed.</p>
    <form id="trkForm" class="card-form panel" style="margin:18px 0" novalidate>
      <label class="f">PNR<input name="pnr" placeholder="FCXXXXXXXX" autocomplete="off" style="text-transform:uppercase"></label>
      <label class="f">Email or mobile number<input name="contact" autocomplete="off"></label>
      <p class="err" id="trkErr" role="alert"></p>
      <button class="btn primary" type="submit">Find my booking</button>
    </form>
    <div id="trkOut"></div>`;
  $('#trkForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const el = e.target.elements;
    try {
      const b = await api('/track', { method: 'POST', body: { pnr: el.pnr.value, contact: el.contact.value } });
      if (id !== navId) return;
      $('#trkErr').textContent = '';
      $('#trkOut').innerHTML = ticketHtml(b) + '<div class="tk-actions"><button class="btn" type="button" id="trkPrint">Print ticket</button></div>';
      $('#trkPrint').onclick = () => window.print();
    } catch (ex) { $('#trkErr').textContent = ex.message; $('#trkOut').innerHTML = ''; }
  });
}

async function offersPage(id) {
  app.innerHTML = '<h1 style="font-size:2rem">Offers</h1><div id="ofBody"><p class="loading">Loading…</p></div>';
  try {
    const list = await api('/offers');
    if (id !== navId) return;
    $('#ofBody').innerHTML = list.length ? `<div class="offer-row">${list.map((o) => `<div class="offer"><b>${esc(o.code)}</b><span>${esc(o.description)}</span>${o.validTo ? `<small>Valid until ${esc(o.validTo)}</small>` : ''}<button class="btn small" type="button" data-copy="${esc(o.code)}">Copy code</button></div>`).join('')}</div><p class="hint">Enter the code on the passenger details page, before payment.</p>`
      : '<div class="empty"><h2>No offers right now</h2><p>Check back soon.</p></div>';
    $$('[data-copy]').forEach((b) => { b.onclick = async () => { try { await navigator.clipboard.writeText(b.dataset.copy); toast(`Copied ${b.dataset.copy}`); } catch { toast(`Your code: ${b.dataset.copy}`); } }; });
  } catch (e) { showError(e.message, id); }
}

function helpPage() {
  app.innerHTML = `
    <h1 style="font-size:2rem">Help and policies</h1>
    <div class="stack" style="max-width:760px;margin-top:18px">
      <section class="panel"><h3>How booking works</h3>
        <p>Search a route, choose your seats, add passenger details and pay. Once you continue to payment we hold your seats for ${state.config.holdMinutes} minutes. If the payment is not completed in that time, the seats are released and nothing is charged.</p></section>
      <section class="panel policy"><h3>Cancellation and refunds</h3><dl>${policyRows}</dl>
        <p class="h-sub" style="margin-top:12px">You can cancel the whole booking or only some seats from the ticket page. If an operator cancels a trip, every passenger is refunded in full automatically. Refunds go back to the original payment method, usually in 5 to 7 working days.</p></section>
      <section class="panel"><h3>Women-only seats</h3><p>Seats marked W are kept for women passengers and can only be booked with a female passenger.</p></section>
      <section class="panel"><h3>Boarding</h3><p>Reach your boarding point at least 15 minutes early and carry your PNR and a photo ID. Bookings close ${15} minutes before departure.</p></section>
      <section class="panel"><h3>Forgot your password?</h3><p>Use <a href="#/forgot">Forgot password</a> and we will email you a link that works for 30 minutes.</p></section>
    </div>`;
}

/* =====================================================================
   FORGOT / RESET PASSWORD, VERIFY EMAIL
   ===================================================================== */
function forgotPage() {
  app.innerHTML = `
    <h1 style="font-size:2rem">Forgot your password?</h1>
    <p class="h-sub">Enter your account email and we will send you a link to choose a new password.</p>
    <form id="fgForm" class="card-form panel" style="margin-top:18px" novalidate>
      <label class="f">Email<input name="email" type="email" autocomplete="email"></label>
      <p class="err" id="fgErr" role="alert"></p>
      <div id="fgOk"></div>
      <button class="btn primary" type="submit">Send reset link</button>
      <a href="#/">Back to home</a>
    </form>`;
  $('#fgForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const btn = $('button[type=submit]', e.target);
    btn.disabled = true;
    try {
      const r = await api('/auth/forgot', { method: 'POST', body: { email: e.target.elements.email.value.trim() } });
      $('#fgErr').textContent = '';
      $('#fgOk').innerHTML = `<p class="ok-box">${esc(r.message)}</p>${state.config.emailLogMode ? '<p class="ticket-note">This server has no email service connected yet, so the link is printed in the server console and in Admin, Outbox.</p>' : ''}`;
    } catch (ex) { $('#fgErr').textContent = ex.message; $('#fgOk').innerHTML = ''; }
    btn.disabled = false;
  });
}

function resetPage(token) {
  app.innerHTML = `
    <h1 style="font-size:2rem">Choose a new password</h1>
    <form id="rsForm" class="card-form panel" style="margin-top:18px" novalidate>
      <label class="f">New password<input name="pw" type="password" autocomplete="new-password"></label>
      <label class="f">Repeat new password<input name="pw2" type="password" autocomplete="new-password"></label>
      <p class="hint">At least 8 characters, with a letter and a number.</p>
      <p class="err" id="rsErr" role="alert"></p>
      <button class="btn primary" type="submit">Save new password</button>
    </form>`;
  $('#rsForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const el = e.target.elements;
    if (!token) { $('#rsErr').innerHTML = 'This link is incomplete. <a href="#/forgot">Request a new one</a>.'; return; }
    if (el.pw.value !== el.pw2.value) { $('#rsErr').textContent = 'The two passwords do not match.'; return; }
    try {
      await api('/auth/reset', { method: 'POST', body: { token, password: el.pw.value } });
      app.innerHTML = '<div class="empty"><h2>Password changed</h2><p>You were logged out everywhere. Log in with your new password.</p><button class="btn primary" id="rsLogin" type="button">Log in</button></div>';
      $('#rsLogin').onclick = () => { location.hash = '#/'; openAuth(); };
    } catch (ex) { $('#rsErr').innerHTML = esc(ex.message) + ' <a href="#/forgot">Request a new link</a>'; }
  });
}

async function verifyPage(token, id) {
  app.innerHTML = '<p class="loading">Confirming your email…</p>';
  try {
    await api('/auth/verify', { method: 'POST', body: { token } });
    if (id !== navId) return;
    if (state.user) state.user.emailVerified = true;
    renderHeader();
    app.innerHTML = '<div class="empty"><h2>Email confirmed</h2><p>Thank you. Your tickets will be sent to this address.</p><a class="btn primary" href="#/">Book a bus</a></div>';
  } catch (e) { if (id === navId) app.innerHTML = `<div class="empty"><h2>This link did not work</h2><p>${esc(e.message)}</p><a class="btn primary" href="#/profile">Go to my account</a></div>`; }
}

/* =====================================================================
   START
   ===================================================================== */
(async function init() {
  try { state.config = await api('/config'); } catch { /* defaults */ }
  try { state.cities = await api('/cities'); } catch { /* the form still works without suggestions */ }
  if (state.token) {
    try { setSession({ ...(await api('/me')), token: state.token }); } catch { setSession(null); }
  }
  route();
})();
