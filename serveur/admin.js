// Gestion des comptes Wasla (le serveur doit etre lance)
// node admin.js ajouter nom motdepasse
// node admin.js bloquer nom | debloquer nom | supprimer nom | liste
const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 3100;
let key = '';
try { key = fs.readFileSync(path.join(__dirname, 'admin.key'), 'utf8').trim(); } catch (e) {}
if (!key) { console.log('Lance le serveur une premiere fois (bash wasla.sh).'); process.exit(1); }

const [cmd, user, pass] = process.argv.slice(2);
const body = JSON.stringify({ key, cmd, user, pass });
const req = http.request({ host: '127.0.0.1', port: PORT, path: '/admin', method: 'POST',
  headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body) } }, res => {
  let out = '';
  res.on('data', c => out += c);
  res.on('end', () => console.log(out));
});
req.on('error', () => console.log('Serveur Wasla eteint. Lance-le avec : bash wasla.sh'));
req.end(body);
