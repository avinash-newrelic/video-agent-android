package com.newrelic.nrvideoproject;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.newrelic.videoagent.core.NRAdConfig;
import com.newrelic.videoagent.core.NRVideo;
import com.newrelic.videoagent.core.NRVideoPlayerConfiguration;
import com.newrelic.videoagent.core.NewRelicVideoAgent;
import com.newrelic.videoagent.core.tracker.NRTracker;
import com.newrelic.videoagent.core.tracker.NRVideoTracker;
import com.newrelic.videoagent.exoplayer.tracker.NRTrackerExoPlayer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VideoPlayer extends AppCompatActivity {

    private ExoPlayer player;
    private Integer trackerId;

    // Scenario-mode state. When scenarioId is non-null, this activity was
    // launched by MainActivity's CI dispatch path: the URL comes from the
    // intent, playback runs for a fixed duration or until an error, and the
    // activity finishes itself after writing the SCENARIO_DONE sentinel.
    private String scenarioId;
    private final Handler scenarioHandler = new Handler(Looper.getMainLooper());
    private boolean scenarioFinished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);

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

    // ── CI scenario-mode playback ──────────────────────────────────────────
    //
    // Reuses the interactive playVideo() to build the tracker + player so the
    // scenario exercises the same code path as a real user session, then
    // layers three additional behaviours on top:
    //
    //   1. VIEW_ID:<id> is written to the log as soon as the tracker exists.
    //      The workflow doesn't need this for polling, but a missing viewId
    //      when the scenario ostensibly succeeded is an unambiguous signal
    //      that NRVA never registered.
    //   2. A Player.Listener finishes the scenario early on player error —
    //      the content-error scenario relies on this to complete in seconds
    //      instead of running out the full duration timeout.
    //   3. A postDelayed writes SCENARIO_DONE and finishes the activity
    //      after scenarioDurationMs so long-form streams don't hold the
    //      test job open indefinitely.
    private void playScenario() {
        String url = getIntent().getStringExtra("scenarioUrl");
        int durationMs = getIntent().getIntExtra("scenarioDurationMs", 30000);
        playVideo(url);

        writeLogLine("VIEW_ID:" + getViewIdOrPlaceholder());

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                finishScenario("player-error:" + error.getErrorCodeName());
            }
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) finishScenario("ended");
            }
        });

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
            Log.w("VideoPlayer", "scenario log write failed", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        NRVideo.releaseTracker(trackerId);
        player.stop();
    }

    private void playVideo(String videoUrl) {
        player = new ExoPlayer.Builder(this).build();

        Map<String, Object> customAttr = new HashMap<>();
        customAttr.put("something", "This is my test title");
        customAttr.put("myAttrStr", "Hello");
        customAttr.put("myAttrInt", 101);
        customAttr.put("name", "nr-video-agent-android-01-24JUL-john-starc");
        NRVideoPlayerConfiguration playerConfiguration = new NRVideoPlayerConfiguration("test-player", player, (NRAdConfig) null, customAttr);
        trackerId = NRVideo.addPlayer(playerConfiguration);
        // Get the content tracker and configure aggregation
        NRTracker tracker = NewRelicVideoAgent.getInstance().getContentTracker(trackerId);
        if (tracker instanceof NRTrackerExoPlayer) {
            Boolean aggregationEnabled = true;
            ((NRTrackerExoPlayer) tracker).setDroppedFrameAggregationEnabled(aggregationEnabled); // true for testing
            Log.d("VideoPlayer", "CONTENT_DROPPED_FRAMES events aggregation enabled: " + aggregationEnabled);
        }

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("actionName", "VIDEO_STARTED");
        attributes.put("videoUrl", videoUrl);
        attributes.put("playerType", "ExoPlayer");
        NRVideo.recordCustomEvent(attributes, trackerId);

        PlayerView playerView = findViewById(R.id.player);
        playerView.setPlayer(player);
        // Set the playlist URIs
        List<Uri> uris = new ArrayList<>();
        uris.add(Uri.parse(videoUrl));
        player.setMediaItem(MediaItem.fromUri(videoUrl));
        // Prepare the player.
        player.setPlayWhenReady(true);
        player.prepare();
    }
}
