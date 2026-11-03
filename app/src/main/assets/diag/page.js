// Fallback copy of pageHooks from cs.js, loaded when inline scripts are blocked.
(function () {
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
  pageHooks();
})();
