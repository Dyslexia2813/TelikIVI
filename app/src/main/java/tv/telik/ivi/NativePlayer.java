package tv.telik.ivi;

import android.content.Context;
import android.graphics.Color;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Android's own video player (Media3/ExoPlayer with the box's Widevine DRM), full screen over the page.
 * The site still picks the channel and gets the licence; the picture comes from here because the
 * built-in Firefox engine gets the keys but never shows a frame on this box.
 */
final class NativePlayer {

    interface Listener {
        void onState(String state);
    }

    private final Context context;
    private final FrameLayout root;
    private final Listener listener;
    private SurfaceView surface;
    private ExoPlayer player;
    private String state = "-";
    private int retries;
    private Runnable retry;

    NativePlayer(Context context, FrameLayout root, Listener listener) {
        this.context = context;
        this.root = root;
        this.listener = listener;
    }

    boolean isShowing() {
        return player != null;
    }

    String state() {
        return state;
    }

    /** Request headers worth passing on (browser identity, origin, cookies, auth); not transport ones. */
    static Map<String, String> usefulHeaders(Map<String, String> in) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : in.entrySet()) {
            String k = e.getKey().toLowerCase();
            if (k.equals("host") || k.equals("content-length") || k.equals("accept-encoding") || k.equals("range")
                    || k.equals("connection") || k.startsWith("sec-") || k.equals("te") || k.equals("priority")
                    || k.equals("if-none-match") || k.equals("if-modified-since")) continue;
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    void start(String manifest, boolean dash, Map<String, String> headers, String licenseUrl,
               Map<String, String> licenseHeaders) {
        stop();
        retries = 0;
        surface = new SurfaceView(context);
        surface.setBackgroundColor(Color.TRANSPARENT);
        // Above the page, below the pointer and diagnostics.
        root.addView(surface, 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER));
        root.setBackgroundColor(Color.BLACK);

        Map<String, String> h = usefulHeaders(headers);
        String ua = null;
        for (String k : new java.util.ArrayList<>(h.keySet())) {
            if (k.equalsIgnoreCase("user-agent")) ua = h.remove(k);
        }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(15_000)
                .setDefaultRequestProperties(h);
        if (ua != null) http.setUserAgent(ua);

        MediaItem.Builder item = new MediaItem.Builder()
                .setUri(manifest)
                .setMimeType(dash ? MimeTypes.APPLICATION_MPD : MimeTypes.APPLICATION_M3U8);
        if (licenseUrl != null) {
            Map<String, String> lh = usefulHeaders(licenseHeaders);
            for (String k : new java.util.ArrayList<>(lh.keySet())) {
                if (k.equalsIgnoreCase("user-agent")) lh.remove(k);
            }
            item.setDrmConfiguration(new MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(licenseUrl)
                    .setLicenseRequestHeaders(lh)
                    .setMultiSession(true)
                    .build());
        }

        player = new ExoPlayer.Builder(context,
                new DefaultRenderersFactory(context).setEnableDecoderFallback(true),
                new DefaultMediaSourceFactory(http))
                .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                .build();
        player.setVideoSurfaceView(surface);
        player.addListener(events);
        player.setMediaItem(item.build());
        player.setPlayWhenReady(true);
        player.prepare();
        setState("подключение");
    }

    void stop() {
        if (retry != null) root.removeCallbacks(retry);
        retry = null;
        if (player != null) {
            player.removeListener(events);
            player.release();
            player = null;
        }
        if (surface != null) {
            root.removeView(surface);
            surface = null;
        }
        state = "-";
    }

    private void setState(String s) {
        state = s;
        listener.onState(s);
    }

    private final Player.Listener events = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int s) {
            if (s == Player.STATE_READY) {
                retries = 0;
                Format f = player.getVideoFormat();
                setState("идёт" + (f != null ? " " + f.width + "x" + f.height : ""));
            } else if (s == Player.STATE_BUFFERING) {
                setState("буферизация");
            } else if (s == Player.STATE_ENDED) {
                setState("поток закончился");
            }
        }

        @Override
        public void onVideoSizeChanged(VideoSize size) {
            fit(size);
        }

        @Override
        public void onPlayerError(PlaybackException e) {
            Throwable c = e.getCause();
            setState("ОШИБКА " + e.getErrorCodeName() + (c != null ? ": " + c : ""));
            if (player == null) return;
            if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition();
                player.prepare();
                return;
            }
            // Short network trouble: try again a few times, then leave the error on screen.
            if (retries++ < 3) {
                retry = () -> {
                    if (player != null) {
                        player.prepare();
                        player.setPlayWhenReady(true);
                    }
                };
                root.postDelayed(retry, 2_000L * retries);
            }
        }
    };

    /** Keep the picture's aspect ratio: letterbox inside the screen instead of stretching. */
    private void fit(VideoSize size) {
        if (surface == null || size.width <= 0 || size.height <= 0) return;
        int sw = root.getWidth(), sh = root.getHeight();
        if (sw <= 0 || sh <= 0) return;
        float ratio = size.width * size.pixelWidthHeightRatio / size.height;
        int w = sw, h = Math.round(sw / ratio);
        if (h > sh) {
            h = sh;
            w = Math.round(sh * ratio);
        }
        surface.setLayoutParams(new FrameLayout.LayoutParams(w, h, android.view.Gravity.CENTER));
    }
}
