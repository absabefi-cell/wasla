#!/data/data/com.termux/files/usr/bin/bash
# Demarre le serveur Wasla (port 3100) et son tunnel. Ne touche pas a MonChat.
cd "$(dirname "$0")"
pkill -f "node wasla-server.js" 2>/dev/null
pkill -f "tunnel --url http://localhost:3100" 2>/dev/null
sleep 1
nohup node wasla-server.js > serveur.log 2>&1 &
rm -f tunnel.log
nohup cloudflared tunnel --url http://localhost:3100 > tunnel.log 2>&1 &
echo "Demarrage du tunnel..."
L=""
for i in $(seq 1 40); do
  L=$(grep -o 'https://[a-z0-9-]*\.trycloudflare\.com' tunnel.log 2>/dev/null | head -1)
  [ -n "$L" ] && break
  sleep 1
done
if [ -n "$L" ]; then
  echo "$L" > lien.txt
  echo ""
  echo "Adresse du serveur Wasla :"
  echo "$L"
else
  echo "Pas de lien. Regarde tunnel.log"
fi
