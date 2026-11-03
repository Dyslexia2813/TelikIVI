// Network side: refused or failed requests, and anything that looks like video (manifests, DRM licences).
function send(m) { try { browser.runtime.sendNativeMessage("browser", m); } catch (e) {} }
var all = { urls: ["<all_urls>"] };
var seg = {};
browser.webRequest.onCompleted.addListener(function (d) {
  if (d.statusCode >= 400) send({ t: "http", s: d.statusCode, u: d.url });
}, all);
browser.webRequest.onErrorOccurred.addListener(function (d) {
  if (!/ABORTED/.test(d.error)) send({ t: "net", e: d.error, u: d.url });
}, all);
browser.webRequest.onBeforeRequest.addListener(function (d) {
  var u = d.url.toLowerCase();
  if (/\.m3u8|\.mpd|license|widevine|drm|proxy/.test(u)) send({ t: "media", u: d.url });
  // DRM licences usually go by POST; count stream segments per host.
  else if (d.method === "POST" && /ivi|cdn/.test(u)) send({ t: "media", u: "POST " + d.url });
  if (/livecdn|\.ts|\.m4s|\.mp4/.test(u)) {
    var h = u.split("/")[2];
    seg[h] = (seg[h] || 0) + 1;
    if (seg[h] === 1 || seg[h] === 5 || seg[h] % 50 === 0) send({ t: "seg", h: h, n: seg[h] });
  }
}, all);

// Stream hand-over to the app's own player: the video manifest and the DRM licence request, with the
// headers the site's player used, so the app can play the same stream natively.
var hdrs = {};
browser.webRequest.onBeforeSendHeaders.addListener(function (d) {
  var h = {};
  (d.requestHeaders || []).forEach(function (x) { h[x.name] = x.value; });
  hdrs[d.requestId] = h;
  if (/\/\/w\.ivi\.ru\/proxy\//.test(d.url) && d.method === "POST") {
    send({ t: "stream", k: "license", u: d.url, h: h });
  }
}, all, ["requestHeaders"]);
browser.webRequest.onHeadersReceived.addListener(function (d) {
  var type = "";
  (d.responseHeaders || []).forEach(function (x) { if (x.name.toLowerCase() === "content-type") type = x.value || ""; });
  var u = d.url.toLowerCase();
  var dash = /dash\+xml/.test(type) || /\.mpd(\?|$)/.test(u);
  var hls = /mpegurl/i.test(type) || /\.m3u8(\?|$)/.test(u);
  if ((dash || hls) && d.statusCode < 400) {
    send({ t: "stream", k: "manifest", f: dash ? "dash" : "hls", u: d.url, ct: type, h: hdrs[d.requestId] || {} });
  }
  delete hdrs[d.requestId];
}, all, ["responseHeaders"]);
