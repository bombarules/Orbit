/* Bomba Wall 1.0 — the piece every Bomba Live app shares.
 *
 * Keep this file IDENTICAL in OverLook, bomba-live, Orbit and BombaArgus.
 * If you change it in one repo, copy it to the other three.
 *
 * It does two things:
 *  1. ICE: STUN + TURN relay settings, so screens connect even when the
 *     devices are on different networks (phone on cell data, computer on
 *     Wi-Fi, work vs home).
 *  2. Roster: keeps the list of device names the same on every screen
 *     that uses the same wall code. Add or remove a device on one screen
 *     and every other screen picks it up within a few seconds. No server
 *     of our own: the first screen online holds the list at the PeerJS id
 *     bomba-<code>--roster and the others sync with it; if it goes away,
 *     another screen takes over.
 *
 * Page API (window.BombaWall):
 *   BombaWall.ICE                 RTCConfiguration for new Peer(...,{config})
 *   BombaWall.start(code, onList) begin syncing; onList(names) on remote change
 *   BombaWall.setList(names)      call whenever this page edits its list
 *   BombaWall.list()              current names
 *   BombaWall.stop()
 *
 * Optional own TURN server: in any page's console run
 *   localStorage.setItem("bomba-live-turn", JSON.stringify(
 *     {urls:"turn:your.server:3478", username:"u", credential:"p"}))
 */
(function () {
  "use strict";
  var VERSION = "1.0";
  var LS = {
    get: function (k, d) { try { var v = localStorage.getItem(k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set: function (k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} }
  };

  /* ---------- 1. connection settings ---------- */
  var ice = [
    { urls: ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"] },
    { urls: ["turn:eu-0.turn.peerjs.com:3478", "turn:us-0.turn.peerjs.com:3478"], username: "peerjs", credential: "peerjsp" }
  ];
  var own = LS.get("bomba-live-turn", null);
  if (own && own.urls) ice.unshift(own);
  var ICE = { iceServers: ice };

  /* ---------- 2. shared device roster ---------- */
  var DEFAULTS = ["Home computer", "Work computer", "Phone", "Tablet", "Mac"];
  var MAX = 60;
  var slug = function (s) { return String(s).toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, ""); };
  var clean = function (s) { return String(s || "").replace(/[\u0000-\u001f<>]/g, "").trim().slice(0, 30); };

  /* meta: { slug: {n: name, t: stamp, del: bool} } — newest stamp wins */
  var meta = LS.get("bomba-live-devmeta", null);
  var maxT = 0;
  function bump() { maxT = Math.max(Date.now(), maxT + 1); return maxT; }
  function noteT(t) { if (t > maxT) maxT = t; }
  if (!meta || typeof meta !== "object") {
    meta = {};
    var seed = LS.get("bomba-live-devices", DEFAULTS);
    (Array.isArray(seed) ? seed : DEFAULTS).forEach(function (n, i) { n = clean(n); if (n) meta[slug(n)] = { n: n, t: i + 1, del: false }; });
  }
  Object.keys(meta).forEach(function (k) { noteT(meta[k].t || 0); });

  function list() { return Object.keys(meta).filter(function (k) { return !meta[k].del; }).map(function (k) { return meta[k].n; }); }
  function save() { LS.set("bomba-live-devmeta", meta); LS.set("bomba-live-devices", list()); }

  /* Compare a page's list with ours: new names are adds, missing names are removals. */
  function setList(names, quiet) {
    if (!Array.isArray(names)) return;
    var want = {}, changed = false;
    names.forEach(function (n) { n = clean(n); if (n) want[slug(n)] = n; });
    Object.keys(want).forEach(function (k) {
      if (!meta[k] || meta[k].del || meta[k].n !== want[k]) { meta[k] = { n: want[k], t: bump(), del: false }; changed = true; }
    });
    Object.keys(meta).forEach(function (k) {
      if (!meta[k].del && !want[k]) { meta[k] = { n: meta[k].n, t: bump(), del: true }; changed = true; }
    });
    if (changed) { save(); if (!quiet) push(); }
  }

  function merge(d) {
    if (!d || d.t !== "bomba-roster" || !d.meta || typeof d.meta !== "object") return false;
    var changed = false, keys = Object.keys(d.meta).slice(0, MAX * 2);
    keys.forEach(function (k) {
      var e = d.meta[k]; if (!e || typeof e.t !== "number") return;
      var n = clean(e.n); if (!n || slug(n) !== k) return;
      noteT(e.t);
      if (!meta[k] || e.t > meta[k].t) { meta[k] = { n: n, t: e.t, del: !!e.del }; changed = true; }
    });
    if (Object.keys(meta).length > MAX * 2) { /* forget the oldest removed names */
      Object.keys(meta).filter(function (k) { return meta[k].del; })
        .sort(function (a, b) { return meta[a].t - meta[b].t; })
        .slice(0, Object.keys(meta).length - MAX * 2).forEach(function (k) { delete meta[k]; });
    }
    if (changed) { save(); if (onList) { try { onList(list()); } catch (e) {} } }
    return changed;
  }
  function pack() { return { t: "bomba-roster", v: 1, meta: meta }; }

  /* ---------- networking ---------- */
  var code = null, onList = null, host = null, cPeer = null, conn = null, clients = [], timer = null, claiming = false;
  var rid = function (c) { return "bomba-" + String(c).toLowerCase().replace(/[^a-z0-9]/g, "") + "--roster"; };

  function push() {
    var p = pack();
    if (host) clients.forEach(function (c) { try { c.open && c.send(p); } catch (e) {} });
    else if (conn && conn.open) { try { conn.send(p); } catch (e) {} }
  }

  function tryHost() {
    if (host || claiming || !code || !window.Peer) return;
    claiming = true;
    var p = new Peer(rid(code), { config: ICE });
    p.on("open", function () {
      claiming = false; host = p;
      if (conn) { try { conn.close(); } catch (e) {} conn = null; }
    });
    p.on("connection", function (c) {
      c.on("open", function () { clients.push(c); try { c.send(pack()); } catch (e) {} });
      c.on("data", function (d) {
        var ch = merge(d);
        try { c.send(pack()); } catch (e) {}
        if (ch) clients.forEach(function (o) { if (o !== c) { try { o.open && o.send(pack()); } catch (e) {} } });
      });
      var drop = function () { clients = clients.filter(function (o) { return o !== c; }); };
      c.on("close", drop); c.on("error", drop);
    });
    p.on("error", function (e) {
      if (host === p) return;               /* minor errors after we're up */
      claiming = false;
      try { p.destroy(); } catch (x) {}
      if (e && e.type === "unavailable-id") join();
    });
    p.on("disconnected", function () { try { if (!p.destroyed) p.reconnect(); } catch (e) {} });
    p.on("close", function () { if (host === p) { host = null; clients = []; } });
  }

  function join() {
    if (!code || !window.Peer || (conn && (conn.open || conn._bombaPending))) return;
    if (!cPeer || cPeer.destroyed) {
      cPeer = new Peer(undefined, { config: ICE });
      cPeer.on("error", function () {});
      cPeer.on("disconnected", function () { try { if (!cPeer.destroyed) cPeer.reconnect(); } catch (e) {} });
    }
    var go = function () {
      var c = cPeer.connect(rid(code), { reliable: true }); if (!c) return;
      c._bombaPending = true; conn = c;
      c.on("open", function () { c._bombaPending = false; try { c.send(pack()); } catch (e) {} });
      c.on("data", merge);
      var lost = function () { if (conn === c) conn = null; };
      c.on("close", lost); c.on("error", lost);
      setTimeout(function () { if (conn === c && !c.open) { try { c.close(); } catch (e) {} conn = null; } }, 10000);
    };
    if (cPeer.open) go(); else cPeer.once("open", go);
  }

  function tick() { if (!host && !(conn && conn.open)) tryHost(); }

  function start(c, cb) {
    stop();
    onList = cb || null;
    if (!c || !window.Peer) return;
    code = c;
    setList(LS.get("bomba-live-devices", list()), true); /* pick up edits made by older code */
    tryHost();
    timer = setInterval(tick, 12000);
  }
  function stop() {
    clearInterval(timer); timer = null;
    [host, cPeer].forEach(function (p) { if (p) { try { p.destroy(); } catch (e) {} } });
    host = cPeer = conn = null; clients = []; claiming = false; code = null;
  }

  window.BombaWall = {
    VERSION: VERSION, ICE: ICE,
    start: start, stop: stop, setList: setList, list: list,
    peerId: function (c, name) { return "bomba-" + String(c).toLowerCase().replace(/[^a-z0-9]/g, "") + "-" + slug(name); }
  };
})();
