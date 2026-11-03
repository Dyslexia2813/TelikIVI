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
