// Robot de test : se connecte avec un compte et repond automatiquement.
// node robot.js            (compte test / 1234)
// node robot.js nom motdepasse
// CTRL + C pour l'arreter (il passe alors hors ligne).
const WebSocket = require('ws');
const crypto = require('crypto');

const PORT = process.env.PORT || 3100;
const [user = 'test', pass = '1234'] = process.argv.slice(2);

function go() {
  const ws = new WebSocket('ws://127.0.0.1:' + PORT + '/ws');
  ws.on('open', () => ws.send(JSON.stringify({ t: 'login', user, pass })));
  ws.on('message', raw => {
    const o = JSON.parse(raw);
    if (o.t === 'login_ok') console.log('Robot connecte en tant que ' + o.user + '. CTRL + C pour arreter.');
    if (o.t === 'err') { console.log('Erreur : ' + o.msg); process.exit(1); }
    if (o.t === 'msg' && o.from !== user) {
      console.log(o.from + ' : ' + o.text);
      setTimeout(() => {
        const text = '🤖 Bien recu : « ' + o.text + ' »';
        ws.send(JSON.stringify({ t: 'msg', to: o.from, text, id: crypto.randomUUID() }));
        console.log('  -> reponse envoyee');
      }, 1500);
    }
  });
  ws.on('close', () => { console.log('Deconnecte, nouvel essai dans 3 s...'); setTimeout(go, 3000); });
  ws.on('error', () => {});
}
go();
