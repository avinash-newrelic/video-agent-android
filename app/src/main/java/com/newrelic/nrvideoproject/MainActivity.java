package com.newrelic.nrvideoproject;

import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Switch;

import com.newrelic.videoagent.core.NRVideo;
import com.newrelic.videoagent.core.NRVideoConfiguration;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    Switch adsSwitch;
    Switch qoeSwitch;
    Switch mediaTailorSwitch;
    int counter = 0;
    NRVideoConfiguration config;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // CI auto-run: the playback-telemetry workflow launches MainActivity
        // with `-e SCENARIO_ID <id>` extras. In that mode we skip the UI,
        // apply per-run NRVA overrides, tag every future event with the run
        // metadata, and forward the launch to the right player activity.
        // A missing SCENARIO_ID means a normal interactive launch and we fall
        // through to the existing button-driven flow.
        String scenarioId = getIntent().getStringExtra("SCENARIO_ID");
        if (scenarioId != null && !scenarioId.isEmpty()) {
            startScenario(scenarioId);
            return;
        }

        NRVideoConfiguration config = new NRVideoConfiguration.Builder(BuildConfig.NR_APPLICATION_TOKEN)
                .autoDetectPlatform(getApplicationContext())
                .withHarvestCycle(30)
                .enableLogging()
                .enableQoeAggregate(BuildConfig.QOE_AGGREGATE_DEFAULT)
                .build();
        NRVideo.newBuilder(getApplicationContext()).withConfiguration(config).build();
        setContentView(R.layout.activity_main);

        adsSwitch = findViewById(R.id.ads_switch);
        qoeSwitch = findViewById(R.id.qoe_switch);
        mediaTailorSwitch = findViewById(R.id.mediatailor_switch);

        // Initialize QOE switch with current configuration state
        qoeSwitch.setChecked(config.isQoeAggregateEnabled());

        // Set up QOE switch listener with optimized UI operations
        qoeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            // Perform config update on background thread to avoid UI blocking
                    // Toggle QOE aggregate functionality at runtime
                    config.setQoeAggregateEnabled(isChecked);

                    // Show user feedback on UI thread
                        String message = "QOE Aggregate " + (isChecked ? "Enabled" : "Disabled");
                        android.widget.Toast.makeText(MainActivity.this, message, android.widget.Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.video0).setOnClickListener(this);
        findViewById(R.id.video1).setOnClickListener(this);
        findViewById(R.id.video2).setOnClickListener(this);
        findViewById(R.id.video3).setOnClickListener(this);
        findViewById(R.id.video4).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Map<String, Object> attr = new HashMap<>();
                attr.put("kind", "counter");
                attr.put("actionName", "CLICK");
                attr.put("counter", counter++);
                NRVideo.recordCustomEvent(attr);

                Map<String, Object> emptyAttr = new HashMap<>();
                NRVideo.recordCustomEvent(emptyAttr);
            }
        });

        Map<String, Object> attr = new HashMap<>();
        attr.put("actionName", "AGENT_TEST");
        attr.put("intVal", 1001);
        attr.put("floatVal", 1.23);
        attr.put("strVal", "this is a string");
        attr.put("kind", "app start successfully");
        NRVideo.recordCustomEvent(attr);
    }

    @Override
    public void onClick(View v) {
        Intent intent = new Intent(this, getVideoActivity());
        Object tag = v.getTag();
        String videoTag = tag != null ? tag.toString() : "default";
        intent.putExtra("video", videoTag);
        startActivity(intent);
    }

    Class getVideoActivity() {
        if (mediaTailorSwitch.isChecked()) {
            return VideoPlayerMediaTailor.class;
        }
        if (adsSwitch.isChecked()) {
            return VideoPlayerAds.class;
        }
        return VideoPlayer.class;
    }

    // ── CI scenario dispatch ─────────────────────────────────────────────────
    //
    // Each `scenarioId` maps to (target activity, playback URL, optional ad
    // tag URL). We build NRVideo with any NR_* overrides passed as intent
    // extras (currently only harvest cycle is exposed), set the run/leg/
    // scenario metadata as global attributes so every event NRVA emits during
    // this process is tagged, and hand off to the player activity.
    //
    // The URLs here have to keep working from a CI runner with no external
    // secrets — public sample streams, and the Google-hosted VMAP tag the
    // interactive flow already uses. A deliberately-invalid URL powers the
    // content-error scenario; a syntactically-valid-but-nonexistent VMAP
    // powers ad-error.
    //
    // MediaTailor is a special case: MT_SESSION_INIT_URL must be passed as
    // an extra (from a workflow secret). If it's absent the target activity
    // writes SCENARIO_DONE:skipped and exits so the workflow doesn't hang.
    private void startScenario(String scenarioId) {
        int harvestCycleSecs = getIntent().getIntExtra("NR_HARVEST_CYCLE_SECS", 30);
        String collectorAddress = getIntent().getStringExtra("NR_COLLECTOR_ADDRESS");

        NRVideoConfiguration.Builder builder = new NRVideoConfiguration.Builder(BuildConfig.NR_APPLICATION_TOKEN)
                .autoDetectPlatform(getApplicationContext())
                .withHarvestCycle(harvestCycleSecs)
                .enableLogging()
                .enableQoeAggregate(BuildConfig.QOE_AGGREGATE_DEFAULT);

        // A non-empty NR_COLLECTOR_ADDRESS points the /connect and /data
        // endpoints at a non-prod NR ingest (typically the staging collector
        // in CI runs). Leaving it unset preserves the SDK's built-in prod
        // default rather than substituting an empty string, which the agent
        // would try to route to.
        if (collectorAddress != null && !collectorAddress.isEmpty()) {
            builder.withCollectorAddress(collectorAddress);
        }

        NRVideoConfiguration cfg = builder.build();
        NRVideo.newBuilder(getApplicationContext()).withConfiguration(cfg).build();

        // Tag every event this process emits with the run metadata. Reading
        // any of these attributes back via NRQL is how the workflow finds
        // its own events; a missing extra falls back to a sentinel string
        // that stands out in the UI rather than silently dropping the attr.
        String[] metaKeys = {"runId", "nrvaVersion", "legTag", "scenarioId", "gitSha", "triggerTime"};
        String[] extraNames = {"RUN_ID", "NRVA_VERSION", "LEG_TAG", "SCENARIO_ID", "GIT_SHA", "TRIGGER_TIME"};
        for (int i = 0; i < metaKeys.length; i++) {
            String v = getIntent().getStringExtra(extraNames[i]);
            NRVideo.setGlobalAttribute(metaKeys[i], v != null ? v : "(unset)");
        }

        String contentMp4 = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4";
        String contentHls = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8";
        String invalidUrl = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/does-not-exist-4032.m3u8";
        String vmapValid  = getString(R.string.ad_tag_url);
        String vmapBroken = "https://example.com/does-not-exist-vmap.xml";

        Class<?> target;
        String url;
        String adTag = null;
        // Per-scenario default duration. Ads need enough headroom for the
        // pre-roll fetch + decode + first-content-play cycle before we cut
        // them off; content lifecycle scenarios don't. The workflow can
        // still override via SCENARIO_DURATION_MS to shorten or lengthen
        // any specific run.
        int defaultDurationMs;

        switch (scenarioId) {
            case "content-hls-lifecycle":
                target = VideoPlayer.class;         url = contentHls;   defaultDurationMs = 30000;  break;
            case "content-mp4-lifecycle":
                target = VideoPlayer.class;         url = contentMp4;   defaultDurationMs = 30000;  break;
            case "content-error":
                target = VideoPlayer.class;         url = invalidUrl;   defaultDurationMs = 20000;  break;
            case "ad-vmap-lifecycle":
                target = VideoPlayerAds.class;      url = contentMp4;   adTag = vmapValid;   defaultDurationMs = 75000;  break;
            case "ad-error":
                target = VideoPlayerAds.class;      url = contentMp4;   adTag = vmapBroken;  defaultDurationMs = 30000;  break;
            case "ssai-mediatailor-lifecycle":
                target = VideoPlayerMediaTailor.class; url = "";        defaultDurationMs = 45000;  break;
            default:
                // Unknown scenario id — MainActivity itself writes the sentinel
                // so the workflow's poll can complete on the correct code path
                // instead of timing out with no explanation.
                writeSentinel(scenarioId, "unknown-scenario");
                finishAndRemoveTask();
                return;
        }

        int durationMs = getIntent().getIntExtra("SCENARIO_DURATION_MS", defaultDurationMs);

        Intent intent = new Intent(this, target);
        intent.putExtra("scenario", scenarioId);
        intent.putExtra("scenarioUrl", url);
        intent.putExtra("scenarioDurationMs", durationMs);
        if (adTag != null) intent.putExtra("adTagUrl", adTag);
        String mtInitUrl = getIntent().getStringExtra("MT_SESSION_INIT_URL");
        if (mtInitUrl != null) intent.putExtra("sessionInitUrl", mtInitUrl);
        startActivity(intent);
        finish();
    }

    private void writeSentinel(String scenarioId, String reason) {
        try {
            java.io.File dir = new java.io.File(getExternalFilesDir(null), "logs");
            if (!dir.exists()) dir.mkdirs();
            java.io.File f = new java.io.File(dir, "auto-play-" + scenarioId + ".log");
            try (java.io.FileWriter w = new java.io.FileWriter(f, true)) {
                w.write("SCENARIO_DONE:" + scenarioId + " reason=" + reason + "\n");
            }
        } catch (java.io.IOException ignore) { }
    }
}
