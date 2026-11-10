'use strict';

/* =====================================================================
   First Choice - admin panel (loaded before app.js; uses its helpers)
   ===================================================================== */

const ADMIN_TABS = [['report', 'Dashboard'], ['trips', 'Trips'], ['services', 'Timetable'], ['buses', 'Buses'], ['operators', 'Operators'],
  ['coupons', 'Coupons'], ['bookings', 'Bookings'], ['users', 'Users'], ['outbox', 'Outbox'], ['refunds', 'Refund issues']];

const dt = (iso) => `${fmtDate(iso)}, ${fmtTime(iso)}`;
const statusPill = (s) => `<span class="pill ${s === 'CANCELLED' || s === 'FAILED' ? 'cx' : s === 'CONFIRMED' || s === 'SENT' || s === 'LOGGED' || s === 'PROCESSED' || s === 'ACTIVE' ? 'ok' : 'warn'}">${esc(s)}</span>`;
const table = (heads, rows) => `<div class="table-wrap"><table class="t"><thead><tr>${heads.map((h) => `<th${h.startsWith('#') ? ' class="num"' : ''}>${esc(h.replace(/^#/, ''))}</th>`).join('')}</tr></thead><tbody>${rows.join('') || `<tr><td colspan="${heads.length}" style="color:var(--muted)">Nothing to show.</td></tr>`}</tbody></table></div>`;
const row = (cells) => `<tr>${cells.map((c) => (c && c.num !== undefined) ? `<td class="num">${c.num}</td>` : `<td>${c}</td>`).join('')}</tr>`;
const num = (v) => ({ num: v });

function parsePoints(text) {
  return String(text || '').split('\n').map((l) => l.trim()).filter(Boolean).map((l) => {
    const [name, off] = l.split('|');
    return { name: name.trim(), offset: Number((off || '0').trim()) || 0 };
  });
}

async function adminView(tab, id) {
  if (needLogin(id, 'the admin area')) return;
  if (state.user.role !== 'ADMIN') return showError('This area is only for administrators.', id);
  app.innerHTML = `
    <h1 style="font-size:2rem">Admin</h1>
    <nav class="tabs" aria-label="Admin sections">${ADMIN_TABS.map(([k, l]) => `<a href="#/admin/${k}"${k === tab ? ' aria-current="page"' : ''}>${l}</a>`).join('')}</nav>
    <div id="adminBody"><p class="loading">Loading…</p></div>`;
  const tabs = { report: adminReport, trips: adminTrips, services: adminServices, buses: adminBuses, operators: adminOperators,
    coupons: adminCoupons, bookings: adminBookings, users: adminUsers, outbox: adminOutbox, refunds: adminRefunds };
  try { await (tabs[tab] || adminReport)($('#adminBody'), id); }
  catch (e) { if (id === navId) $('#adminBody').innerHTML = `<div class="empty"><h2>That did not work</h2><p>${esc(e.message)}</p></div>`; }
}

/* ---------- dashboard ---------- */
async function adminReport(body, id) {
  const to = ymd(new Date()), from = ymd(addDays(new Date(), -29));
  body.innerHTML = `<form class="date-row" id="rpForm"><label class="f">From<input type="date" name="from" value="${from}"></label>
    <label class="f">To<input type="date" name="to" value="${to}"></label><button class="btn primary" type="submit">Show</button></form><div id="rpOut"></div>`;
  const load = async () => {
    const el = $('#rpForm').elements;
    const r = await api(`/admin/report?from=${el.from.value}&to=${el.to.value}`);
    if (id !== navId) return;
    $('#rpOut').innerHTML = `
      <div class="kpis">
        <div class="kpi"><small>Bookings</small><b>${r.bookings}</b></div><div class="kpi"><small>Seats sold</small><b>${r.seats}</b></div>
        <div class="kpi"><small>Gross sales</small><b>${money(r.gross)}</b></div><div class="kpi"><small>Refunded</small><b>${money(r.refunded)}</b></div>
        <div class="kpi"><small>Net revenue</small><b>${money(r.net)}</b></div><div class="kpi"><small>Registered users</small><b>${r.users}</b></div>
      </div>
      <h3 style="margin-bottom:10px">By route</h3>
      ${table(['Route', '#Bookings', '#Seats', '#Gross', '#Refunded', '#Net'], r.routes.map((x) => row([esc(x.route), num(x.bookings), num(x.seats), num(money(x.gross)), num(money(x.refunded)), num(money(x.net))])))}`;
  };
  $('#rpForm').addEventListener('submit', (e) => { e.preventDefault(); load().catch((ex) => toast(ex.message, 'error')); });
  await load();
}

/* ---------- trips: delay / cancel ---------- */
async function adminTrips(body, id) {
  body.innerHTML = `<form class="date-row" id="trForm"><label class="f">Travel date<input type="date" name="date" value="${ymd(new Date())}"></label><button class="btn primary" type="submit">Show trips</button></form><div id="trOut"></div>`;
  const load = async () => {
    const list = await api('/admin/trips?date=' + $('#trForm').elements.date.value);
    if (id !== navId) return;
    $('#trOut').innerHTML = table(['Departure', 'Route', 'Bus', 'Operator', '#Seats sold', '#Held', 'Status', 'Delay', 'Actions'],
      list.map((t) => row([dt(t.departure), `${esc(t.origin)} to ${esc(t.destination)}`, esc(t.bus_name), esc(t.operator_name), num(`${t.sold}/${t.capacity}`), num(t.held),
        statusPill(t.status), t.delay_min ? `${t.delay_min} min` : '-',
        t.status === 'CANCELLED' ? '' : `<button class="btn small mini" data-delay="${esc(t.id)}">Set delay</button> <button class="btn small mini danger" data-cancel="${esc(t.id)}">Cancel trip</button>`])));
    $$('[data-delay]').forEach((b) => { b.onclick = async () => {
      const m = prompt('How many minutes late is this bus? (0 clears the delay)', '0');
      if (m === null) return;
      try { await api(`/admin/trips/${b.dataset.delay}/delay`, { method: 'POST', body: { minutes: m } }); toast('Delay updated.'); load(); } catch (e) { toast(e.message, 'error'); }
    }; });
    $$('[data-cancel]').forEach((b) => { b.onclick = async () => {
      const reason = prompt('Cancel this trip? Every passenger is refunded in full and emailed. Reason (shown to passengers):', 'Bus unavailable');
      if (reason === null) return;
      try { const r = await api(`/admin/trips/${b.dataset.cancel}/cancel`, { method: 'POST', body: { reason } }); toast(`Trip cancelled. ${r.cancelledBookings} booking(s) refunded.`); load(); } catch (e) { toast(e.message, 'error'); }
    }; });
  };
  $('#trForm').addEventListener('submit', (e) => { e.preventDefault(); load().catch((ex) => toast(ex.message, 'error')); });
  await load();
}

/* ---------- timetable (recurring services) ---------- */
async function adminServices(body, id) {
  const [buses, services] = await Promise.all([api('/admin/buses'), api('/admin/services')]);
  if (id !== navId) return;
  const days = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
  body.innerHTML = `
    <form class="admin-form" id="svForm" novalidate><h3>Add a route to the timetable</h3>
      <label class="f full">Bus<select name="busId">${buses.map((b) => `<option value="${esc(b.id)}">${esc(b.name)} · ${esc(b.operator_name)} · ${typeLabel(b.bus_type)} ${b.layout.toLowerCase()} (${b.capacity})</option>`).join('')}</select></label>
      <label class="f">From city<input name="origin" list="cityList"></label><label class="f">To city<input name="destination" list="cityList"></label>
      <label class="f">Departure time<input name="departTime" type="time" value="07:00"></label>
      <label class="f">Journey time (minutes)<input name="durationMin" type="number" min="15" value="240"></label>
      <label class="f">Fare per seat (₹)<input name="fare" type="number" min="1" step="1"></label>
      <div class="full"><b>Runs on</b><div class="days">${days.map((d) => `<label><input type="checkbox" name="day" value="${d}" checked> ${d}</label>`).join('')}</div></div>
      <label class="f">Boarding points<textarea name="boarding" placeholder="One per line: Name | minutes after departure&#10;Barnala Bus Stand | 0&#10;Bypass Chowk | 15"></textarea></label>
      <label class="f">Dropping points<textarea name="dropping" placeholder="One per line: Name | minutes from arrival&#10;Sector 43 ISBT | 0&#10;Sector 17 | -20"></textarea></label>
      <datalist id="cityList">${state.cities.map((c) => `<option value="${esc(c)}">`).join('')}</datalist>
      <p class="err full" id="svErr" role="alert"></p>
      <div class="full"><button class="btn primary" type="submit">Add to timetable</button> <span class="hint">Trips for the next 30 days are created automatically.</span></div>
    </form>
    <h3 style="margin-bottom:10px">Current timetable</h3>
    ${table(['Route', 'Time', 'Bus', 'Operator', '#Fare', 'Days', 'Status', ''],
      services.map((s) => row([`${esc(s.origin)} to ${esc(s.destination)}`, esc(s.depart_time), esc(s.bus_name), esc(s.operator_name), num(money(s.fare)),
        s.run_days.split('').map((c, i) => (c === '1' ? days[i][0] : '·')).join(' '), s.active ? statusPill('ACTIVE') : '<span class="pill warn">PAUSED</span>',
        `<button class="btn small mini" data-toggle="${esc(s.id)}" data-active="${s.active ? 'false' : 'true'}">${s.active ? 'Pause' : 'Resume'}</button>`])))}`;
  $('#svForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const el = e.target.elements;
    const checked = $$('input[name=day]', e.target).filter((c) => c.checked).map((c) => c.value);
    const runDays = days.map((d) => (checked.includes(d) ? '1' : '0')).join('');
    try {
      await api('/admin/services', { method: 'POST', body: { busId: el.busId.value, origin: el.origin.value, destination: el.destination.value, departTime: el.departTime.value,
        durationMin: el.durationMin.value, fare: el.fare.value, runDays, boardingPoints: parsePoints(el.boarding.value), droppingPoints: parsePoints(el.dropping.value) } });
      toast('Added to the timetable. Trips were created.');
      adminView('services', id);
    } catch (ex) { $('#svErr').textContent = ex.message; }
  });
  $$('[data-toggle]').forEach((b) => { b.onclick = async () => { try { await api(`/admin/services/${b.dataset.toggle}/active`, { method: 'POST', body: { active: b.dataset.active } }); adminView('services', id); } catch (e) { toast(e.message, 'error'); } }; });
}

/* ---------- buses ---------- */
async function adminBuses(body, id) {
  const [ops, buses] = await Promise.all([api('/admin/operators'), api('/admin/buses')]);
  if (id !== navId) return;
  body.innerHTML = `
    <form class="admin-form" id="bsForm" novalidate><h3>Add a bus</h3>
      <label class="f">Operator<select name="operatorId">${ops.map((o) => `<option value="${esc(o.id)}">${esc(o.name)}</option>`).join('')}</select></label>
      <label class="f">Registration number<input name="regNumber" placeholder="PB-10-AB-1234"></label>
      <label class="f">Bus name<input name="name"></label>
      <label class="f">Type<select name="busType"><option value="AC">AC</option><option value="NON_AC">Non-AC</option></select></label>
      <label class="f">Layout<select name="layout"><option value="SEATER">Seater (2+2)</option><option value="SLEEPER">Sleeper (lower + upper)</option></select></label>
      <label class="f">Total seats<input name="capacity" type="number" min="8" max="60" value="40"></label>
      <label class="f full">Amenities (comma separated)<input name="amenities" placeholder="WiFi, Charging point, Blanket, Water bottle"></label>
      <label class="f full">Women-only seats (comma separated labels)<input name="ladiesSeats" placeholder="Seater: 1,2,5,6   Sleeper: L1,L2"></label>
      <p class="err full" id="bsErr" role="alert"></p>
      <div class="full"><button class="btn primary" type="submit">Add bus</button></div>
    </form>
    ${table(['Bus', 'Registration', 'Operator', 'Type', 'Layout', '#Seats', 'Amenities'], buses.map((b) => row([esc(b.name), `<span class="mono">${esc(b.reg_number)}</span>`, esc(b.operator_name), typeLabel(b.bus_type), esc(b.layout.toLowerCase()), num(b.capacity), esc(b.amenities || '-')])))}`;
  $('#bsForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const el = e.target.elements;
    try {
      await api('/admin/buses', { method: 'POST', body: Object.fromEntries(['operatorId', 'regNumber', 'name', 'busType', 'layout', 'capacity', 'amenities', 'ladiesSeats'].map((k) => [k, el[k].value])) });
      toast('Bus added.'); adminView('buses', id);
    } catch (ex) { $('#bsErr').textContent = ex.message; }
  });
}

/* ---------- operators ---------- */
async function adminOperators(body, id) {
  const ops = await api('/admin/operators');
  if (id !== navId) return;
  body.innerHTML = `
    <form class="admin-form" id="opForm" novalidate><h3>Add a bus operator</h3>
      <label class="f">Operator name<input name="name"></label><label class="f">Contact phone<input name="phone" type="tel"></label><label class="f">About<input name="about"></label>
      <p class="err full" id="opErr" role="alert"></p><div class="full"><button class="btn primary" type="submit">Add operator</button></div></form>
    ${table(['Operator', 'Phone', 'About'], ops.map((o) => row([esc(o.name), esc(o.phone || '-'), esc(o.about || '-')])))}`;
  $('#opForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const el = e.target.elements;
    try { await api('/admin/operators', { method: 'POST', body: { name: el.name.value, phone: el.phone.value, about: el.about.value } }); toast('Operator added.'); adminView('operators', id); }
    catch (ex) { $('#opErr').textContent = ex.message; }
  });
}

/* ---------- coupons ---------- */
async function adminCoupons(body, id) {
  const list = await api('/admin/coupons');
  if (id !== navId) return;
  body.innerHTML = `
    <form class="admin-form" id="cpForm" novalidate><h3>Create a coupon</h3>
      <label class="f">Code<input name="code" placeholder="DIWALI20" style="text-transform:uppercase"></label>
      <label class="f">Type<select name="kind"><option value="PERCENT">Percent off</option><option value="FLAT">Flat amount off</option></select></label>
      <label class="f">Value (% or ₹)<input name="amount" type="number" min="1"></label>
      <label class="f full">Description shown to customers<input name="description" placeholder="20% off up to ₹200 on bookings of ₹400+"></label>
      <label class="f">Minimum booking (₹)<input name="minAmount" type="number" min="0" value="0"></label>
      <label class="f">Maximum discount (₹)<input name="maxDiscount" type="number" min="0" value="0"></label>
      <label class="f">Valid until<input name="validTo" type="date"></label>
      <label class="f">Usage limit (blank = unlimited)<input name="usageLimit" type="number" min="1"></label>
      <p class="err full" id="cpErr" role="alert"></p><div class="full"><button class="btn primary" type="submit">Create coupon</button></div></form>
    ${table(['Code', 'Offer', '#Min', '#Max discount', 'Valid until', '#Used', 'Status', ''],
      list.map((c) => row([`<b>${esc(c.code)}</b>`, esc(c.description), num(money(c.minAmount)), num(c.maxDiscount ? money(c.maxDiscount) : '-'), esc(c.validTo || 'No end date'),
        num(`${c.used}${c.usageLimit ? '/' + c.usageLimit : ''}`), c.active ? '<span class="pill ok">ACTIVE</span>' : '<span class="pill warn">OFF</span>',
        `<button class="btn small mini" data-code="${esc(c.code)}" data-active="${c.active ? 'false' : 'true'}">${c.active ? 'Turn off' : 'Turn on'}</button>`])))}`;
  $('#cpForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const el = e.target.elements;
    try {
      await api('/admin/coupons', { method: 'POST', body: Object.fromEntries(['code', 'kind', 'amount', 'description', 'minAmount', 'maxDiscount', 'validTo', 'usageLimit'].map((k) => [k, el[k].value])) });
      toast('Coupon created.'); adminView('coupons', id);
    } catch (ex) { $('#cpErr').textContent = ex.message; }
  });
  $$('[data-code]').forEach((b) => { b.onclick = async () => { try { await api(`/admin/coupons/${b.dataset.code}/active`, { method: 'POST', body: { active: b.dataset.active } }); adminView('coupons', id); } catch (e) { toast(e.message, 'error'); } }; });
}

/* ---------- bookings / users / outbox / refunds ---------- */
async function adminBookings(body, id) {
  body.innerHTML = `<form class="date-row" id="bkForm"><label class="f">PNR<input name="pnr" placeholder="FC..."></label>
    <label class="f">Status<select name="status"><option value="">All</option><option>CONFIRMED</option><option>CANCELLED</option><option>FAILED</option></select></label>
    <label class="f">Booked from<input type="date" name="from"></label><label class="f">to<input type="date" name="to"></label><button class="btn primary" type="submit">Search</button></form><div id="bkOut"></div>`;
  const load = async () => {
    const el = $('#bkForm').elements;
    const qs = ['pnr', 'status', 'from', 'to'].filter((k) => el[k].value).map((k) => `${k}=${enc(el[k].value)}`).join('&');
    const list = await api('/admin/bookings?' + qs);
    if (id !== navId) return;
    $('#bkOut').innerHTML = table(['PNR', 'Passenger', 'Contact', 'Route', 'Departure', '#Seats', '#Paid', '#Refunded', 'Status'],
      list.map((b) => row([`<a class="mono" href="#/ticket/${esc(b.pnr)}">${esc(b.pnr)}</a>`, esc(b.user), `${esc(b.email)}<br><small>${esc(b.phone)}</small>`, `${esc(b.source)} to ${esc(b.destination)}`,
        dt(b.departure), num(b.seats), num(money(b.total)), num(money(b.refunded)), statusPill(b.status)])));
  };
  $('#bkForm').addEventListener('submit', (e) => { e.preventDefault(); load().catch((ex) => toast(ex.message, 'error')); });
  await load();
}

async function adminUsers(body, id) {
  const list = await api('/admin/users');
  if (id !== navId) return;
  body.innerHTML = table(['Name', 'Email', 'Mobile', 'Role', 'Email confirmed', '#Bookings', 'Joined'],
    list.map((u) => row([esc(u.name), esc(u.email), esc(u.phone || '-'), esc(u.role), u.email_verified ? 'Yes' : 'No', num(u.bookings), fmtDate(u.created_at)])));
}

async function adminOutbox(body, id) {
  const list = await api('/admin/outbox');
  if (id !== navId) return;
  body.innerHTML = `${state.config.emailLogMode ? '<p class="ticket-note">Email is in log mode: nothing is sent to real inboxes. Connect an SMTP account (see README) to send real emails. Reset and verification links below are for testing only.</p>' : ''}
    <div class="stack" style="margin-top:14px">${list.map((m) => `<details class="panel"><summary><b>${esc(m.subject)}</b> · to ${esc(m.to_email)} · ${statusPill(m.mail_status)} · <small>${esc(String(m.created_at).replace('T', ' ').slice(0, 16))}</small></summary><p class="pre mono" style="margin-top:10px">${esc(m.body || '')}</p></details>`).join('') || '<div class="empty"><h2>No emails yet</h2></div>'}</div>`;
}

async function adminRefunds(body, id) {
  const list = await api('/admin/refunds');
  if (id !== navId) return;
  body.innerHTML = `<p class="h-sub" style="margin-bottom:12px">Refunds that the payment provider did not accept (FAILED) or that need manual handling (MANUAL). Refund these from your payment dashboard.</p>
    ${table(['PNR', '#Amount', 'Status', 'Reason', 'When'], list.map((r) => row([`<a class="mono" href="#/ticket/${esc(r.booking_pnr)}">${esc(r.booking_pnr)}</a>`, num(money(r.amount_paise / 100)), statusPill(r.refund_status), esc(r.reason || ''), esc(String(r.created_at).replace('T', ' ').slice(0, 16))])))}`;
}
