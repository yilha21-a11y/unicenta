// OLPOS - Espace Patron (lecture seule). Licence GPL v3. Aucune ressource externe.
'use strict';

var S = { me: null, period: 'day', from: '', to: '', tab: 'resume', data: null };

function $(s) { return document.querySelector(s); }
function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}
function banner(text, err) {
  var b = $('#banner');
  if (!text) { b.hidden = true; return; }
  b.textContent = text; b.className = err ? 'err' : ''; b.hidden = false;
}
function api(method, path, body) {
  return fetch('/api/patron/' + path, {
    method: method, credentials: 'same-origin', cache: 'no-store',
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined
  }).then(function (r) {
    return r.json().catch(function () { return {}; }).then(function (d) {
      if (r.status === 401 && path !== 'login' && S.me) { S.me = null; showLogin('Session expirée ou révoquée : reconnectez-vous.'); }
      return { status: r.status, data: d };
    });
  });
}
function errText(r) { return r.data && r.data.error ? r.data.error.message : 'Erreur ' + r.status; }

// ------------------------------------------------------------------ montants et dates
var PAY = { cash: 'Espèces', magcard: 'Carte bancaire', cheque: 'Chèque', paperin: 'Bon / ticket', free: 'Offert',
  debt: 'Ardoise', cashrefund: 'Remboursement espèces', magcardrefund: 'Remboursement carte', chequerefund: 'Remboursement chèque',
  paperout: 'Remboursement bon', voucherin: 'Bon d’achat', voucherout: 'Remboursement bon d’achat', bank: 'Virement' };
function payName(t) { return PAY[t] || t; }

function money(v) {
  var info = S.me ? S.me.restaurant : {};
  try {
    return new Intl.NumberFormat(String(info.locale || 'fr-FR').replace('_', '-'), { style: 'currency', currency: info.currency || 'EUR' }).format(v || 0);
  } catch (e) { return (Math.round((v || 0) * 100) / 100).toFixed(2) + ' ' + (info.currency || ''); }
}
function when(ms) {
  if (!ms) { return '-'; }
  var tz = S.me && S.me.restaurant.timezone;
  try {
    return new Date(ms).toLocaleString('fr-FR', { timeZone: tz || undefined, day: '2-digit', month: '2-digit',
      year: 'numeric', hour: '2-digit', minute: '2-digit' });
  } catch (e) { return new Date(ms).toLocaleString('fr-FR'); }
}

// ------------------------------------------------------------------ connexion
function b64ToBytes(b64) {
  var s = atob(b64), a = new Uint8Array(s.length);
  for (var i = 0; i < s.length; i++) { a[i] = s.charCodeAt(i); }
  return a;
}
function bytesToB64(buf) {
  var a = new Uint8Array(buf), s = '';
  for (var i = 0; i < a.length; i++) { s += String.fromCharCode(a[i]); }
  return btoa(s);
}

function showLogin(msg) {
  $('#who').textContent = ''; $('#logout').hidden = true;
  var last = '';
  try { last = localStorage.getItem('olpos.restaurant') || ''; } catch (e) { last = ''; }
  $('#view').innerHTML = '<form class="card login" id="f">'
    + '<h1>Connexion du patron</h1>'
    + '<p class="muted">Utilisez l\'identifiant et le mot de passe de votre compte Administrateur OLPOS, '
    + 'et le code à 6 chiffres seulement si la double authentification est activée. Votre mot de passe est chiffré pour '
    + 'la caisse de votre restaurant : le portail ne peut pas le lire.</p>'
    + '<label>Code du restaurant<input id="rest" autocomplete="organization" required value="' + esc(last) + '"></label>'
    + '<label>Identifiant OLPOS<input id="user" autocomplete="username" required></label>'
    + '<label>Mot de passe<input id="pwd" type="password" autocomplete="current-password" required></label>'
    + '<label>Code à 6 chiffres <span class="muted">(seulement si activé à la caisse — sinon laisser vide)</span><input id="otp" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" autocomplete="one-time-code"></label>'
    + '<button class="primary" type="submit" id="go">Se connecter</button></form>';
  banner(msg || null, !!msg);
  if (!window.crypto || !window.crypto.subtle) {
    banner('Ce navigateur ne permet pas le chiffrement nécessaire (connexion HTTPS obligatoire).', true);
  }
  $('#f').onsubmit = function (e) { e.preventDefault(); login(); };
}

function login() {
  var rest = $('#rest').value.trim().toLowerCase(), user = $('#user').value.trim(),
    pwd = $('#pwd').value, otp = $('#otp').value.trim();
  $('#go').disabled = true;
  banner('Vérification par la caisse du restaurant…');
  try { localStorage.setItem('olpos.restaurant', rest); } catch (e) { /* stockage indisponible */ }
  api('GET', 'challenge?restaurant=' + encodeURIComponent(rest)).then(function (c) {
    if (c.status !== 200) { throw new Error(errText(c)); }
    return crypto.subtle.importKey('spki', b64ToBytes(c.data.encKey), { name: 'RSA-OAEP', hash: 'SHA-256' }, false,
      ['encrypt']).then(function (key) {
      var payload = JSON.stringify({ cid: c.data.challengeId, nonce: c.data.nonce, user: user, password: pwd, totp: otp });
      return crypto.subtle.encrypt({ name: 'RSA-OAEP' }, key, new TextEncoder().encode(payload));
    }).then(function (ct) {
      $('#pwd').value = '';
      return api('POST', 'login', { restaurant: rest, user: user, challengeId: c.data.challengeId, ciphertext: bytesToB64(ct) });
    });
  }).then(function (r) {
    $('#go').disabled = false;
    if (r.status !== 200) { throw new Error(errText(r)); }
    banner(null);
    start();
  }).catch(function (e) {
    if ($('#go')) { $('#go').disabled = false; }
    banner(e.message || 'Connexion impossible.', true);
  });
}

// ------------------------------------------------------------------ tableau de bord
function start() {
  api('GET', 'me').then(function (r) {
    if (r.status !== 200) { showLogin(); return; }
    S.me = r.data;
    $('#who').textContent = r.data.user + ' - ' + r.data.restaurant.name;
    $('#logout').hidden = false;
    render();
    load();
  });
}

function q() {
  var s = 'period=' + S.period;
  if (S.period === 'custom') { s += '&from=' + S.from + '&to=' + S.to; }
  return s;
}

function render() {
  var i = S.me.restaurant;
  var html = '<div class="status"><span><span class="dot' + (i.online ? ' ok' : '') + '"></span>Caisse '
    + (i.online ? 'connectée' : 'hors ligne') + '</span><span>Dernière synchronisation : ' + esc(i.lastSyncText || '-')
    + '</span><span>Devise : ' + esc(i.currency || '-') + '</span><span>Fuseau horaire : ' + esc(i.timezone || '-')
    + '</span><span>Lecture seule</span></div>';
  html += '<div class="periods">' + [['day', 'Jour'], ['week', 'Semaine'], ['month', 'Mois'], ['custom', 'Période']].map(function (p) {
    return '<button data-p="' + p[0] + '"' + (S.period === p[0] ? ' class="on"' : '') + '>' + p[1] + '</button>';
  }).join('') + (S.period === 'custom' ? '<input type="date" id="from" value="' + esc(S.from) + '"> au <input type="date" id="to" value="'
    + esc(S.to) + '"> <button id="apply">Afficher</button>' : '') + '</div>';
  html += '<div id="content"><p class="muted">Chargement…</p></div>';
  $('#view').innerHTML = html;
  Array.prototype.forEach.call(document.querySelectorAll('[data-p]'), function (b) {
    b.onclick = function () {
      S.period = b.dataset.p;
      if (S.period === 'custom' && !S.from) { var d = new Date().toISOString().slice(0, 10); S.from = d; S.to = d; }
      render(); if (S.period !== 'custom') { load(); }
    };
  });
  if ($('#apply')) { $('#apply').onclick = function () { S.from = $('#from').value; S.to = $('#to').value; load(); }; }
}

function load() {
  Promise.all([api('GET', 'summary?' + q()), api('GET', 'tickets?' + q()), api('GET', 'closures?' + q()),
    api('GET', 'open-orders')]).then(function (r) {
    for (var i = 0; i < r.length; i++) { if (r[i].status !== 200) { banner(errText(r[i]), true); return; } }
    S.data = { s: r[0].data, tickets: r[1].data.tickets, closures: r[2].data.closures, open: r[3].data };
    banner(null);
    content();
  });
}

function kpi(label, v) { return '<div class="kpi"><span>' + label + '</span><b>' + v + '</b></div>'; }
function table(head, rows, numeric) {
  return '<table><tr>' + head.map(function (h, i) { return '<th' + (numeric[i] ? ' class="n"' : '') + '>' + h + '</th>'; }).join('')
    + '</tr>' + (rows.length ? rows.map(function (r) {
      return '<tr' + (r.click ? ' class="click" data-t="' + esc(r.click) + '"' : '') + '>' + r.cells.map(function (c, i) {
        return '<td' + (numeric[i] ? ' class="n"' : '') + '>' + c + '</td>';
      }).join('') + '</tr>';
    }).join('') : '<tr><td colspan="' + head.length + '" class="muted">Aucune donnée sur la période.</td></tr>') + '</table>';
}

function content() {
  var s = S.data.s;
  var tabs = [['resume', 'Résumé'], ['produits', 'Produits'], ['categories', 'Catégories'], ['employes', 'Employés'],
    ['jours', 'Jours et heures'], ['tickets', 'Tickets'], ['clotures', 'Clôtures'], ['encours', 'Commandes en cours']];
  var html = '<div class="tabs">' + tabs.map(function (t) {
    return '<button data-tab="' + t[0] + '"' + (S.tab === t[0] ? ' class="on"' : '') + '>' + t[1] + '</button>';
  }).join('') + '</div>';
  if (S.tab === 'resume') {
    html += '<div class="kpis">' + kpi('Ventes TTC', money(s.sales.total)) + kpi('Ventes HT', money(s.sales.base))
      + kpi('Taxes', money(s.sales.tax)) + kpi('Remises (HT)', money(s.discounts))
      + kpi('Remboursements TTC', money(s.refunds.total)) + kpi('Net TTC', money(s.net.total))
      + kpi('Tickets', s.ticketCount) + kpi('Panier moyen', money(s.averageBasket)) + '</div>';
    html += '<div class="card"><h2>Paiements</h2>' + table(['Moyen', 'Nombre', 'Montant'], s.payments.map(function (p) {
      return { cells: [esc(payName(p.type)), p.count, money(p.total)] };
    }), [0, 1, 1]) + '</div>';
    html += '<div class="card"><h2>Exporter</h2><div class="exports"><a class="btn" href="/api/patron/export.csv?' + q()
      + '">CSV</a><a class="btn" href="/api/patron/export.pdf?' + q() + '">PDF</a></div>'
      + '<p class="muted">Remboursements : ' + s.refundCount + ' ticket(s), HT ' + money(s.refunds.base) + ', taxes '
      + money(s.refunds.tax) + '. Les remises sont déjà déduites du HT.</p></div>';
  } else if (S.tab === 'produits' || S.tab === 'categories') {
    var list = S.tab === 'produits' ? s.products : s.categories;
    html += '<div class="card">' + table([S.tab === 'produits' ? 'Produit' : 'Catégorie', 'Quantité', 'HT', 'TTC'],
      list.map(function (p) { return { cells: [esc(p.name), p.quantity, money(p.base), money(p.total)] }; }), [0, 1, 1, 1]) + '</div>';
  } else if (S.tab === 'employes') {
    html += '<div class="card">' + table(['Employé', 'Tickets', 'TTC'], s.employees.map(function (p) {
      return { cells: [esc(p.name), p.tickets, money(p.total)] };
    }), [0, 1, 1]) + '</div>';
  } else if (S.tab === 'jours') {
    html += '<div class="card"><h2>Par jour</h2>' + table(['Jour', 'Tickets', 'TTC'], s.days.map(function (p) {
      return { cells: [esc(p.day), p.tickets, money(p.total)] };
    }), [0, 1, 1]) + '</div><div class="card"><h2>Par heure</h2>' + table(['Heure', 'Tickets', 'TTC'], s.hours.map(function (p) {
      return { cells: [p.hour + ' h', p.tickets, money(p.total)] };
    }), [0, 1, 1]) + '</div>';
  } else if (S.tab === 'tickets') {
    html += '<div class="card"><p class="muted">Touchez un ticket pour voir ses articles.</p>' + table(['Date', 'N°', 'Table', 'Employé', 'TTC'],
      S.data.tickets.map(function (t) {
        return { click: t.id, cells: [esc(t.when || when(t.ts)), (t.type === 1 ? 'Remb. ' : '') + t.ticketNo
          + (t.orderNumber ? ' (cde ' + t.orderNumber + ')' : ''), esc(t.place || '-'), esc(t.employee || '-'), money(t.total)] };
      }), [0, 0, 0, 0, 1]) + '</div><div id="detail"></div>';
  } else if (S.tab === 'clotures') {
    html += '<div class="card">' + table(['Ouverture', 'Fermeture', 'Poste', 'Tickets', 'Encaisse'], S.data.closures.map(function (c) {
      return { cells: [esc(c.startText || when(c.start)), c.end ? esc(c.endText || when(c.end)) : 'en cours', esc(c.host) + ' #' + c.seq, c.tickets,
        money(c.paymentsTotal) + '<br><span class="muted">' + c.payments.map(function (p) { return esc(payName(p.type)) + ' ' + money(p.total); }).join(', ') + '</span>'] };
    }), [0, 0, 0, 1, 1]) + '</div>';
  } else if (S.tab === 'encours') {
    var o = S.data.open;
    html += '<div class="card"><p class="muted">Commandes ouvertes NON encaissées (état du ' + esc(o.tsText || when(o.ts))
      + '), distinctes des ventes.</p>' + table(['Table', 'Commande', 'Serveur', 'Articles', 'TTC'], (o.orders || []).map(function (c) {
      return { cells: [esc(c.place), c.orderNumber || '-', esc(c.employee || '-'), c.lines.map(function (l) {
        return l.units + ' x ' + esc(l.name);
      }).join('<br>'), money(c.total)] };
    }), [0, 0, 0, 0, 1]) + '</div>';
  }
  $('#content').innerHTML = html;
  Array.prototype.forEach.call(document.querySelectorAll('[data-tab]'), function (b) {
    b.onclick = function () { S.tab = b.dataset.tab; content(); };
  });
  Array.prototype.forEach.call(document.querySelectorAll('[data-t]'), function (r) {
    r.onclick = function () { showTicket(r.dataset.t); };
  });
}

function showTicket(id) {
  api('GET', 'tickets/' + encodeURIComponent(id)).then(function (r) {
    if (r.status !== 200) { banner(errText(r), true); return; }
    var t = r.data.ticket;
    $('#detail').innerHTML = '<div class="card"><h2>Ticket ' + t.ticketNo + ' - ' + esc(t.when || when(t.ts)) + '</h2>'
      + table(['Article', 'Qté', 'Prix HT', 'Total HT'], t.lines.map(function (l) {
        return { cells: [esc(l.name) + (l.discount ? ' (remise)' : ''), l.units, money(l.price), money(l.total)] };
      }), [0, 1, 1, 1]) + '<p>HT ' + money(t.base) + ' &middot; Taxes ' + money(t.tax) + ' &middot; <b>TTC ' + money(t.total)
      + '</b></p><p class="muted">' + t.payments.map(function (p) { return esc(payName(p.type)) + ' ' + money(p.total); }).join(', ') + '</p></div>';
    $('#detail').scrollIntoView();
  });
}

$('#logout').onclick = function () { api('POST', 'logout', {}).then(function () { S.me = null; showLogin(); }); };
api('GET', 'me').then(function (r) { if (r.status === 200) { start(); } else { showLogin(); } });
