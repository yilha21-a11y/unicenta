// Test local du portail dans un VRAI navigateur sans fenetre (Chrome/Edge, protocole DevTools) :
// chiffrement WebCrypto, connexion, tableau de bord, captures d'ecran format telephone.
// Usage : node --experimental-websocket navigateur-test.mjs CHROME URL DOSSIER_CAPTURES
// Identifiants (base de TEST) lus dans les variables d'environnement DEMO_REST, DEMO_USER, DEMO_PWD, DEMO_CODE_FILE.
import { spawn } from 'node:child_process';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const [chrome, url, outDir] = process.argv.slice(2);
const profile = mkdtempSync(join(tmpdir(), 'olpos-nav-'));
const port = 9333;
const proc = spawn(chrome, ['--headless=new', '--disable-gpu', '--no-first-run', '--user-data-dir=' + profile,
  '--remote-debugging-port=' + port, 'about:blank'], { stdio: 'ignore' });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let target;
for (let i = 0; i < 50 && !target; i++) {
  await sleep(300);
  try {
    const list = await (await fetch('http://127.0.0.1:' + port + '/json')).json();
    target = list.find((t) => t.type === 'page');
  } catch (e) { /* pas encore pret */ }
}
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r) => { ws.onopen = r; });
let id = 0;
const waiting = new Map();
ws.onmessage = (m) => {
  const d = JSON.parse(m.data);
  if (d.id && waiting.has(d.id)) { waiting.get(d.id)(d); waiting.delete(d.id); }
};
const send = (method, params = {}) => new Promise((r) => { const i = ++id; waiting.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
const evaluate = async (expr) => (await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true })).result.result.value;
const shot = async (name) => {
  const r = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true });
  writeFileSync(join(outDir, name), Buffer.from(r.result.data, 'base64'));
};
const log = (...a) => console.log(...a);

await send('Page.enable');
await send('Runtime.enable');
await send('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
await send('Page.navigate', { url });
await sleep(1500);
log('Page :', await evaluate('document.title'));
log('WebCrypto disponible :', await evaluate('!!(window.crypto && crypto.subtle)'));
await shot('portail-connexion.png');

const fill = async (pwd) => {
  const code = readFileSync(process.env.DEMO_CODE_FILE, 'utf8').trim();
  await evaluate(`(function(){
    document.querySelector('#rest').value=${JSON.stringify(process.env.DEMO_REST)};
    document.querySelector('#user').value=${JSON.stringify(process.env.DEMO_USER)};
    document.querySelector('#pwd').value=${JSON.stringify(pwd)};
    document.querySelector('#otp').value=${JSON.stringify(code)};
    document.querySelector('#go').click(); return true; })()`);
  for (let i = 0; i < 40; i++) {
    await sleep(500);
    const state = await evaluate(`(function(){var b=document.querySelector('#banner');
      if (document.querySelector('.kpis')) return 'TABLEAU';
      if (b && !b.hidden && b.className==='err') return 'ERREUR: '+b.textContent; return '';})()`);
    if (state) return state;
  }
  return 'DELAI DEPASSE';
};

// 1. mauvais mot de passe
log('Mauvais mot de passe ->', await fill('mot-de-passe-faux'));
await shot('portail-refus.png');
await sleep(31000);   // nouveau code a 6 chiffres (un code ne sert qu'une fois)
// 2. bon mot de passe
log('Bon mot de passe ->', await fill(process.env.DEMO_PWD));
await sleep(1000);
log('Champ mot de passe vide apres envoi :', await evaluate("!document.querySelector('#pwd') || document.querySelector('#pwd').value===''"));
log('Statut :', await evaluate("document.querySelector('.status') && document.querySelector('.status').innerText"));
log('Ventes :', await evaluate("Array.from(document.querySelectorAll('.kpi')).map(k=>k.innerText.replace(/\\n/g,' ')).join(' | ')"));
await shot('portail-resume.png');
for (const tab of ['produits', 'tickets', 'clotures', 'encours']) {
  await evaluate(`document.querySelector('[data-tab=${tab}]').click()`);
  await sleep(400);
  await shot('portail-' + tab + '.png');
}
await evaluate("document.querySelector('[data-tab=tickets]').click()");
await sleep(300);
await evaluate("document.querySelector('tr.click').click()");
await sleep(800);
log('Detail ticket :', await evaluate("document.querySelector('#detail h2') && document.querySelector('#detail h2').innerText"));
await shot('portail-ticket.png');
// lecture seule : une ecriture est refusee
log('POST sur summary ->', await evaluate("fetch('/api/patron/summary',{method:'POST',credentials:'same-origin',body:'{}'}).then(r=>r.status)"));
// semaine
await evaluate("document.querySelector('[data-p=week]').click()");
await sleep(1200);
log('Semaine :', await evaluate("Array.from(document.querySelectorAll('.kpi')).slice(0,1).map(k=>k.innerText.replace(/\\n/g,' ')).join('')"));
// deconnexion
await evaluate("document.querySelector('#logout').click()");
await sleep(800);
log('Apres deconnexion, /me ->', await evaluate("fetch('/api/patron/me',{credentials:'same-origin'}).then(r=>r.status)"));
const errors = await evaluate('window.__errs || null');
log('Erreurs JS :', errors);
ws.close();
proc.kill();
await sleep(500);
try { rmSync(profile, { recursive: true, force: true }); } catch (e) { /* profil temporaire */ }
