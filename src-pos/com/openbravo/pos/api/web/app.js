// uniCenta - page web de prise de commande (servie par la caisse).
// Licence GPL v3. Aucune ressource externe : fonctionne sans Internet.
'use strict';

var API = '/api/v1';
var POLL_MS = 5000;

var S = {
  session: null,        // {token, user:{id,name}, permissions:[]}
  catalog: null,        // {categories, products}
  floors: null,
  screen: 'users',      // users | password | floors | table
  loginUser: null,
  table: null,          // {id, name}
  order: null,          // derniere commande lue
  category: null,
  sending: false,
  timer: null
};

// ------------------------------------------------------------------ outils

function $(sel) { return document.querySelector(sel); }
function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
    return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c];
  });
}
function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
function uuid() {
  // crypto.randomUUID n'existe pas en http sur iPhone : getRandomValues oui.
  var b = new Uint8Array(16);
  crypto.getRandomValues(b);
  b[6] = (b[6] & 0x0f) | 0x40; b[8] = (b[8] & 0x3f) | 0x80;
  var h = Array.prototype.map.call(b, function (x) { return (x + 256).toString(16).slice(1); }).join('');
  return h.slice(0, 8) + '-' + h.slice(8, 12) + '-' + h.slice(12, 16) + '-' + h.slice(16, 20) + '-' + h.slice(20);
}
function store(key, value) {
  try {
    if (value === null || value === undefined) { localStorage.removeItem(key); }
    else { localStorage.setItem(key, JSON.stringify(value)); }
  } catch (e) { /* navigation privee : on continue sans memoire */ }
}
function load(key) {
  try { var v = localStorage.getItem(key); return v ? JSON.parse(v) : null; } catch (e) { return null; }
}
function can(p) { return !!(S.session && S.session.permissions.indexOf(p) >= 0); }
function money(n) {
  return (Math.round(n * 100) / 100).toFixed(2).replace('.', ',') + ' \u20ac';
}

// ------------------------------------------------------------------ appels a la caisse

function NetworkError() { this.network = true; }

function api(method, path, body) {
  var ctrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
  var t = ctrl ? setTimeout(function () { ctrl.abort(); }, 10000) : null;
  var headers = {};
  if (S.session) { headers['Authorization'] = 'Bearer ' + S.session.token; }
  if (body !== undefined) { headers['Content-Type'] = 'application/json'; }
  return fetch(API + path, {
    method: method, headers: headers, cache: 'no-store',
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: ctrl ? ctrl.signal : undefined
  }).then(function (res) {
    if (t) { clearTimeout(t); }
    return res.text().then(function (txt) {
      var data = null;
      try { data = txt ? JSON.parse(txt) : null; } catch (e) { data = null; }
      if (res.status === 401 && S.session && path !== '/auth/login') {
        logout('Session expiree : reconnectez-vous.');
      }
      return {status: res.status, data: data};
    });
  }, function () {
    if (t) { clearTimeout(t); }
    throw new NetworkError();
  });
}

function errorText(r) {
  return r && r.data && r.data.error ? r.data.error.message : 'Erreur ' + (r ? r.status : '');
}

// ------------------------------------------------------------------ bandeau et ecran

function banner(text, kind, actionLabel, action) {
  var b = $('#banner');
  if (!text) { b.hidden = true; b.innerHTML = ''; return; }
  b.className = kind || '';
  b.innerHTML = esc(text) + (actionLabel ? ' <button class="btn" id="bannerAction">' + esc(actionLabel) + '</button>' : '');
  b.hidden = false;
  if (action) { $('#bannerAction').onclick = action; }
}

function header(title, back) {
  $('#title').textContent = title;
  $('#back').hidden = !back;
  $('#back').onclick = back || null;
  $('#who').textContent = S.session ? S.session.user.name : '';
  $('#logout').hidden = !S.session;
}

function closeSheet() { $('#sheet').hidden = true; $('#sheet').innerHTML = ''; }

function sheet(html) {
  var s = $('#sheet');
  s.innerHTML = '<div class="panel">' + html + '</div>';
  s.hidden = false;
  s.onclick = function (e) { if (e.target === s) { closeSheet(); } };
}

// ------------------------------------------------------------------ connexion

function showUsers() {
  S.screen = 'users';
  header('Qui etes-vous ?', null);
  $('#view').innerHTML = '<p class="muted">Chargement...</p>';
  api('GET', '/users').then(function (r) {
    if (r.status !== 200) { $('#view').innerHTML = '<div class="card">' + esc(errorText(r)) + '</div>'; return; }
    var users = r.data.users;
    if (!users.length) {
      $('#view').innerHTML = '<div class="card">Aucun serveur autorise. A la caisse, donnez un mot de passe '
        + 'et la permission mobile.Access au role du serveur.</div>';
      return;
    }
    $('#view').innerHTML = '<div class="list">' + users.map(function (u, i) {
      return '<button class="row" data-i="' + i + '"><span>' + esc(u.name) + '</span><span class="muted">&#8250;</span></button>';
    }).join('') + '</div>';
    Array.prototype.forEach.call(document.querySelectorAll('.list .row'), function (el) {
      el.onclick = function () { showPassword(users[+el.dataset.i]); };
    });
  }, function () {
    $('#view').innerHTML = '<div class="card">Impossible de joindre la caisse. Verifiez le Wi-Fi.</div>';
  });
}

function showPassword(user) {
  S.screen = 'password';
  S.loginUser = user;
  header(user.name, showUsers);
  $('#view').innerHTML = '<form class="card" id="loginForm"><label>Mot de passe<input type="password" id="pwd" '
    + 'autocomplete="current-password" autofocus></label><button class="btn primary" type="submit" style="width:100%">'
    + 'Se connecter</button></form>';
  $('#loginForm').onsubmit = function (e) {
    e.preventDefault();
    var btn = $('#loginForm button'); btn.disabled = true;
    api('POST', '/auth/login', {userId: user.id, password: $('#pwd').value}).then(function (r) {
      btn.disabled = false;
      if (r.status !== 200) { banner(errorText(r), 'error'); return; }
      S.session = {token: r.data.token, user: r.data.user, permissions: r.data.permissions};
      store('session', S.session);
      banner(null);
      showFloors();
    }, function () { btn.disabled = false; banner('Impossible de joindre la caisse.', 'error'); });
  };
}

function logout(message) {
  if (S.session) { api('POST', '/auth/logout', '').catch(function () {}); }
  S.session = null; S.catalog = null;
  store('session', null);
  stopPolling();
  closeSheet();
  showUsers();
  banner(message || null, message ? 'warn' : '');
}

// ------------------------------------------------------------------ plan de salle

function showFloors() {
  S.screen = 'floors'; S.table = null; S.order = null;
  header('Tables', null);
  closeSheet();
  refreshFloors();
  startPolling();
  if (!S.catalog) {
    api('GET', '/catalog').then(function (r) { if (r.status === 200) { S.catalog = r.data; } }, function () {});
  }
}

function refreshFloors() {
  return api('GET', '/floors').then(function (r) {
    if (S.screen !== 'floors') { return; }
    if (r.status !== 200) { banner(errorText(r), 'error'); return; }
    S.floors = r.data.floors;
    $('#view').innerHTML = S.floors.map(function (f) {
      return '<h2>' + esc(f.name) + '</h2><div class="grid">' + f.tables.map(function (t) {
        var pending = load('pending:' + t.id) ? ' &#9888;' : '';
        var draft = load('draft:' + t.id);
        var count = draft && draft.items.length ? ' <span class="tag">' + draft.items.length + ' a envoyer</span>' : '';
        return '<button class="table' + (t.occupied ? ' busy' : '') + '" data-id="' + esc(t.id) + '" data-name="'
          + esc(t.name) + '">' + esc(t.name) + pending + count + '<small>' + (t.occupied ? esc(t.waiter || 'occupee') : 'libre')
          + '</small></button>';
      }).join('') + '</div>';
    }).join('');
    Array.prototype.forEach.call(document.querySelectorAll('.table'), function (el) {
      el.onclick = function () { showTable({id: el.dataset.id, name: el.dataset.name}); };
    });
  }, function () { banner('Pas de reponse de la caisse (Wi-Fi ?). Nouvel essai automatique...', 'error'); });
}

// ------------------------------------------------------------------ commande d'une table

function draftOf(id) { return load('draft:' + id) || {items: []}; }
function saveDraft(id, d) { store('draft:' + id, d.items.length ? d : null); }

function showTable(t) {
  S.screen = 'table'; S.table = t; S.order = null;
  header(t.name, function () { banner(null); showFloors(); });
  closeSheet();
  banner(null);
  if (!S.catalog) {
    api('GET', '/catalog').then(function (r) { if (r.status === 200) { S.catalog = r.data; renderTable(); } }, function () {});
  }
  refreshOrder().then(function () {
    resumePending();
    if (load('bpending:' + t.id)) {
      banner('Une addition n\'a pas recu de reponse : statut A VERIFIER.', 'error', 'Verifier',
        function () { deliverBill(t.id); });
    }
    if (load('kpending:' + t.id)) {
      banner('Un envoi cuisine n\'a pas recu de reponse : statut A VERIFIER.', 'error', 'Verifier',
        function () { deliverKitchen(t.id); });
    }
  });
  startPolling();
}

function refreshOrder() {
  var t = S.table;
  return api('GET', '/tables/' + encodeURIComponent(t.id) + '/order').then(function (r) {
    if (S.table !== t) { return; }
    if (r.status !== 200) { banner(errorText(r), 'error'); return; }
    S.order = r.data.order;
    renderTable();
  }, function () {
    if (S.table === t) { banner('Pas de reponse de la caisse (Wi-Fi ?). Nouvel essai automatique...', 'error'); }
  });
}

function renderTable() {
  if (S.screen !== 'table') { return; }
  var o = S.order;
  var d = draftOf(S.table.id);
  var html = '';
  if (o && o.lockedBy) {
    html += '<div class="card" style="background:var(--busy-bg)">Table ouverte a la caisse par <b>' + esc(o.lockedBy.who)
      + '</b> : consultation seulement. Vos ajouts restent dans le brouillon.</div>';
  }
  // commande enregistree
  html += '<div class="card"><h2>Commande en cours' + (o && o.exists ? ' <span class="muted">(v' + o.version + ')</span>' : '') + '</h2>';
  if (!o) {
    html += '<p class="muted">Chargement...</p>';
  } else if (!o.lines.length) {
    html += '<p class="muted">Aucun article.</p>';
  } else {
    html += o.lines.map(function (l, i) {
      return '<div class="line"><div class="name"><b>' + esc(l.quantity) + ' &times; ' + esc(l.name) + '</b>'
        + kitchenTag(l.kitchenStatus)
        + (l.note ? '<div class="note">' + esc(l.note) + '</div>' : '')
        + '</div><div>' + money(l.total) + '</div>'
        + (can('sales.EditLines') && !l.sent ? '<button class="btn" data-line="' + i + '">&#9998;</button>' : '')
        + '</div>';
    }).join('');
    html += '<div class="total"><span>Total</span><span>' + esc(o.totalDisplay) + '</span></div>';
  }
  html += '</div>';
  html += kitchenCard(o);
  // brouillon
  if (d.items.length) {
    html += '<div class="card"><h2>A envoyer a la caisse</h2>' + d.items.map(function (it, i) {
      return '<div class="line"><div class="name"><b>' + esc(it.name) + '</b>'
        + (it.note ? '<div class="note">' + esc(it.note) + '</div>' : '') + '</div>'
        + '<div class="qty"><button data-dminus="' + i + '">&minus;</button><b>' + it.quantity
        + '</b><button data-dplus="' + i + '">+</button><button data-dnote="' + i + '">&#9998;</button></div></div>';
    }).join('') + '</div>';
  }
  // catalogue
  if (S.catalog) {
    var cats = S.catalog.categories.filter(function (c) {
      return S.catalog.products.some(function (p) { return p.categoryId === c.id; });
    });
    if (!S.category || !cats.some(function (c) { return c.id === S.category; })) { S.category = cats.length ? cats[0].id : null; }
    html += '<div class="tabs">' + cats.map(function (c) {
      return '<button data-cat="' + esc(c.id) + '"' + (c.id === S.category ? ' class="on"' : '') + '>' + esc(c.name) + '</button>';
    }).join('') + '</div><div class="products">' + S.catalog.products.filter(function (p) {
      return p.categoryId === S.category;
    }).map(function (p) {
      return '<button class="product" data-prod="' + esc(p.id) + '"><b>' + esc(p.name) + '</b><span class="muted">'
        + esc(p.priceDisplay) + '</span></button>';
    }).join('') + '</div>';
  }
  $('#view').innerHTML = html;
  var toKitchen = o ? o.lines.filter(function (l) { return l.kitchenStatus === 'a_envoyer'; }).length : 0;
  var kitchenOk = can('sales.PrintKitchen') && toKitchen > 0 && !d.items.length && !S.sending
    && !load('kpending:' + S.table.id);
  $('#view').insertAdjacentHTML('beforeend', '<div class="actions"><button class="btn primary" id="send"'
    + (d.items.length && !S.sending ? '' : ' disabled') + '>' + (S.sending ? 'Envoi...' : 'Envoyer a la caisse'
    + (d.items.length ? ' (' + d.items.length + ')' : '')) + '</button>'
    + (can('sales.PrintKitchen') ? '<button class="btn" id="kitchen"' + (kitchenOk ? '' : ' disabled') + '>Cuisine'
    + (toKitchen ? ' (' + toKitchen + ')' : '') + '</button>' : '')
    + (can('sales.PrintTicket') ? '<button class="btn" id="bill"' + (o && o.lines.length && !d.items.length
      && !S.sending && !load('bpending:' + S.table.id) ? '' : ' disabled') + '>Addition</button>' : '') + '</div>');

  wire('[data-cat]', function (el) { S.category = el.dataset.cat; renderTable(); });
  wire('[data-prod]', function (el) { addToDraft(el.dataset.prod); });
  wire('[data-dplus]', function (el) { changeDraft(+el.dataset.dplus, 1); });
  wire('[data-dminus]', function (el) { changeDraft(+el.dataset.dminus, -1); });
  wire('[data-dnote]', function (el) { editDraftNote(+el.dataset.dnote); });
  wire('[data-line]', function (el) { editLine(S.order.lines[+el.dataset.line]); });
  $('#send').onclick = sendDraft;
  if ($('#kitchen')) { $('#kitchen').onclick = sendKitchen; }
  if ($('#bill')) { $('#bill').onclick = printBill; }
  wire('[data-kok]', function (el) { resolveKitchen(el.dataset.kok, 'printed'); });
  wire('[data-kre]', function (el) {
    if (confirm('Reimprimer ces lignes en cuisine ? Seulement si le bon n\'est PAS sorti.')) {
      resolveKitchen(el.dataset.kre, 'reprint');
    }
  });
}

function wire(sel, fn) {
  Array.prototype.forEach.call(document.querySelectorAll(sel), function (el) { el.onclick = function () { fn(el); }; });
}

// ------------------------------------------------------------------ brouillon (ajouts)

function addToDraft(productId) {
  var p = S.catalog.products.filter(function (x) { return x.id === productId; })[0];
  if (!p) { return; }
  var d = draftOf(S.table.id);
  var same = d.items.filter(function (it) { return it.productId === productId && !it.note; })[0];
  if (same) { same.quantity++; } else { d.items.push({productId: p.id, name: p.name, quantity: 1, note: ''}); }
  saveDraft(S.table.id, d);
  renderTable();
}

function changeDraft(i, delta) {
  var d = draftOf(S.table.id);
  d.items[i].quantity += delta;
  if (d.items[i].quantity <= 0) { d.items.splice(i, 1); }
  saveDraft(S.table.id, d);
  renderTable();
}

function editDraftNote(i) {
  var d = draftOf(S.table.id);
  noteSheet(d.items[i].name, d.items[i].note, function (note) {
    d.items[i].note = note;
    saveDraft(S.table.id, d);
    renderTable();
  });
}

function noteSheet(title, value, done) {
  sheet('<h2>Note cuisine : ' + esc(title) + '</h2><input type="text" id="note" maxlength="200" value="' + esc(value || '')
    + '" placeholder="ex. sans sel, bien cuit"><div class="row"><button class="btn" id="cancel">Annuler</button>'
    + '<button class="btn primary" id="ok">OK</button></div>');
  $('#cancel').onclick = closeSheet;
  $('#ok').onclick = function () { var v = $('#note').value.trim(); closeSheet(); done(v); };
}

function sendDraft() {
  var d = draftOf(S.table.id);
  if (!d.items.length || !S.order) { return; }
  var ops = d.items.map(function (it) {
    var op = {op: 'add', productId: it.productId, quantity: it.quantity};
    if (it.note) { op.note = it.note; }
    return op;
  });
  submit(ops, true);
}

// ------------------------------------------------------------------ lignes deja enregistrees

function editLine(l) {
  var q = l.quantity;
  sheet('<h2>' + esc(l.name) + '</h2><div class="qty" style="justify-content:center;margin-bottom:12px">'
    + '<button id="m">&minus;</button><b id="q" style="min-width:40px;text-align:center">' + q + '</b><button id="p">+</button></div>'
    + '<input type="text" id="note" maxlength="200" value="' + esc(l.note || '') + '" placeholder="Note cuisine">'
    + '<div class="row"><button class="btn danger" id="del">Supprimer</button><button class="btn" id="cancel">Annuler</button>'
    + '<button class="btn primary" id="ok">Valider</button></div>');
  $('#m').onclick = function () { if (q > 1) { q--; $('#q').textContent = q; } };
  $('#p').onclick = function () { if (q < 999) { q++; $('#q').textContent = q; } };
  $('#cancel').onclick = closeSheet;
  $('#del').onclick = function () { closeSheet(); submit([{op: 'remove', lineId: l.lineId}], false); };
  $('#ok').onclick = function () {
    var ops = [];
    var note = $('#note').value.trim();
    if (q !== l.quantity) { ops.push({op: 'setQuantity', lineId: l.lineId, quantity: q}); }
    if (note !== (l.note || '')) { ops.push({op: 'setNote', lineId: l.lineId, note: note}); }
    closeSheet();
    if (ops.length) { submit(ops, false); }
  };
}

// ------------------------------------------------------------------ envoi fiable

// Chaque demande a un requestId. Elle est gardee (localStorage) tant que la
// caisse n'a pas repondu : en cas de coupure Wi-Fi, on renvoie EXACTEMENT la
// meme demande, que la caisse reconnait (pas de doublon).
function submit(ops, fromDraft) {
  if (S.sending) { return; }
  var tableId = S.table.id;
  if (load('pending:' + tableId)) { banner('Une demande precedente attend encore une reponse.', 'warn', 'Reessayer', resumePending); return; }
  var body = {requestId: uuid(), baseVersion: S.order ? S.order.version : 0, operations: ops};
  store('pending:' + tableId, {body: body, fromDraft: fromDraft});
  deliver(tableId);
}

function resumePending() {
  if (!S.table || S.sending) { return; }
  if (load('pending:' + S.table.id)) { deliver(S.table.id); }
}

function deliver(tableId) {
  var pending = load('pending:' + tableId);
  if (!pending) { return; }
  S.sending = true;
  renderTable();
  banner('Envoi a la caisse...', '');
  var attempt = 0;
  function tryOnce() {
    attempt++;
    return api('POST', '/tables/' + encodeURIComponent(tableId) + '/order/changes', pending.body).then(function (r) {
      S.sending = false;
      store('pending:' + tableId, null);   // la caisse a repondu : la demande n'est plus en suspens
      handleResult(tableId, pending, r);
    }, function () {
      if (attempt < 3) { return sleep(1500).then(tryOnce); }
      S.sending = false;
      banner('Pas de reponse de la caisse. Votre demande est gardee et sera renvoyee telle quelle '
        + '(sans doublon).', 'error', 'Reessayer', function () { deliver(tableId); });
      renderTable();
    });
  }
  tryOnce();
}

function handleResult(tableId, pending, r) {
  var d = r.data || {};
  if (d.order && S.table && S.table.id === tableId) { S.order = d.order; }
  if (r.status === 200) {
    if (pending.fromDraft) { saveDraft(tableId, {items: []}); }
    banner(d.duplicate ? 'Deja recu par la caisse (renvoi apres coupure) : rien en double.' : 'Enregistre a la caisse.', '');
    setTimeout(function () { if (!S.sending) { banner(null); } }, 3000);
  } else if (r.status === 401) {
    return;   // deconnexion deja geree ; le brouillon est garde
  } else {
    var keep = pending.fromDraft ? ' Votre brouillon est garde : verifiez puis renvoyez.' : '';
    banner(errorText(r) + keep, r.status === 409 || r.status === 423 ? 'warn' : 'error');
    if (!d.order && S.table && S.table.id === tableId) { refreshOrder(); }
  }
  renderTable();
}

// ------------------------------------------------------------------ cuisine

var KITCHEN_LABELS = {
  envoye: 'Envoye a l\'imprimante (non confirme)',
  confirme: 'Impression confirmee',
  a_verifier: 'A verifier',
  non_imprime: 'Non imprime',
  confirme_manuellement: 'Bon sorti (verifie)',
  reimpression_demandee: 'Reimpression demandee',
  en_cours: 'Impression en cours...'
};

function kitchenTag(st) {
  if (st === 'a_envoyer') { return '<span class="tag">a envoyer en cuisine</span>'; }
  if (st === 'envoye') { return '<span class="tag">en cuisine</span>'; }
  if (st === 'a_verifier') { return '<span class="tag warn">&#9888; a verifier</span>'; }
  return '';
}

function kitchenCard(o) {
  if (!o || !o.kitchenSends || !o.kitchenSends.length) { return ''; }
  return '<div class="card"><h2>Envois cuisine</h2>' + o.kitchenSends.slice(0, 5).map(function (k) {
    var check = (k.status === 'a_verifier' || k.status === 'envoye') && can('sales.PrintKitchen');
    var when = k.at ? new Date(k.at).toLocaleTimeString('fr-FR', {hour: '2-digit', minute: '2-digit'}) : '';
    return '<div class="line"><div class="name"><b class="k-' + esc(k.status) + '">' + esc(KITCHEN_LABELS[k.status] || k.status)
      + '</b> <span class="muted">' + esc(when) + ' - ' + esc(k.by || '') + '</span>'
      + '<div class="note">' + (k.items || []).map(function (it) {
        return esc(it.quantity) + ' x ' + esc(it.name) + (it.note ? ' (' + esc(it.note) + ')' : '');
      }).join(', ') + '</div>'
      + (k.message && k.status !== 'confirme' ? '<div class="note">' + esc(k.message) + '</div>' : '')
      + (check ? '<div style="display:flex;gap:8px;margin-top:8px"><button class="btn" data-kok="' + esc(k.id)
        + '">Le bon est sorti</button><button class="btn danger" data-kre="' + esc(k.id) + '">Reimprimer</button></div>' : '')
      + '</div></div>';
  }).join('') + '</div>';
}

// Un envoi cuisine a un sendId garde dans le telephone. Sans reponse (Wi-Fi),
// AUCUN renvoi automatique : le serveur appuie sur "Verifier", qui redemande le
// MEME envoi ; la caisse le reconnait et n'imprime jamais deux fois.
function sendKitchen() {
  if (S.sending || !S.table) { return; }
  var tableId = S.table.id;
  store('kpending:' + tableId, {sendId: uuid()});
  deliverKitchen(tableId);
}

function deliverKitchen(tableId) {
  var k = load('kpending:' + tableId);
  if (!k || S.sending) { return; }
  S.sending = true;
  renderTable();
  banner('Envoi en cuisine...', '');
  api('POST', '/tables/' + encodeURIComponent(tableId) + '/kitchen/send', {sendId: k.sendId}).then(function (r) {
    S.sending = false;
    store('kpending:' + tableId, null);   // la caisse a repondu : plus d'incertitude cote telephone
    var d = r.data || {};
    if (d.order && S.table && S.table.id === tableId) { S.order = d.order; }
    if (r.status === 200) {
      var st = d.send.status;
      banner((d.duplicate ? 'Envoi deja recu par la caisse (pas de double impression) : ' : '')
        + (KITCHEN_LABELS[st] || st) + '. ' + (st !== 'confirme' && d.send.message ? d.send.message : ''),
        st === 'confirme' || st === 'envoye' ? '' : 'warn');
    } else if (r.status !== 401) {
      banner(errorText(r), r.status === 409 || r.status === 423 || r.status === 503 ? 'warn' : 'error');
    }
    renderTable();
  }, function () {
    S.sending = false;
    banner('Envoi cuisine sans reponse de la caisse : statut A VERIFIER. Rien ne sera renvoye automatiquement. '
      + '"Verifier" redemande ce meme envoi (jamais imprime deux fois).', 'error', 'Verifier',
      function () { deliverKitchen(tableId); });
    renderTable();
  });
}

function resolveKitchen(sendId, action) {
  var tableId = S.table.id;
  api('POST', '/tables/' + encodeURIComponent(tableId) + '/kitchen/' + encodeURIComponent(sendId) + '/resolve',
    {action: action}).then(function (r) {
    var d = r.data || {};
    if (d.order) { S.order = d.order; }
    if (r.status === 200) {
      banner(action === 'printed' ? 'Note : le bon est sorti.'
        : 'Lignes remises "a envoyer" : appuyez sur Cuisine pour les reimprimer.', '');
    } else if (r.status !== 401) {
      banner(errorText(r), 'warn');
    }
    renderTable();
  }, function () { banner('Pas de reponse de la caisse, reessayez.', 'error'); });
}

// ------------------------------------------------------------------ addition provisoire

// Meme principe que la cuisine : un printId garde dans le telephone, aucun renvoi
// automatique ; "Verifier" redemande la MEME addition (jamais imprimee deux fois),
// "Reimprimer" en demande explicitement une nouvelle.
function printBill() {
  if (S.sending || !S.table) { return; }
  store('bpending:' + S.table.id, {printId: uuid()});
  deliverBill(S.table.id);
}

function deliverBill(tableId) {
  var b = load('bpending:' + tableId);
  if (!b || S.sending) { return; }
  S.sending = true;
  renderTable();
  banner('Impression de l\'addition...', '');
  api('POST', '/tables/' + encodeURIComponent(tableId) + '/bill/print', {printId: b.printId}).then(function (r) {
    S.sending = false;
    store('bpending:' + tableId, null);
    var d = r.data || {};
    if (d.order && S.table && S.table.id === tableId) { S.order = d.order; }
    if (r.status === 200) {
      var st = d.print.status;
      var text = (d.duplicate ? 'Addition deja recue par la caisse, pas imprimee deux fois' : 'Addition')
        + (d.print.total ? ' (' + d.print.total + ')' : '') + ' : ' + (KITCHEN_LABELS[st] || st) + '. '
        + (st !== 'confirme' && d.print.message ? d.print.message : '');
      if (st === 'a_verifier' || st === 'non_imprime') {
        banner(text, 'warn', 'Reimprimer', function () {
          if (st === 'non_imprime' || confirm('Imprimer une nouvelle addition ? Seulement si elle n\'est PAS sortie.')) {
            printBill();
          }
        });
      } else {
        banner(text, '');
      }
    } else if (r.status !== 401) {
      banner(errorText(r), r.status === 409 || r.status === 423 || r.status === 503 ? 'warn' : 'error');
    }
    renderTable();
  }, function () {
    S.sending = false;
    banner('Addition sans reponse de la caisse : statut A VERIFIER. Rien ne sera renvoye automatiquement. '
      + '"Verifier" redemande cette meme addition (jamais imprimee deux fois).', 'error', 'Verifier',
      function () { deliverBill(tableId); });
    renderTable();
  });
}

// ------------------------------------------------------------------ rafraichissement

function startPolling() {
  stopPolling();
  S.timer = setInterval(function () {
    if (document.hidden || S.sending || !$('#sheet').hidden) { return; }
    if (S.screen === 'floors') { refreshFloors(); }
    if (S.screen === 'table') {
      if (load('pending:' + S.table.id)) { resumePending(); } else { refreshOrder(); }
    }
  }, POLL_MS);
}
function stopPolling() { if (S.timer) { clearInterval(S.timer); S.timer = null; } }

// ------------------------------------------------------------------ demarrage

$('#logout').onclick = function () { logout(null); };
S.session = load('session');
if (S.session) {
  api('GET', '/me').then(function (r) { if (r.status === 200) { showFloors(); } }, function () { showFloors(); });
} else {
  showUsers();
}
