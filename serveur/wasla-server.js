// Wasla : serveur de messagerie (Node + WebSocket)
// Separe de MonChat : son propre dossier, son propre port (3100), ses propres donnees.
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 3100;
const DB = path.join(__dirname, 'data.json');
const KEYFILE = path.join(__dirname, 'admin.key');

let db = { users: {}, tokens: {}, msgs: [], calls: [] };
try { db = Object.assign(db, JSON.parse(fs.readFileSync(DB, 'utf8'))); } catch (e) {}
if (!db.calls) db.calls = [];

// Serveurs STUN/TURN pour les appels (ice.json optionnel, sinon STUN Google)
function iceServers() {
  try { return JSON.parse(fs.readFileSync(path.join(__dirname, 'ice.json'), 'utf8')); }
  catch (e) { return [{ urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'] }]; }
}

let ADMIN = '';
try { ADMIN = fs.readFileSync(KEYFILE, 'utf8').trim(); } catch (e) {}
if (!ADMIN) { ADMIN = crypto.randomBytes(24).toString('hex'); fs.writeFileSync(KEYFILE, ADMIN, { mode: 0o600 }); }

let saveT = null;
function save() {
  clearTimeout(saveT);
  saveT = setTimeout(saveNow, 300);
}
function saveNow() {
  fs.writeFileSync(DB + '.tmp', JSON.stringify(db));
  fs.renameSync(DB + '.tmp', DB);
}
process.on('SIGINT', () => { saveNow(); process.exit(0); });
process.on('SIGTERM', () => { saveNow(); process.exit(0); });

function hash(pass, salt) { return crypto.scryptSync(pass, salt, 32).toString('hex'); }

const socks = new Map(); // nom -> Set de connexions
function send(ws, o) { if (ws.readyState === 1) ws.send(JSON.stringify(o)); }
function toUser(u, o) {
  const s = socks.get(u);
  if (!s || !s.size) return false;
  s.forEach(w => send(w, o));
  return true;
}
function visible(u) { return socks.has(u) && socks.get(u).size > 0 && !db.users[u].invisible; }
function contactsFor(u) {
  return Object.keys(db.users)
    .filter(x => x !== u && !db.users[x].blocked)
    .sort()
    .map(x => ({ user: x, online: visible(x) }));
}
function pushContacts() { for (const u of socks.keys()) toUser(u, { t: 'contacts', list: contactsFor(u) }); }

function kick(u) {
  const s = socks.get(u);
  if (s) s.forEach(w => { send(w, { t: 'err', msg: 'Compte bloque', fatal: true }); setTimeout(() => w.close(), 200); });
  for (const [tok, owner] of Object.entries(db.tokens)) if (owner === u) delete db.tokens[tok];
}

// ---------- Administration (seulement avec la cle admin.key) ----------
function admin(o) {
  const u = String(o.user || '').trim().toLowerCase();
  switch (o.cmd) {
    case 'ajouter': {
      if (!u || !o.pass) return 'Usage : node admin.js ajouter nom motdepasse';
      const existed = !!db.users[u];
      const salt = crypto.randomBytes(16).toString('hex');
      db.users[u] = Object.assign(db.users[u] || { blocked: false, invisible: false }, { salt, hash: hash(String(o.pass), salt) });
      save(); pushContacts();
      return existed ? 'Mot de passe change pour ' + u : 'Compte cree : ' + u;
    }
    case 'bloquer':
      if (!db.users[u]) return 'Compte introuvable : ' + u;
      db.users[u].blocked = true; kick(u); save(); pushContacts();
      return 'Compte bloque : ' + u;
    case 'debloquer':
      if (!db.users[u]) return 'Compte introuvable : ' + u;
      db.users[u].blocked = false; save(); pushContacts();
      return 'Compte debloque : ' + u;
    case 'supprimer':
      if (!db.users[u]) return 'Compte introuvable : ' + u;
      kick(u); delete db.users[u]; save(); pushContacts();
      return 'Compte supprime : ' + u;
    case 'liste':
      return Object.keys(db.users).sort().map(x =>
        (visible(x) ? '[en ligne] ' : socks.has(x) ? '[invisible] ' : '[hors ligne] ') + x + (db.users[x].blocked ? ' (BLOQUE)' : '')
      ).join('\n') || 'Aucun compte';
  }
  return 'Commandes : ajouter, bloquer, debloquer, supprimer, liste';
}

const server = http.createServer((req, res) => {
  if (req.method === 'POST' && req.url === '/admin') {
    let body = '';
    req.on('data', c => { body += c; if (body.length > 10000) req.destroy(); });
    req.on('end', () => {
      let o = {};
      try { o = JSON.parse(body); } catch (e) {}
      const ok = typeof o.key === 'string' && o.key.length === ADMIN.length &&
        crypto.timingSafeEqual(Buffer.from(o.key), Buffer.from(ADMIN));
      res.writeHead(ok ? 200 : 403, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end(ok ? admin(o) : 'Refuse');
    });
    return;
  }
  res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('Wasla serveur OK');
});

const wss = new WebSocketServer({ server, path: '/ws' });

wss.on('connection', ws => {
  ws.user = null;
  ws.alive = true;
  ws.on('pong', () => { ws.alive = true; });
  ws.on('message', raw => {
    let o;
    try { o = JSON.parse(raw); } catch (e) { return; }
    ws.alive = true;
    handle(ws, o);
  });
  ws.on('close', () => {
    if (!ws.user) return;
    const s = socks.get(ws.user);
    if (s) { s.delete(ws); if (!s.size) socks.delete(ws.user); }
    if (!socks.has(ws.user)) {
      for (const c of [...active.values()]) {
        if (c.state === 'active' && involves(c, ws.user)) endCall(c, 'lost', 'answered');
        else if (c.state === 'ringing' && c.from === ws.user) endCall(c, 'cancel', 'missed');
      }
    }
    pushContacts();
  });
});

setInterval(() => {
  wss.clients.forEach(w => {
    if (!w.alive) return w.terminate();
    w.alive = false;
    try { w.ping(); } catch (e) {}
  });
}, 45000);

// ---------- Appels ----------
const active = new Map(); // callId -> appel en cours
function involves(c, u) { return c.from === u || c.to === u; }
function busy(u) { for (const c of active.values()) if (involves(c, u)) return true; return false; }
function callsFor(u) { return db.calls.filter(c => involves(c, u)).slice(-50); }
function logCall(c, status) {
  const rec = { id: c.id, from: c.from, to: c.to, video: !!c.video, ts: c.ts, status, seen: status !== 'missed' };
  if (c.answeredAt) rec.dur = Math.round((Date.now() - c.answeredAt) / 1000);
  db.calls.push(rec);
  if (db.calls.length > 3000) db.calls.splice(0, db.calls.length - 3000);
  save();
  toUser(c.from, { t: 'call_log', call: rec });
  toUser(c.to, { t: 'call_log', call: rec });
}
function endCall(c, reason, status) {
  if (!active.has(c.id)) return;
  clearTimeout(c.timer);
  active.delete(c.id);
  toUser(c.from, { t: 'call_end', callId: c.id, reason });
  toUser(c.to, { t: 'call_end', callId: c.id, reason });
  logCall(c, status);
}

function fail(ws, msg) {
  send(ws, { t: 'err', msg, fatal: true });
  setTimeout(() => ws.close(), 200);
}

function attach(ws, u, extra) {
  ws.user = u;
  if (!socks.has(u)) socks.set(u, new Set());
  socks.get(u).add(ws);
  send(ws, Object.assign({ t: extra.token ? 'login_ok' : 'auth_ok', user: u, invisible: !!db.users[u].invisible }, extra));
  send(ws, { t: 'contacts', list: contactsFor(u) });
  send(ws, { t: 'ice_servers', list: iceServers() });
  send(ws, { t: 'calls', list: callsFor(u) });
  // Livrer les messages recus pendant l'absence
  let n = 0;
  for (const m of db.msgs) {
    if (m.to === u && !m.delivered) {
      send(ws, Object.assign({ t: 'msg' }, m));
      m.delivered = true;
      toUser(m.from, { t: 'delivered', id: m.id });
      n++;
    }
  }
  if (n) save();
  pushContacts();
}

function handle(ws, o) {
  if (!ws.user) {
    if (o.t === 'login') {
      const u = String(o.user || '').trim().toLowerCase();
      const rec = db.users[u];
      if (!rec || rec.hash !== hash(String(o.pass || ''), rec.salt)) return fail(ws, 'Nom ou mot de passe incorrect');
      if (rec.blocked) return fail(ws, 'Compte bloque');
      const token = crypto.randomBytes(24).toString('hex');
      db.tokens[token] = u;
      save();
      return attach(ws, u, { token });
    }
    if (o.t === 'auth') {
      const u = db.tokens[o.token];
      if (!u || !db.users[u]) return fail(ws, 'Session expiree, reconnecte-toi');
      if (db.users[u].blocked) return fail(ws, 'Compte bloque');
      return attach(ws, u, {});
    }
    return;
  }

  const me = ws.user;
  if (!db.users[me] || db.users[me].blocked) return fail(ws, 'Compte bloque');

  switch (o.t) {
    case 'msg': {
      const to = String(o.to || '');
      const text = String(o.text || '').slice(0, 4000);
      const id = String(o.id || '').slice(0, 64);
      if (!db.users[to] || !text || !id) return;
      let m = db.msgs.find(x => x.id === id);
      if (!m) {
        m = { id, from: me, to, text, ts: Date.now(), delivered: false };
        db.msgs.push(m);
        if (db.msgs.length > 20000) db.msgs.splice(0, db.msgs.length - 20000);
      }
      send(ws, { t: 'sent', id, ts: m.ts });
      if (!m.delivered && toUser(to, Object.assign({ t: 'msg' }, m))) {
        m.delivered = true;
        toUser(me, { t: 'delivered', id });
      } else if (m.delivered) {
        send(ws, { t: 'delivered', id });
      }
      save();
      break;
    }
    case 'history': {
      const w = String(o.with || '');
      const list = db.msgs.filter(m => (m.from === me && m.to === w) || (m.from === w && m.to === me)).slice(-200);
      send(ws, { t: 'history', with: w, msgs: list });
      break;
    }
    case 'call': {
      const to = String(o.to || '');
      const id = String(o.callId || '').slice(0, 64);
      if (!db.users[to] || !id || active.has(id) || to === me) return;
      const c = { id, from: me, to, video: !!o.video, ts: Date.now(), state: 'ringing' };
      if (busy(me)) return send(ws, { t: 'call_end', callId: id, reason: 'busy_me' });
      if (busy(to)) { send(ws, { t: 'call_end', callId: id, reason: 'busy' }); return logCall(c, 'missed'); }
      active.set(id, c);
      if (db.users[to].blocked || !toUser(to, { t: 'call', callId: id, from: me, video: c.video })) {
        return endCall(c, 'offline', 'missed');
      }
      c.timer = setTimeout(() => endCall(c, 'timeout', 'missed'), 45000);
      send(ws, { t: 'ringing', callId: id });
      break;
    }
    case 'accept': {
      const c = active.get(String(o.callId || ''));
      if (!c || c.to !== me || c.state !== 'ringing') return;
      clearTimeout(c.timer);
      c.state = 'active';
      c.answeredAt = Date.now();
      toUser(c.from, { t: 'accepted', callId: c.id, video: !!o.video });
      break;
    }
    case 'reject': {
      const c = active.get(String(o.callId || ''));
      if (!c || c.to !== me || c.state !== 'ringing') return;
      endCall(c, 'refused', 'refused');
      break;
    }
    case 'hangup': {
      const c = active.get(String(o.callId || ''));
      if (!c || !involves(c, me)) return;
      endCall(c, 'hangup', c.state === 'active' ? 'answered' : 'missed');
      break;
    }
    case 'sdp':
    case 'ice':
    case 'video': {
      const c = active.get(String(o.callId || ''));
      if (!c || !involves(c, me)) return;
      const peer = c.from === me ? c.to : c.from;
      const out = Object.assign({}, o, { from: me });
      toUser(peer, out);
      break;
    }
    case 'calls_seen':
      for (const c of db.calls) if (c.to === me && !c.seen) c.seen = true;
      save();
      break;
    case 'invisible':
      db.users[me].invisible = !!o.on;
      save();
      toUser(me, { t: 'invisible', on: !!o.on });
      pushContacts();
      break;
  }
}

server.listen(PORT, () => console.log('Wasla serveur sur le port ' + PORT));
