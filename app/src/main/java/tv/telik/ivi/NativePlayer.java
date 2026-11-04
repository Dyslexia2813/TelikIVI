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
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

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

        void onQualityChanged();
    }

    private final Context context;
    private final FrameLayout root;
    private final Listener listener;
    private SurfaceView surface;
    private ExoPlayer player;
    private String state = "-";
    private int retries;
    private DefaultTrackSelector trackSelector;
    /** Max: always the best variant; auto: follows the network speed. */
    private boolean maxQuality;
    /** The box's decoder really refused the top variant once: respect its own limits from then on. */
    private static boolean strictDecoder;
    private Runnable restartWithSameStream;
    private boolean firstFrame;
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
        restartWithSameStream = () -> start(manifest, dash, headers, licenseUrl, licenseHeaders);
        retries = 0;
        surface = new SurfaceView(context);
        // The page engine draws on a surface of its own: keep ours on top of it.
        surface.setZOrderMediaOverlay(true);
        firstFrame = false;
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

        // Start at the best quality (1024x576 on ivi) instead of climbing up from the lowest one.
        androidx.media3.exoplayer.upstream.DefaultBandwidthMeter meter =
                new androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(context)
                        .setInitialBitrateEstimate(20_000_000L)
                        .build();
        // The box reports a 1280x720 screen even when the TV gets 1080p: don't cap the quality at that.
        trackSelector = new DefaultTrackSelector(context);
        trackSelector.setParameters(qualityParams());
        player = new ExoPlayer.Builder(context,
                strictDecoder ? new DefaultRenderersFactory(context).setEnableDecoderFallback(true)
                        : new TrustingRenderersFactory(context),
                new DefaultMediaSourceFactory(http))
                .setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                .setBandwidthMeter(meter)
                .setTrackSelector(trackSelector)
                .build();
        player.setVideoSurfaceView(surface);
        player.addListener(events);
        player.setMediaItem(item.build());
        player.setPlayWhenReady(true);
        player.prepare();
        setState("подключение");
    }

    private DefaultTrackSelector.Parameters qualityParams() {
        DefaultTrackSelector.Parameters.Builder b = trackSelector.buildUponParameters()
                .clearViewportSizeConstraints()
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setForceHighestSupportedBitrate(maxQuality);
        if (strictDecoder) b.setMaxVideoSize(1920, 1080);
        else b.clearVideoSizeConstraints();
        return b.build();
    }

    boolean isMaxQuality() {
        return maxQuality;
    }

    /** Switch between automatic and maximum quality; works on the fly. */
    void setMaxQuality(boolean max) {
        maxQuality = max;
        if (trackSelector != null) trackSelector.setParameters(qualityParams());
    }

    /** Current picture size and the variants the stream offers, e.g. "1280x720 · варианты 720p 1080p*". */
    String qualityInfo() {
        if (player == null) return "-";
        StringBuilder sb = new StringBuilder();
        Format cur = player.getVideoFormat();
        sb.append(cur != null ? cur.width + "x" + cur.height : "-");
        if (cur != null && cur.bitrate > 0) sb.append(' ').append(cur.bitrate / 1000).append(" кбит/с");
        sb.append(" · варианты:");
        for (androidx.media3.common.Tracks.Group g : player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < g.length; i++) {
                Format f = g.getTrackFormat(i);
                sb.append(' ').append(f.height > 0 ? f.height + "p" : "?");
                if (g.isTrackSelected(i)) sb.append('*');
                if (!g.isTrackSupported(i)) sb.append("(не тянет)");
            }
        }
        if (strictDecoder) sb.append(" · осторожный режим");
        return sb.toString();
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
                setState("идёт" + (f != null ? " " + f.width + "x" + f.height : "")
                        + (firstFrame ? ", картинка есть" : ", картинки пока нет"));
            } else if (s == Player.STATE_BUFFERING) {
                setState("буферизация");
            } else if (s == Player.STATE_ENDED) {
                setState("поток закончился");
            }
        }

        @Override
        public void onVideoSizeChanged(VideoSize size) {
            fit(size);
            listener.onQualityChanged();
        }

        @Override
        public void onRenderedFirstFrame() {
            firstFrame = true;
            Format f = player != null ? player.getVideoFormat() : null;
            setState("идёт, картинка есть" + (f != null ? " " + f.width + "x" + f.height : ""));
        }

        @Override
        public void onPlayerError(PlaybackException e) {
            Throwable c = e.getCause();
            setState("ОШИБКА " + e.getErrorCodeName() + (c != null ? ": " + c : ""));
            if (player == null) return;
            if (!strictDecoder && isDecoderError(e) && restartWithSameStream != null) {
                // The hardware really can't do the top variant: restart within the box's own limits.
                strictDecoder = true;
                root.post(restartWithSameStream);
                return;
            }
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

    private static boolean isDecoderError(PlaybackException e) {
        int c = e.errorCode;
        return c == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
                || c == PlaybackException.ERROR_CODE_DECODING_FAILED
                || c == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
                || c == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                || c == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED;
    }

    /**
     * Cheap boxes under-report their hardware decoder (e.g. call 1080p "too much"), so the player would
     * skip the best variants. Trust the hardware; a real decoder failure switches to strict mode.
     */
    private static final class TrustingRenderersFactory extends DefaultRenderersFactory {
        TrustingRenderersFactory(Context context) {
            super(context);
            setEnableDecoderFallback(true);
        }

        @Override
        protected void buildVideoRenderers(Context context, int extensionRendererMode,
                androidx.media3.exoplayer.mediacodec.MediaCodecSelector selector, boolean enableDecoderFallback,
                android.os.Handler eventHandler, androidx.media3.exoplayer.video.VideoRendererEventListener eventListener,
                long allowedVideoJoiningTimeMs, java.util.ArrayList<androidx.media3.exoplayer.Renderer> out) {
            out.add(new androidx.media3.exoplayer.video.MediaCodecVideoRenderer(context, selector,
                    allowedVideoJoiningTimeMs, enableDecoderFallback, eventHandler, eventListener, 50) {
                @Override
                protected int supportsFormat(androidx.media3.exoplayer.mediacodec.MediaCodecSelector sel, Format format)
                        throws androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException {
                    int caps = super.supportsFormat(sel, format);
                    if ((caps & 0b111) == C.FORMAT_EXCEEDS_CAPABILITIES) caps = (caps & ~0b111) | C.FORMAT_HANDLED;
                    return caps;
                }
            });
        }
    }

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
