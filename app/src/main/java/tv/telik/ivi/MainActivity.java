package tv.telik.ivi;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A bare full-screen browser for the ivi website, made for a TV remote.
 *
 * - Arrows move a mouse pointer, OK clicks. Pushing the pointer against a screen edge scrolls the page
 *   (or a row of posters) that way.
 * - Back goes back in the site's history; on the first page it exits.
 * - A USB/wireless mouse works as usual; the remote's pointer then jumps to where the mouse is.
 * - Menu shows diagnostics (versions, address, script and network errors, what the video player can do).
 * - The box's 2016 browser lacks newer JavaScript functions: a small polyfill script is put in front of
 *   every ivi page before the site's own scripts run.
 * - Video can go full screen; login cookies are kept between runs; the chosen channel opens on start.
 */
public class MainActivity extends Activity {

    /** Opened on every start (the user's chosen channel for now). */
    private static final String HOME_URL = "https://www.ivi.ru/tvchannels/watch/cgtn";
    private static final String PREFS = "s";
    private static final String KEY_LAST = "last";

    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private WebView web;
    private Pointer pointer;
    private TextView diag;
    private View fullscreenView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;

    private String defaultUa;
    private volatile byte[] polyfill;
    private volatile int patchedPages;
    private final LinkedList<String> lastErrors = new LinkedList<>();

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        hideSystemUi();

        try {
            defaultUa = WebSettings.getDefaultUserAgent(this);
        } catch (RuntimeException e) {
            defaultUa = null;
        }

        diag = new TextView(this);
        diag.setTextColor(0xFFDDDDDD);
        diag.setBackgroundColor(0xCC000000);
        diag.setTextSize(12);
        diag.setPadding(16, 8, 16, 8);
        diag.setVisibility(View.GONE);

        createWebView();

        pointer = new Pointer(this);
        root.addView(pointer, matchParent());
        root.addView(diag, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));

        if (web != null) web.loadUrl(HOME_URL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.onResume();
            web.resumeTimers();
        }
    }

    @Override
    protected void onPause() {
        if (web != null) {
            web.onPause();
            web.pauseTimers();
        }
        CookieManager.getInstance().flush();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private String lastUrl() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LAST, HOME_URL);
    }

    // ---------------------------------------------------------------- mouse

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (isMouse(ev)) pointer.followMouse(ev.getX(), ev.getY());
        return super.dispatchGenericMotionEvent(ev);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (isMouse(ev)) pointer.followMouse(ev.getX(), ev.getY());
        return super.dispatchTouchEvent(ev);
    }

    private static boolean isMouse(MotionEvent ev) {
        return ev.isFromSource(InputDevice.SOURCE_MOUSE);
    }

    // ---------------------------------------------------------------- remote control

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // The remote drives the pointer, not the page's own keyboard focus.
        int k = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) pointer.move(-1, 0, event.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) pointer.move(1, 0, event.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) pointer.move(0, -1, event.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) pointer.move(0, 1, event.getRepeatCount());
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (down && event.getRepeatCount() == 0) pointer.click();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) return true;
                if (diag.getVisibility() == View.VISIBLE) {
                    diag.setVisibility(View.GONE);
                } else if (fullscreenView != null) {
                    exitFullscreen();
                } else if (web != null && web.canGoBack()) {
                    web.goBack();
                } else {
                    finish();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                if (down && event.getRepeatCount() == 0) toggleDiag();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (down && event.getRepeatCount() == 0) {
                    evalJs("(function(){var v=document.querySelector('video');if(v){if(v.paused)v.play();else v.pause();}})()");
                }
                return true;
            default:
                // Letters/digits (e.g. a keyboard plugged into the box) go to the page as usual.
                return super.dispatchKeyEvent(event);
        }
    }

    /** The mouse pointer drawn over the page. */
    private final class Pointer extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private float x = -1, y = -1;
        private final float size;

        Pointer(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            size = 22 * d;
            fill.setColor(Color.WHITE);
            edge.setColor(Color.BLACK);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(2 * d);
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
            setVisibility(View.INVISIBLE);
        }

        /** A real mouse is in use: hide the remote's pointer (the system draws its own), remember the spot. */
        void followMouse(float mx, float my) {
            x = mx;
            y = my;
            main.removeCallbacks(autoHide);
            setVisibility(View.INVISIBLE);
        }

        private void center() {
            if (x < 0 && getWidth() > 0) {
                x = getWidth() / 2f;
                y = getHeight() / 2f;
            }
        }

        void move(int dx, int dy, int repeat) {
            center();
            show();
            // Speeds up while the button is held.
            float step = getWidth() / 60f * Math.min(1 + repeat / 3f, 6);
            float nx = x + dx * step, ny = y + dy * step;
            float maxX = getWidth() - 1, maxY = getHeight() - 1;
            // Pushing against an edge scrolls the content under the pointer.
            if (nx < 0 || nx > maxX || ny < 0 || ny > maxY) scrollAt(dx, dy);
            x = Math.max(0, Math.min(maxX, nx));
            y = Math.max(0, Math.min(maxY, ny));
            invalidate();
        }

        void click() {
            center();
            show();
            long t = SystemClock.uptimeMillis();
            sendTouch(MotionEvent.ACTION_DOWN, t, t, x, y);
            sendTouch(MotionEvent.ACTION_UP, t, t + 60, x, y);
        }

        /** A quick finger swipe at the pointer: scrolls the page or a poster row, whichever is under it. */
        private void scrollAt(int dx, int dy) {
            float dist = (dx != 0 ? getWidth() : getHeight()) * 0.35f;
            // Start the swipe a bit inside the screen so it isn't lost at the very edge.
            float sx = dx == 0 ? x : Math.max(dist, Math.min(getWidth() - dist, x));
            float sy = dy == 0 ? y : Math.max(dist, Math.min(getHeight() - dist, y));
            float ex = sx - dx * dist, ey = sy - dy * dist;
            long t = SystemClock.uptimeMillis();
            sendTouch(MotionEvent.ACTION_DOWN, t, t, sx, sy);
            for (int i = 1; i <= 6; i++) {
                float f = i / 6f;
                sendTouch(MotionEvent.ACTION_MOVE, t, t + i * 16, sx + (ex - sx) * f, sy + (ey - sy) * f);
            }
            sendTouch(MotionEvent.ACTION_UP, t, t + 7 * 16, ex, ey);
        }

        private void sendTouch(int action, long downTime, long time, float px, float py) {
            View target = fullscreenView != null ? fullscreenView : web;
            if (target == null) return;
            MotionEvent e = MotionEvent.obtain(downTime, time, action, px, py, 0);
            e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            target.dispatchTouchEvent(e);
            e.recycle();
        }

        private final Runnable autoHide = () -> setVisibility(View.INVISIBLE);

        private void show() {
            setVisibility(View.VISIBLE);
            main.removeCallbacks(autoHide);
            main.postDelayed(autoHide, 6_000);
        }

        @Override
        protected void onDraw(Canvas c) {
            center();
            if (x < 0) return;
            arrow.reset();
            arrow.moveTo(x, y);
            arrow.lineTo(x, y + size);
            arrow.lineTo(x + size * 0.28f, y + size * 0.75f);
            arrow.lineTo(x + size * 0.5f, y + size * 1.1f);
            arrow.lineTo(x + size * 0.62f, y + size * 1.03f);
            arrow.lineTo(x + size * 0.42f, y + size * 0.68f);
            arrow.lineTo(x + size * 0.75f, y + size * 0.68f);
            arrow.close();
            c.drawPath(arrow, fill);
            c.drawPath(arrow, edge);
        }
    }

    // ---------------------------------------------------------------- web view

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        try {
            web = new WebView(this);
        } catch (RuntimeException e) {
            noteError("WebView не создаётся: " + e);
            return;
        }
        web.setBackgroundColor(Color.BLACK);
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        web.setFocusable(false); // keys go to the pointer, touches to the page

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebViewClient(webClient);
        web.setWebChromeClient(chromeClient);
        root.addView(web, 0, matchParent());
    }

    private final WebViewClient webClient = new WebViewClient() {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            // Runs on a WebView background thread.
            if (isIviPage(request)) {
                try {
                    return fetchHtmlWithPolyfill(request.getUrl().toString(), request.getRequestHeaders());
                } catch (Exception e) {
                    return null; // let the WebView load it normally
                }
            }
            return null;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            evalJs(JS_WIDEVINE_CHECK);
            if (url != null && url.startsWith("http")) {
                getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST, url).apply();
            }
            if (diag.getVisibility() == View.VISIBLE) showDiag();
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            // Old boxes often have outdated root certificates or a wrong clock.
            noteError("SSL " + error.getPrimaryError() + " " + error.getUrl());
            handler.proceed();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            noteError((request.isForMainFrame() ? "страница: " : "сеть: ") + error.getErrorCode() + " "
                    + error.getDescription() + " " + shortUrl(request.getUrl().toString()));
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            // Refused requests (e.g. the player's API or video server) say a lot about why video doesn't start.
            noteError("HTTP " + response.getStatusCode() + " " + shortUrl(request.getUrl().toString()));
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // The browser engine crashed or was killed for memory: rebuild it instead of crashing the app.
            noteError("WebView упал, перезапуск");
            if (web != null) {
                root.removeView(web);
                web.destroy();
                web = null;
            }
            main.postDelayed(() -> {
                createWebView();
                if (web != null) web.loadUrl(lastUrl());
            }, 1_000);
            return true;
        }
    };

    private final WebChromeClient chromeClient = new WebChromeClient() {
        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            // The site's player asked for full screen.
            if (fullscreenView != null) exitFullscreen();
            fullscreenView = view;
            fullscreenCallback = callback;
            root.addView(view, 1, matchParent()); // above the page, below pointer and diagnostics
        }

        @Override
        public void onHideCustomView() {
            exitFullscreen();
        }

        @Override
        public View getVideoLoadingProgressView() {
            return new View(MainActivity.this);
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            // Protected (DRM) video asks for this; nothing else is granted.
            List<String> ok = new ArrayList<>();
            for (String r : request.getResources()) {
                if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) ok.add(r);
            }
            if (ok.isEmpty()) request.deny();
            else request.grant(ok.toArray(new String[0]));
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage m) {
            if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                noteError("JS: " + m.message() + " @" + m.lineNumber());
            }
            return true;
        }
    };

    private void exitFullscreen() {
        if (fullscreenView == null) return;
        root.removeView(fullscreenView);
        fullscreenView = null;
        if (fullscreenCallback != null) fullscreenCallback.onCustomViewHidden();
        fullscreenCallback = null;
        hideSystemUi();
    }

    private void evalJs(String js) {
        if (web != null) web.evaluateJavascript(js, null);
    }

    // ---------------------------------------------------------------- polyfill for the old browser

    private static final Pattern HEAD_TAG = Pattern.compile("<head\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("<html\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    /** An HTML page (not a script, picture or API call) on an ivi site. */
    private static boolean isIviPage(WebResourceRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return false;
        String host = request.getUrl().getHost();
        if (host == null) return false;
        host = host.toLowerCase();
        boolean ivi = host.equals("ivi.ru") || host.endsWith(".ivi.ru") || host.equals("ivi.tv") || host.endsWith(".ivi.tv");
        if (!ivi) return false;
        Map<String, String> h = request.getRequestHeaders();
        if (h == null) return false;
        for (Map.Entry<String, String> e : h.entrySet()) {
            if ("accept".equalsIgnoreCase(e.getKey())) {
                return e.getValue() != null && e.getValue().contains("text/html");
            }
        }
        return false;
    }

    private byte[] polyfillTag() throws Exception {
        if (polyfill == null) {
            InputStream in = getAssets().open("polyfill.js");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write("<script>".getBytes("ISO-8859-1"));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.write("</script>".getBytes("ISO-8859-1"));
            polyfill = out.toByteArray();
        }
        return polyfill;
    }

    /** Downloads an HTML page itself and puts the polyfill script right after <head>. */
    private WebResourceResponse fetchHtmlWithPolyfill(String url, Map<String, String> reqHeaders) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(15_000);
        if (reqHeaders != null) {
            for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                String k = e.getKey();
                if (k == null || e.getValue() == null) continue;
                String lk = k.toLowerCase();
                if (lk.equals("accept-encoding") || lk.equals("range") || lk.equals("host")) continue;
                c.setRequestProperty(k, e.getValue());
            }
        }
        CookieManager cm = CookieManager.getInstance();
        String cookie = cm.getCookie(url);
        if (cookie != null) c.setRequestProperty("Cookie", cookie);

        int code = c.getResponseCode();
        String type = c.getContentType();
        if (code != 200 || type == null || !type.toLowerCase().contains("text/html")) {
            // Redirects, errors and non-HTML are left to the WebView itself.
            c.disconnect();
            return null;
        }
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase("set-cookie")) {
                for (String v : e.getValue()) cm.setCookie(url, v);
            }
        }
        String charset = null;
        Matcher cs = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE).matcher(type);
        if (cs.find()) charset = cs.group(1);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        InputStream in = c.getInputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) body.write(buf, 0, n);
        c.disconnect();
        byte[] bytes = body.toByteArray();

        // Work on bytes (Latin-1 maps 1:1) so the page's own encoding is never touched.
        String latin = new String(bytes, "ISO-8859-1");
        int at = 0;
        Matcher m = HEAD_TAG.matcher(latin);
        if (m.find()) {
            at = m.end();
        } else {
            m = HTML_TAG.matcher(latin);
            if (m.find()) at = m.end();
        }
        byte[] tag = polyfillTag();
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + tag.length);
        out.write(bytes, 0, at);
        out.write(tag);
        out.write(bytes, at, bytes.length - at);
        patchedPages++;

        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-cache");
        return new WebResourceResponse("text/html", charset, 200, "OK", headers,
                new ByteArrayInputStream(out.toByteArray()));
    }

    // ---------------------------------------------------------------- diagnostics

    private void noteError(final String e) {
        main.post(() -> {
            String s = e.length() > 200 ? e.substring(0, 200) : e;
            s = String.format(java.util.Locale.US, "%tR ", new java.util.Date()) + s;
            lastErrors.addFirst(s);
            if (lastErrors.size() > 1 && lastErrors.get(1).substring(6).equals(s.substring(6))) {
                lastErrors.remove(1); // same error again: keep only the newest
            }
            while (lastErrors.size() > 7) lastErrors.removeLast();
            if (diag.getVisibility() == View.VISIBLE) showDiag();
        });
    }

    private void toggleDiag() {
        if (diag.getVisibility() == View.VISIBLE) {
            diag.setVisibility(View.GONE);
            main.removeCallbacks(diagRefresh);
        } else {
            showDiag();
        }
    }

    /** While diagnostics are open, ask the page every 2 s what its video player is doing. */
    private final Runnable diagRefresh = new Runnable() {
        @Override
        public void run() {
            if (diag.getVisibility() != View.VISIBLE) return;
            if (web != null) {
                web.evaluateJavascript(JS_PROBE, v -> {
                    if (v != null && v.length() > 2 && v.startsWith("\"")) {
                        probe = v.substring(1, v.length() - 1).replace("\\\"", "\"");
                    }
                    if (diag.getVisibility() == View.VISIBLE) showDiag();
                });
            }
            main.postDelayed(this, 2_000);
        }
    };

    private String probe = "-";

    /** Asks the protected-video system once per page whether Widevine works; the answer is read by JS_PROBE. */
    private static final String JS_WIDEVINE_CHECK = "(function(){try{if(window.__tkwv)return;window.__tkwv='проверка';"
            + "if(!navigator.requestMediaKeySystemAccess){window.__tkwv='нет EME';return;}"
            + "navigator.requestMediaKeySystemAccess('com.widevine.alpha',[{initDataTypes:['cenc'],"
            + "videoCapabilities:[{contentType:'video/mp4; codecs=\"avc1.42E01E\"'}]}])"
            + ".then(function(){window.__tkwv='есть';},function(e){window.__tkwv='нет ('+e.name+')';});"
            + "}catch(e){window.__tkwv='ошибка '+e;}})()";

    /** What the page's video elements and the browser's media features look like right now. */
    private static final String JS_PROBE = "(function(){var r=[];try{"
            + "r.push('MSE '+(window.MediaSource?(MediaSource.isTypeSupported('video/mp4; codecs=\"avc1.640028\"')?'да':'без H.264'):'нет'));"
            + "r.push('Widevine '+(window.__tkwv||'?'));"
            + "var v=document.querySelectorAll('video');r.push('video: '+v.length);"
            + "for(var i=0;i<v.length&&i<3;i++){var x=v[i];r.push('['+(x.paused?'пауза':'идёт')+' готов'+x.readyState"
            + "+' сеть'+x.networkState+(x.error?' ОШИБКА '+x.error.code+' '+(x.error.message||''):'')"
            + "+' '+String(x.currentSrc||'').slice(0,50)+']');}"
            + "r.push('iframe: '+document.querySelectorAll('iframe').length);"
            + "}catch(e){r.push('проба: '+e);}return r.join(' · ');})()";

    /** Which browser engines are installed (a newer one can be picked as the system WebView). */
    private String webViewPackages() {
        String[] pk = {"com.google.android.webview", "com.android.webview", "com.android.chrome",
                "com.chrome.beta", "com.google.android.webview.beta"};
        StringBuilder sb = new StringBuilder();
        for (String p : pk) {
            try {
                android.content.pm.PackageInfo i = getPackageManager().getPackageInfo(p, 0);
                boolean on = i.applicationInfo == null || i.applicationInfo.enabled;
                if (sb.length() > 0) sb.append(", ");
                sb.append(p).append(' ').append(i.versionName).append(on ? "" : " (выключен)");
            } catch (Exception ignored) {
            }
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    private static String shortUrl(String u) {
        return u.length() > 90 ? u.substring(0, 90) + "…" : u;
    }

    private void showDiag() {
        String chrome = "?";
        if (defaultUa != null) {
            Matcher m = Pattern.compile("Chrome/([\\d.]+)").matcher(defaultUa);
            if (m.find()) chrome = m.group(1);
        }
        String url = web == null || web.getUrl() == null ? "-" : web.getUrl();
        if (url.length() > 120) url = url.substring(0, 120) + "…";
        diag.setText("TelikIVI " + BuildConfig.VERSION_NAME
                + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + " · WebView " + chrome
                + "\nДвижки браузера на приставке: " + webViewPackages()
                + "\nАдрес: " + url
                + " · исправлено страниц: " + patchedPages
                + "\nПлеер: " + probe
                + "\nОшибки: " + (lastErrors.isEmpty() ? "-" : android.text.TextUtils.join("\n   ", lastErrors))
                + "\n(Menu или Назад закрывает)");
        if (diag.getVisibility() != View.VISIBLE) {
            diag.setVisibility(View.VISIBLE);
            main.removeCallbacks(diagRefresh);
            main.post(diagRefresh);
        }
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
