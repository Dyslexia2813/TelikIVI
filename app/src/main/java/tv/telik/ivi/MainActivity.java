package tv.telik.ivi;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
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
import android.widget.FrameLayout;
import android.widget.TextView;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedList;

/**
 * A bare full-screen browser for the ivi website, made for a TV remote.
 *
 * The page runs in Firefox's engine (GeckoView) built into the app: the box's own browser (Chrome 52, 2016)
 * cannot run ivi's scripts and can't be updated on this firmware.
 *
 * - Arrows move a mouse pointer, OK clicks. Pushing the pointer against a screen edge scrolls the page
 *   (or a row of posters) that way.
 * - A USB/wireless mouse works as usual; the remote's pointer then jumps to where the mouse is.
 * - Back leaves full-screen video, then goes back in the site's history; on the first page it exits.
 * - Menu shows diagnostics (fed by a small built-in add-on, assets/diag); with it open, OK switches the site
 *   between its phone and computer versions.
 * - Login cookies are kept between runs; the chosen channel opens on start.
 */
public class MainActivity extends Activity {

    /** Opened on every start: the "Живая природа" channel (needs an ivi subscription, so log in once). */
    private static final String HOME_URL = "https://www.ivi.ru/tvchannels/watch/zhivaya_priroda";

    /** One engine per app process. */
    private static GeckoRuntime runtime;
    private static WebExtension diagExt;
    private static final String PREFS = "s";
    private static final String KEY_DESKTOP = "desktop";

    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private GeckoView view;
    private GeckoSession session;
    private Pointer pointer;
    private TextView diag;

    private boolean canGoBack;
    private boolean fullScreen;
    private String currentUrl = "-";
    private String loadState = "-";
    private final LinkedList<String> events = new LinkedList<>();
    private final LinkedList<String> media = new LinkedList<>();
    private String videoState = "-";

    // The stream the site's player opened, handed to Android's own player (see NativePlayer).
    private NativePlayer nativePlayer;
    private String manifestUrl, licenseUrl;
    private boolean manifestDash;
    private java.util.Map<String, String> manifestHeaders, licenseHeaders;
    private boolean nativeDeclined; // the user pressed Back on the native player: stay on the page
    private final Runnable startNativeAnyway = this::startNative;
    private boolean drmRequested;  // the page asked for protected video: wait for its licence
    private boolean pageParked;    // the page is closed to free memory while our player shows the picture
    private boolean reopening;     // the page is being opened again after Back from our player
    private long lastRestart;
    private int restarts;
    private final LinkedList<String> playerLog = new LinkedList<>();
    private final java.util.LinkedHashMap<String, Integer> segments = new java.util.LinkedHashMap<>();
    /** Ads and counters: their failures don't matter for the video and only clutter the screen. */
    private static final java.util.regex.Pattern NOISE = java.util.regex.Pattern.compile(
            "doubleclick|adriver|tns-counter|stbid|mail\\.ru|flocktory|targetads|yandex|mc\\.|googletag|google-analytics|adfox|top-fwz|mediascope|statad|adhigh|weborama|criteo|vk\\.com|ok\\.ru");

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

        diag = new TextView(this);
        diag.setTextColor(0xFFDDDDDD);
        diag.setBackgroundColor(0xCC000000);
        diag.setTextSize(12);
        diag.setPadding(16, 8, 16, 8);
        diag.setVisibility(View.GONE);

        view = new GeckoView(this);
        root.addView(view, matchParent());
        pointer = new Pointer(this);
        root.addView(pointer, matchParent());
        root.addView(diag, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));

        nativePlayer = new NativePlayer(this, root, st -> {
            if (st.startsWith("идёт")) pointer.setVisibility(View.INVISIBLE);
            refreshDiag();
        });

        if (runtime == null) runtime = GeckoRuntime.create(getApplicationContext(), runtimeSettings());
        openSession();
        installDiagAddon();
        session.loadUri(HOME_URL);
        view.requestFocus();
    }

    /** Engine settings: allow protected (Widevine) video and autoplay, nothing extra. */
    private GeckoRuntimeSettings runtimeSettings() {
        GeckoRuntimeSettings.Builder b = new GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .consoleOutput(false)
                .remoteDebuggingEnabled(false);
        try {
            File f = new File(getFilesDir(), "gecko.yaml");
            FileOutputStream out = new FileOutputStream(f);
            out.write(("prefs:\n"
                    + "  media.eme.enabled: true\n"
                    + "  media.mediadrm-widevinecdm.visible: true\n"
                    + "  media.autoplay.default: 0\n"
                    + "  media.autoplay.blocking_policy: 0\n"
                    // Less memory: the box has little, and our own player needs some too.
                    + "  browser.sessionhistory.max_total_viewers: 0\n"
                    + "  browser.cache.memory.capacity: 16384\n"
                    + "  media.memory_cache_max_size: 16384\n").getBytes("UTF-8"));
            out.close();
            b.configFilePath(f.getAbsolutePath());
        } catch (Exception e) {
            note("настройки движка: " + e);
        }
        return b.build();
    }

    private void openSession() {
        session = new GeckoSession(new GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                .userAgentMode(desktopMode() ? GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                        : GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(desktopMode() ? GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
                        : GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .build());
        session.setContentDelegate(contentDelegate);
        session.setNavigationDelegate(navigationDelegate);
        session.setProgressDelegate(progressDelegate);
        session.setPermissionDelegate(permissionDelegate);
        if (diagExt != null) session.getWebExtensionController().setMessageDelegate(diagExt, diagMessages, "browser");
        session.open(runtime);
        view.setSession(session);
        view.coverUntilFirstPaint(Color.BLACK); // no white flash while a page loads
    }

    private boolean desktopMode() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DESKTOP, false);
    }

    /** Phone version of the site <-> computer version, then reload. */
    private void toggleDesktop() {
        boolean d = !desktopMode();
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DESKTOP, d).apply();
        note("сайт как " + (d ? "компьютер" : "телефон"));
        videoState = "-";
        media.clear();
        playerLog.clear();
        segments.clear();
        resetStream();
        if (session != null) {
            view.releaseSession();
            session.close();
        }
        openSession();
        session.loadUri(currentUrl.startsWith("http") ? currentUrl : HOME_URL);
    }

    // ---------------------------------------------------------------- diagnostics add-on

    private void installDiagAddon() {
        runtime.getWebExtensionController()
                .ensureBuiltIn("resource://android/assets/diag/", "diag@telik.tv")
                .accept(ext -> {
                    diagExt = ext;
                    ext.setMessageDelegate(diagMessages, "browser");
                    if (session != null) session.getWebExtensionController().setMessageDelegate(ext, diagMessages, "browser");
                }, e -> note("диагностика не включилась: " + e));
    }

    private final WebExtension.MessageDelegate diagMessages = new WebExtension.MessageDelegate() {
        @Override
        public GeckoResult<Object> onMessage(String nativeApp, Object message, WebExtension.MessageSender sender) {
            if (message instanceof JSONObject) onDiag((JSONObject) message);
            return null;
        }
    };

    private void onDiag(JSONObject m) {
        String t = m.optString("t");
        String all = m.toString();
        if ("stream".equals(t)) {
            onStream(m);
            return;
        }
        if (!"player".equals(t) && !"video".equals(t) && NOISE.matcher(all).find()) return;
        switch (t) {
            case "player":
                playerLog.addFirst(String.format(java.util.Locale.US, "%tR:%<tS ", new java.util.Date()) + m.optString("m"));
                while (playerLog.size() > 9) playerLog.removeLast();
                refreshDiag();
                break;
            case "seg":
                segments.put(m.optString("h"), m.optInt("n"));
                refreshDiag();
                break;
            case "video":
                videoState = m.optString("m");
                refreshDiag();
                break;
            case "media":
                String u = shortUrl(m.optString("u"));
                if (!media.contains(u)) {
                    media.addFirst(u);
                    while (media.size() > 3) media.removeLast();
                    refreshDiag();
                }
                break;
            case "http":
                note("HTTP " + m.optInt("s") + " " + shortUrl(m.optString("u")));
                break;
            case "net":
                note("сеть " + m.optString("e") + " " + shortUrl(m.optString("u")));
                break;
            default:
                note(("console".equals(t) ? "консоль: " : "JS: ") + m.optString("m") + " [" + m.optString("f") + "]");
        }
    }

    // ---------------------------------------------------------------- native playback

    private static java.util.Map<String, String> headers(JSONObject h) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        if (h == null) return out;
        java.util.Iterator<String> it = h.keys();
        while (it.hasNext()) {
            String k = it.next();
            out.put(k, h.optString(k));
        }
        return out;
    }

    private void onStream(JSONObject m) {
        if (nativeDeclined || nativePlayer.isShowing()) return;
        if ("manifest".equals(m.optString("k"))) {
            if (manifestUrl != null) return; // live manifests are re-read all the time; the first one is enough
            manifestUrl = m.optString("u");
            manifestDash = "dash".equals(m.optString("f"));
            manifestHeaders = headers(m.optJSONObject("h"));
            note("поток найден (" + (manifestDash ? "DASH" : "HLS") + ")");
            // Protected video: wait for the licence request; open video: start soon.
            main.removeCallbacks(startNativeAnyway);
            main.postDelayed(startNativeAnyway, drmRequested ? 25_000 : 8_000);
        } else if ("license".equals(m.optString("k"))) {
            licenseUrl = m.optString("u");
            licenseHeaders = headers(m.optJSONObject("h"));
        }
        if (manifestUrl != null && licenseUrl != null) {
            main.removeCallbacks(startNativeAnyway);
            main.postDelayed(startNativeAnyway, 500);
        }
    }

    private void startNative() {
        if (manifestUrl == null || nativeDeclined || nativePlayer.isShowing() || isFinishing()) return;
        note("включаю свой плеер" + (licenseUrl != null ? " с DRM" : " без DRM"));
        nativePlayer.start(manifestUrl, manifestDash, manifestHeaders, licenseUrl,
                licenseHeaders != null ? licenseHeaders : new java.util.HashMap<>());
        // The box has little memory: close the page (and its stuck player) while ours plays.
        // Our player has everything it needs (stream, licence address, headers).
        if (session != null) {
            view.releaseSession();
            session.close();
            session = null;
        }
        pageParked = true;
        view.setVisibility(View.GONE); // its own drawing surface could cover our picture
        pointer.bringToFront();
        diag.bringToFront();
    }

    /** declined: the user pressed Back, so open the page again and don't jump back into the player. */
    private void stopNative(boolean declined) {
        main.removeCallbacks(startNativeAnyway);
        boolean was = nativePlayer.isShowing();
        nativePlayer.stop();
        if (declined && was) nativeDeclined = true;
        if (pageParked) {
            view.setVisibility(View.VISIBLE);
            view.requestFocus(); // so typing (login) reaches the page
            pageParked = false;
            reopening = true;
            openSession();
            session.loadUri(currentUrl.startsWith("http") ? currentUrl : HOME_URL);
        }
    }

    private void resetStream() {
        manifestUrl = licenseUrl = null;
        manifestHeaders = licenseHeaders = null;
        drmRequested = false;
        if (reopening) {
            reopening = false; // same page after Back: stay on it
            return;
        }
        nativeDeclined = false;
        if (nativePlayer.isShowing() || pageParked) stopNative(false);
    }

    private static String shortUrl(String u) {
        return u.length() > 90 ? u.substring(0, 90) + "…" : u;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (session != null) session.setActive(true);
    }

    @Override
    protected void onPause() {
        if (session != null) session.setActive(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (nativePlayer != null) nativePlayer.stop();
        if (session != null) {
            view.releaseSession();
            session.close();
            session = null;
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

    // ---------------------------------------------------------------- engine callbacks

    private final GeckoSession.ContentDelegate contentDelegate = new GeckoSession.ContentDelegate() {
        @Override
        public void onFullScreen(GeckoSession s, boolean full) {
            fullScreen = full;
        }

        @Override
        public void onCrash(GeckoSession s) {
            note("страница упала");
            restartSession();
        }

        @Override
        public void onKill(GeckoSession s) {
            note("страница выгружена из памяти");
            restartSession();
        }
    };

    private void restartSession() {
        if (s_isParked()) return; // our player is on; the page will be opened again on Back
        long now = SystemClock.uptimeMillis();
        restarts = now - lastRestart < 60_000 ? restarts + 1 : 1;
        lastRestart = now;
        if (restarts > 3) {
            note("страница падает слишком часто: перезапусти приложение");
            return;
        }
        main.postDelayed(() -> {
            if (isFinishing()) return;
            if (session != null) {
                view.releaseSession();
                session.close();
            }
            openSession();
            session.loadUri(currentUrl.startsWith("http") ? currentUrl : HOME_URL);
        }, 1_000);
    }

    private boolean s_isParked() {
        return pageParked || nativePlayer.isShowing();
    }

    private final GeckoSession.NavigationDelegate navigationDelegate = new GeckoSession.NavigationDelegate() {
        @Override
        public void onCanGoBack(GeckoSession s, boolean can) {
            canGoBack = can;
        }

        // Login and other pages the site opens "in a new window": open them right here.
        @Override
        public GeckoResult<GeckoSession> onNewSession(GeckoSession s, String uri) {
            s.loadUri(uri);
            return null;
        }

        // Inside ivi the address changes without a full page load (e.g. another channel):
        // treat it as a new page. Both forms are declared; the engine calls the one it has.
        public void onLocationChange(GeckoSession s, String url,
                java.util.List<GeckoSession.PermissionDelegate.ContentPermission> perms, Boolean gesture) {
            onAddress(url);
        }

        public void onLocationChange(GeckoSession s, String url,
                java.util.List<GeckoSession.PermissionDelegate.ContentPermission> perms) {
            onAddress(url);
        }
    };

    private void onAddress(String url) {
        if (url == null || url.equals(currentUrl) || !url.startsWith("http")) return;
        currentUrl = url;
        resetStream();
        refreshDiag();
    }

    private final GeckoSession.ProgressDelegate progressDelegate = new GeckoSession.ProgressDelegate() {
        @Override
        public void onPageStart(GeckoSession s, String url) {
            currentUrl = url;
            resetStream();
            loadState = "загрузка";
            refreshDiag();
        }

        @Override
        public void onPageStop(GeckoSession s, boolean success) {
            loadState = success ? "загружена" : "ошибка загрузки";
            if (!success) note("страница не загрузилась: " + currentUrl);
            refreshDiag();
        }
    };

    private final GeckoSession.PermissionDelegate permissionDelegate = new GeckoSession.PermissionDelegate() {
        @Override
        public GeckoResult<Integer> onContentPermissionRequest(GeckoSession s, ContentPermission perm) {
            // Protected video (DRM) and autoplay are allowed; location, camera, notifications etc. are not.
            int p = perm.permission;
            boolean ok = p == PERMISSION_MEDIA_KEY_SYSTEM_ACCESS
                    || p == PERMISSION_AUTOPLAY_AUDIBLE
                    || p == PERMISSION_AUTOPLAY_INAUDIBLE
                    || p == PERMISSION_PERSISTENT_STORAGE;
            if (p == PERMISSION_MEDIA_KEY_SYSTEM_ACCESS) {
                drmRequested = true;
                note("сайт запросил защищённое видео (DRM): разрешено");
                // The manifest may already be known: give the licence time to arrive.
                if (manifestUrl != null && licenseUrl == null) {
                    main.removeCallbacks(startNativeAnyway);
                    main.postDelayed(startNativeAnyway, 25_000);
                }
            }
            return GeckoResult.fromValue(ok ? ContentPermission.VALUE_ALLOW : ContentPermission.VALUE_DENY);
        }
    };

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
                if (diag.getVisibility() == View.VISIBLE) {
                    if (down && event.getRepeatCount() == 0) toggleDesktop();
                    return true;
                }
                if (down && event.getRepeatCount() == 0) pointer.click();
                return true;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Enter on a real keyboard goes to the page (e.g. sends the login form);
                // on a remote it is the OK button.
                if (event.getDevice() != null
                        && event.getDevice().getKeyboardType() == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                    return super.dispatchKeyEvent(event);
                }
                if (down && event.getRepeatCount() == 0) pointer.click();
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
                if (down && event.getRepeatCount() == 0) pointer.click();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) return true;
                if (diag.getVisibility() == View.VISIBLE) {
                    diag.setVisibility(View.GONE);
                } else if (nativePlayer.isShowing()) {
                    // Back from the picture to the page (e.g. to pick another channel).
                    stopNative(true);
                } else if (fullScreen && session != null) {
                    session.exitFullScreen();
                } else if (canGoBack && session != null) {
                    session.goBack();
                } else {
                    finish();
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                if (down && event.getRepeatCount() == 0) toggleDiag();
                return true;
            default:
                // Letters/digits (e.g. a keyboard on the box) and media keys go to the page as usual.
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
            if (view == null) return;
            MotionEvent e = MotionEvent.obtain(downTime, time, action, px, py, 0);
            e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            view.dispatchTouchEvent(e);
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

    // ---------------------------------------------------------------- diagnostics

    private void note(final String e) {
        main.post(() -> {
            String s = String.format(java.util.Locale.US, "%tR ", new java.util.Date())
                    + (e.length() > 200 ? e.substring(0, 200) : e);
            events.addFirst(s);
            if (events.size() > 1 && events.get(1).substring(6).equals(s.substring(6))) events.remove(1);
            while (events.size() > 5) events.removeLast();
            refreshDiag();
        });
    }

    private void toggleDiag() {
        if (diag.getVisibility() == View.VISIBLE) diag.setVisibility(View.GONE);
        else showDiag();
    }

    private void refreshDiag() {
        if (diag.getVisibility() == View.VISIBLE) showDiag();
    }

    private void showDiag() {
        diag.setTextSize(10);
        String url = currentUrl.length() > 120 ? currentUrl.substring(0, 120) + "…" : currentUrl;
        diag.setText(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME
                + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + " · движок Firefox " + org.mozilla.geckoview.BuildConfig.MOZILLA_VERSION
                + " · " + Build.SUPPORTED_ABIS[0]
                + "\nАдрес: " + url + " · " + loadState + " · сайт как " + (desktopMode() ? "компьютер" : "телефон")
                + "\nСвой плеер: " + (nativePlayer.isShowing() ? nativePlayer.state()
                        : manifestUrl != null ? (nativeDeclined ? "выключен кнопкой Назад" : "поток найден, жду лицензию") : "ждёт поток")
                + (licenseUrl != null ? " · лицензия: " + shortUrl(licenseUrl) : "")
                + "\nВидео сайта: " + videoState
                + "\nЗапросы видео: " + (media.isEmpty() ? "-" : android.text.TextUtils.join("\n   ", media))
                + "\nКуски потока: " + (segments.isEmpty() ? "-" : segments.toString())
                + "\nПлеер (новые сверху): " + (playerLog.isEmpty() ? "-" : android.text.TextUtils.join("\n   ", playerLog))
                + "\nСобытия: " + (events.isEmpty() ? "-" : android.text.TextUtils.join("\n   ", events))
                + "\n(OK — переключить сайт телефон/компьютер · Menu или Назад закрывает)");
        diag.setVisibility(View.VISIBLE);
        diag.bringToFront();
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
