// Test de sonnerie : le compte test appelle quelqu'un.
// node appeler.js mouad          (appel audio, apres 10 s)
// node appeler.js mouad video 20 (appel video, apres 20 s)
// Si personne ne repond en 45 s : appel manque.
const WebSocket = require('ws');
const crypto = require('crypto');

const PORT = process.env.PORT || 3100;
const [to = 'mouad', mode = 'audio', delai = '10'] = process.argv.slice(2);
const user = process.env.WASLA_USER || 'test';
const pass = process.env.WASLA_PASS || '1234';

const ws = new WebSocket('ws://127.0.0.1:' + PORT + '/ws');
ws.on('open', () => ws.send(JSON.stringify({ t: 'login', user, pass })));
ws.on('message', raw => {
  const o = JSON.parse(raw);
  if (o.t === 'err') { console.log('Erreur : ' + o.msg); process.exit(1); }
  if (o.t === 'login_ok') {
    const s = parseFloat(delai) || 10;
    console.log('Appel ' + mode + ' vers ' + to + ' dans ' + s + ' s... (verrouille ton telephone)');
    setTimeout(() => {
      ws.send(JSON.stringify({ t: 'call', to, callId: crypto.randomUUID(), video: mode === 'video' }));
      console.log('Ca sonne!');
    }, s * 1000);
  }
  if (o.t === 'accepted') console.log('Appel accepte (le robot ne parle pas : raccroche quand tu veux).');
  if (o.t === 'call_end') {
    const r = { offline: 'hors ligne : appel manque enregistre', timeout: 'pas de reponse : appel manque', refused: 'appel refuse', hangup: 'raccroche' };
    console.log('Fin : ' + (r[o.reason] || o.reason));
    setTimeout(() => process.exit(0), 300);
  }
});
