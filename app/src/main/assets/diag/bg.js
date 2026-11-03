// Network side: refused or failed requests, and anything that looks like video (manifests, DRM licences).
function send(m) { try { browser.runtime.sendNativeMessage("browser", m); } catch (e) {} }
var all = { urls: ["<all_urls>"] };
browser.webRequest.onCompleted.addListener(function (d) {
  if (d.statusCode >= 400) send({ t: "http", s: d.statusCode, u: d.url });
}, all);
browser.webRequest.onErrorOccurred.addListener(function (d) {
  if (!/ABORTED/.test(d.error)) send({ t: "net", e: d.error, u: d.url });
}, all);
browser.webRequest.onBeforeRequest.addListener(function (d) {
  var u = d.url.toLowerCase();
  if (/\.m3u8|\.mpd|license|widevine|drm/.test(u)) send({ t: "media", u: d.url });
}, all);
