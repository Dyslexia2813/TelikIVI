// Page side: script errors, console errors and what the <video> elements are doing.
(function () {
  function send(m) {
    m.f = location.host;
    try { browser.runtime.sendNativeMessage("browser", m); } catch (e) {}
  }
  // Runs inside the page itself: watches the protected-video (EME) and stream buffer (MSE) calls of the site's player.
  function pageHooks() {
    var seen = {};
    function rep(m) {
      m = String(m).slice(0, 300);
      if (seen[m]) return;
      seen[m] = 1;
      try { window.postMessage({ __tk: 1, m: m }, "*"); } catch (e) {}
    }
    function err(e) { return e ? (e.name || "") + " " + (e.message || "") : "?"; }
    document.documentElement.setAttribute("data-tk", "1");
    var N = navigator;
    function hookSession(s) {
      s.addEventListener("message", function (e) { rep("EME: сайт просит лицензию (" + e.messageType + ")"); });
      s.addEventListener("keystatuseschange", function () {
        var r = [];
        try { s.keyStatuses.forEach(function (v) { r.push(v); }); } catch (e) {}
        rep("EME ключи: " + r.join(","));
      });
      var g = s.generateRequest.bind(s);
      s.generateRequest = function (t, d) {
        rep("EME generateRequest " + t);
        return g(t, d).catch(function (e) { rep("EME generateRequest ОШИБКА " + err(e)); throw e; });
      };
      var u = s.update.bind(s);
      s.update = function (r) {
        rep("EME лицензия получена, " + (r && r.byteLength) + " байт");
        return u(r).then(function (x) { rep("EME лицензия принята"); return x; },
          function (e) { rep("EME лицензия ОШИБКА " + err(e)); throw e; });
      };
    }
    if (N.requestMediaKeySystemAccess) {
      var o = N.requestMediaKeySystemAccess.bind(N);
      N.requestMediaKeySystemAccess = function (ks, cfg) {
        var c = "";
        try { c = JSON.stringify(cfg).slice(0, 120); } catch (e) {}
        rep("EME запрос " + ks + " " + c);
        return o(ks, cfg).then(function (a) {
          rep("EME " + ks + ": доступ есть");
          var oc = a.createMediaKeys.bind(a);
          a.createMediaKeys = function () {
            return oc().then(function (k) {
              rep("EME ключи созданы");
              var os = k.createSession.bind(k);
              k.createSession = function (t) { var s = os(t); hookSession(s); return s; };
              if (k.setServerCertificate) {
                var sc = k.setServerCertificate.bind(k);
                k.setServerCertificate = function (x) {
                  return sc(x).then(function (r) { rep("EME сертификат: " + r); return r; },
                    function (e) { rep("EME сертификат ОШИБКА " + err(e)); throw e; });
                };
              }
              return k;
            }, function (e) { rep("EME ключи ОШИБКА " + err(e)); throw e; });
          };
          return a;
        }, function (e) { rep("EME " + ks + ": ОТКАЗ " + err(e)); throw e; });
      };
    }
    var MS = window.MediaSource;
    if (MS) {
      var it = MS.isTypeSupported;
      MS.isTypeSupported = function (t) {
        var r = it.call(MS, t);
        rep("MSE " + t + " → " + (r ? "да" : "нет"));
        return r;
      };
      var asb = MS.prototype.addSourceBuffer;
      MS.prototype.addSourceBuffer = function (t) {
        rep("MSE буфер " + t);
        try {
          var b = asb.call(this, t), n = 0;
          b.addEventListener("error", function () { rep("MSE буфер ОШИБКА " + t); });
          b.addEventListener("updateend", function () { n++; if (n === 1 || n === 10) rep("MSE " + t + ": кусков " + n); });
          return b;
        } catch (e) { rep("MSE буфер ОТКАЗ " + t + " " + err(e)); throw e; }
      };
    }
    document.addEventListener("encrypted", function (e) { rep("видео зашифровано: " + e.initDataType); }, true);
  }
  window.addEventListener("message", function (e) {
    var d = e.data;
    try { if (d && d.__tk) send({ t: "player", m: String(d.m) }); } catch (x) {}
  });
  // Inline first (runs before the site's scripts); if the page's security policy blocks it, load it as a file.
  try {
    var code = "(" + pageHooks.toString() + ")();";
    var s = document.createElement("script");
    s.textContent = code;
    (document.head || document.documentElement).appendChild(s);
    s.remove();
    if (document.documentElement.getAttribute("data-tk") !== "1") {
      var s2 = document.createElement("script");
      s2.src = browser.runtime.getURL("page.js");
      (document.head || document.documentElement).appendChild(s2);
      send({ t: "js", m: "слежка за плеером: через файл" });
    }
  } catch (e) { send({ t: "js", m: "слежка за плеером не встала: " + e }); }
  window.addEventListener("error", function (e) {
    var t = e.target;
    if (t && t !== window && t.tagName) {
      var src = t.currentSrc || t.src || t.href || "";
      send({ t: "js", m: "не загрузился " + t.tagName.toLowerCase() + " " + src + (t.error ? " (код " + t.error.code + ")" : "") });
    } else {
      send({ t: "js", m: (e.message || "ошибка") + " @" + (e.filename || "").split("/").pop() + ":" + e.lineno });
    }
  }, true);
  window.addEventListener("unhandledrejection", function (e) {
    var r = e.reason, s;
    try { s = r && (r.message || r.name) ? (r.name || "") + " " + (r.message || "") : String(r); } catch (x) { s = "?"; }
    send({ t: "js", m: "promise: " + s });
  });
  try {
    var pc = window.wrappedJSObject.console;
    var orig = pc.error;
    exportFunction(function () {
      var parts = [];
      for (var i = 0; i < arguments.length && i < 4; i++) {
        try { var a = arguments[i]; parts.push(a && a.message ? a.message : String(a)); } catch (x) { parts.push("?"); }
      }
      send({ t: "console", m: parts.join(" ").slice(0, 300) });
      try { return orig.apply(pc, arguments); } catch (x) {}
    }, pc, { defineAs: "error" });
  } catch (e) {}
  var last = "";
  setInterval(function () {
    var v = document.querySelectorAll("video");
    if (!v.length) return;
    var r = [];
    for (var i = 0; i < v.length && i < 3; i++) {
      var x = v[i];
      r.push((x.paused ? "пауза" : "идёт") + " готов" + x.readyState + " сеть" + x.networkState
        + " " + Math.round(x.currentTime) + "с" + (x.error ? " ОШИБКА " + x.error.code + " " + (x.error.message || "") : "")
        + (x.mediaKeys ? " DRM" : "") + " " + String(x.currentSrc || "").slice(0, 40));
    }
    var s = r.join(" | ");
    if (s !== last) { last = s; send({ t: "video", m: s }); }
  }, 2000);
})();
