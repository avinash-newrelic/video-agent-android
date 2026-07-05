package com.newrelic.nrvideoproject;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import com.google.ads.interactivemedia.v3.api.AdErrorEvent;
import com.google.ads.interactivemedia.v3.api.AdEvent;
import com.newrelic.videoagent.core.NRAdConfig;
import com.newrelic.videoagent.core.NRVideo;
import com.newrelic.videoagent.core.NRVideoPlayerConfiguration;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.ima.ImaAdsLoader;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import android.util.Log;
import com.newrelic.videoagent.core.NewRelicVideoAgent;
import com.newrelic.videoagent.core.tracker.NRTracker;
import com.newrelic.videoagent.core.tracker.NRVideoTracker;
import com.newrelic.videoagent.ima.tracker.NRTrackerIMA;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * @OptIn is required for Media3 IMA ads integration APIs which are marked as @UnstableApi.
 * This is by design as per Google's Media3 documentation.
 * @see <a href="https://developer.android.com/media/media3/exoplayer/customization#unstable-api">Media3 Unstable API Documentation</a>
 */
@OptIn(markerClass = UnstableApi.class)
public class VideoPlayerAds extends AppCompatActivity implements AdErrorEvent.AdErrorListener, AdEvent.AdEventListener {

    private ExoPlayer player;
    private Integer trackerId;
    private ImaAdsLoader adsLoader;
    private PlayerView playerView;
    private com.newrelic.videoagent.ima.tracker.NRTrackerIMA adTracker;

    // Scenario-mode state. See VideoPlayer.playScenario() for the identical
    // pattern — this activity adds an AdError early-finish path because the
    // ad-error scenario relies on IMA to surface its failure, not ExoPlayer.
    private String scenarioId;
    private final Handler scenarioHandler = new Handler(Looper.getMainLooper());
    private boolean scenarioFinished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player_ads);

        playerView = findViewById(R.id.player);

        scenarioId = getIntent().getStringExtra("scenario");
        if (scenarioId != null) {
            playScenario();
            return;
        }

        String video = getIntent().getStringExtra("video");

        if (video.equals("Tears")) {
            Log.v("VideoPlayer", "Play Tears");
            playVideo("https://turtle-tube.appspot.com/t/t2/dash.mpd");
        }
        else if (video.equals("Playhouse")) {
            Log.v("VideoPlayer", "Play Playhouse");
            playVideo("https://turtle-tube.appspot.com/t/t2/dash.mpd");
        }
        else if (video.equals("Kite")) {
            Log.v("VideoPlayer", "Play Kite");
            playVideo("https://turtle-tube.appspot.com/t/t2/dash.mpd");
        }
        else if (video.equals("Live")) {
            Log.v("VideoPlayer", "Play Live");
            playVideo("https://turtle-tube.appspot.com/t/t2/dash.mpd");
        }
        else {
            Log.v("VideoPlayer","Unknown video");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NRVideo.releaseTracker(trackerId);
        player.stop();
    }

    private void playVideo(String videoUrl) {
        playVideo(videoUrl, getString(R.string.ad_tag_url));
    }

    private void playVideo(String videoUrl, String adTagUrl) {
        // Set up the factory for media sources, passing the ads loader and ad view providers.
        DataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this);
        DefaultMediaSourceFactory mediaSourceFactory = new DefaultMediaSourceFactory(dataSourceFactory);
        mediaSourceFactory.setAdsLoaderProvider(unusedAdTagUri -> adsLoader);
        mediaSourceFactory.setAdViewProvider(playerView);

        player = new ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).build();
        NRVideoPlayerConfiguration playerConfiguration = new NRVideoPlayerConfiguration("test-player-something-else", player, NRAdConfig.csai(), null);
        trackerId = NRVideo.addPlayer(playerConfiguration);
        adTracker = (NRTrackerIMA) NewRelicVideoAgent.getInstance().getAdTracker(trackerId);
        // Get the ad tracker before building the loader
        // Set ad event and error listeners on the builder
        ImaAdsLoader.Builder builder = new ImaAdsLoader.Builder(this);
        builder.setAdErrorListener(this);
        builder.setAdEventListener(this);
//          OR
//        builder.setAdErrorListener(adTracker.getAdErrorListener());
//        builder.setAdEventListener(adTracker.getAdEventListener());
        adsLoader = builder.build();
        playerView.setPlayer(player);
        adsLoader.setPlayer(player);

        // Create the MediaItem to play, specifying the content URI and ad tag URI.
        Uri contentUri = Uri.parse(videoUrl);
        Uri adTagUri = Uri.parse(adTagUrl);
        MediaItem mediaItem = new MediaItem.Builder().setUri(contentUri).setAdTagUri(adTagUri).build();
        // Prepare the content and ad to be played with the ExoPlayer.
        player.setMediaItem(mediaItem);
        // Set PlayWhenReady. If true, content and ads will autoplay.
        player.setPlayWhenReady(true);
        player.prepare();
    }

    @Override
    public void onAdError(AdErrorEvent adErrorEvent) {
        if (NewRelicVideoAgent.getInstance().getAdTracker(trackerId) != null) {
            ((NRTrackerIMA) NewRelicVideoAgent.getInstance().getAdTracker(trackerId)).handleAdError(adErrorEvent);
        }
        // In scenario mode an ad error is a terminal event for ad-error and
        // a soft signal for ad-vmap-lifecycle (a broken pod shouldn't fail
        // the lifecycle scenario). We finish either way; the scenario id in
        // the log tells apart which was expected.
        if (scenarioId != null) {
            finishScenario("ad-error:" + adErrorEvent.getError().getErrorCodeNumber());
        }
    }

    //AdEvent.AdEventListener
    @Override
    public void onAdEvent(AdEvent adEvent) {
        if (NewRelicVideoAgent.getInstance().getAdTracker(trackerId) != null) {
            ((NRTrackerIMA) NewRelicVideoAgent.getInstance().getAdTracker(trackerId)).handleAdEvent(adEvent);
        }
    }

    // ── CI scenario-mode playback ────────────────────────────────────────────
    //
    // Same shape as VideoPlayer.playScenario(). The distinction: ad scenarios
    // route through the AdError callback above for early finish, and the
    // fallback timeout is deliberately longer (default 60s) because a full
    // pre-roll + first content play window doesn't fit in 30s once IMA has
    // fetched, decoded, and rendered the ad.
    private void playScenario() {
        String url = getIntent().getStringExtra("scenarioUrl");
        String adTag = getIntent().getStringExtra("adTagUrl");
        int durationMs = getIntent().getIntExtra("scenarioDurationMs", 60000);

        if (adTag == null || adTag.isEmpty()) {
            adTag = getString(R.string.ad_tag_url);
        }
        playVideo(url, adTag);

        writeLogLine("VIEW_ID:" + getViewIdOrPlaceholder());
        scenarioHandler.postDelayed(() -> finishScenario("timeout"), durationMs);
    }

    private String getViewIdOrPlaceholder() {
        try {
            NRTracker t = NewRelicVideoAgent.getInstance().getContentTracker(trackerId);
            if (t instanceof NRVideoTracker) return ((NRVideoTracker) t).getViewId();
        } catch (Exception ignore) { }
        return "(unavailable)";
    }

    private synchronized void finishScenario(String reason) {
        if (scenarioFinished) return;
        scenarioFinished = true;
        scenarioHandler.removeCallbacksAndMessages(null);
        writeLogLine("SCENARIO_DONE:" + scenarioId + " reason=" + reason);
        finishAndRemoveTask();
    }

    private void writeLogLine(String line) {
        try {
            // See MainActivity.writeSentinel for why this is internal
            // storage and not getExternalFilesDir — scoped storage on
            // Android 11+ blocks `adb shell` from reading external app
            // files, which breaks the CI workflow's SCENARIO_DONE poll.
            File dir = new File(getFilesDir(), "logs");
            if (!dir.exists()) dir.mkdirs();
            try (FileWriter w = new FileWriter(new File(dir, "auto-play-" + scenarioId + ".log"), true)) {
                w.write(line + "\n");
            }
        } catch (IOException e) {
            Log.w("VideoPlayerAds", "scenario log write failed", e);
        }
    }
}
