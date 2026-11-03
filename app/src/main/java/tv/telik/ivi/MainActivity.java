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
 * - Menu shows diagnostics.
 * - Login cookies are kept between runs; the chosen channel opens on start.
 */
public class MainActivity extends Activity {

    /** Opened on every start (the user's chosen channel for now). */
    private static final String HOME_URL = "https://www.ivi.ru/tvchannels/watch/cgtn";

    /** One engine per app process. */
    private static GeckoRuntime runtime;

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

        if (runtime == null) runtime = GeckoRuntime.create(getApplicationContext(), runtimeSettings());
        openSession();
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
                    + "  media.autoplay.blocking_policy: 0\n").getBytes("UTF-8"));
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
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .build());
        session.setContentDelegate(contentDelegate);
        session.setNavigationDelegate(navigationDelegate);
        session.setProgressDelegate(progressDelegate);
        session.setPermissionDelegate(permissionDelegate);
        session.open(runtime);
        view.setSession(session);
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
            note("страница упала, перезапуск");
            restartSession();
        }

        @Override
        public void onKill(GeckoSession s) {
            note("страница выгружена из памяти, перезапуск");
            restartSession();
        }
    };

    private void restartSession() {
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

    private final GeckoSession.NavigationDelegate navigationDelegate = new GeckoSession.NavigationDelegate() {
        @Override
        public void onCanGoBack(GeckoSession s, boolean can) {
            canGoBack = can;
        }
    };

    private final GeckoSession.ProgressDelegate progressDelegate = new GeckoSession.ProgressDelegate() {
        @Override
        public void onPageStart(GeckoSession s, String url) {
            currentUrl = url;
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
            if (p == PERMISSION_MEDIA_KEY_SYSTEM_ACCESS) note("сайт запросил защищённое видео (DRM): разрешено");
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
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (down && event.getRepeatCount() == 0) pointer.click();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down) return true;
                if (diag.getVisibility() == View.VISIBLE) {
                    diag.setVisibility(View.GONE);
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
            while (events.size() > 6) events.removeLast();
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
        String url = currentUrl.length() > 120 ? currentUrl.substring(0, 120) + "…" : currentUrl;
        diag.setText("TelikIVI " + BuildConfig.VERSION_NAME
                + " · Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + " · движок Firefox " + org.mozilla.geckoview.BuildConfig.MOZILLA_VERSION
                + " · " + Build.SUPPORTED_ABIS[0]
                + "\nАдрес: " + url + " · " + loadState
                + "\nСобытия: " + (events.isEmpty() ? "-" : android.text.TextUtils.join("\n   ", events))
                + "\n(Menu или Назад закрывает)");
        diag.setVisibility(View.VISIBLE);
        diag.bringToFront();
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
