// Page side: script errors, console errors and what the <video> elements are doing.
(function () {
  function send(m) {
    m.f = location.host;
    try { browser.runtime.sendNativeMessage("browser", m); } catch (e) {}
  }
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
