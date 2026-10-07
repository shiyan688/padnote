package com.padnote.android;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowInsets;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Button;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Debug-only host for exercising the real Agent settings dialogs with isolated fixtures. */
abstract class AgentSettingsLayoutHarnessBaseActivity extends Activity {
    static final String EXTRA_PREFERENCES = "fixture_preferences";
    static final String FIXTURE_BUTTON = "打开连接设置";
    private static final String[] SCREENSHOT_NAMES = {
            "list-second.png", "action-identity.png", "action-probe.png",
            "capability-top.png", "capability-bottom.png"
    };

    private String preferencesName;
    private AgentConnectionStore store;
    private ExecutorService worker;

    protected float localFontScaleOverride() { return 0.0f; }

    @Override protected void attachBaseContext(Context base) {
        float fontScale = localFontScaleOverride();
        if (fontScale > 0.0f) {
            Configuration local = new Configuration(base.getResources().getConfiguration());
            local.fontScale = fontScale;
            super.attachBaseContext(base.createConfigurationContext(local));
        } else {
            super.attachBaseContext(base);
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferencesName = getIntent().getStringExtra(EXTRA_PREFERENCES);
        if (preferencesName == null || !preferencesName.matches("agent-layout-[0-9a-fA-F-]{36}")) {
            throw new IllegalArgumentException("Missing UUID-owned settings fixture");
        }
        File screenshotDirectory = new File(new File(getFilesDir(), "ui-fixtures"),
                preferencesName);
        if (screenshotDirectory.exists()) {
            throw new IllegalStateException("Refusing to reuse an existing screenshot fixture");
        }
        SharedPreferences preferences = getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
        if (!preferences.getAll().isEmpty()) {
            throw new IllegalStateException("Refusing to reuse a non-empty settings fixture");
        }
        store = new AgentConnectionStore(preferences, System::currentTimeMillis);
        seedSyntheticProfiles();
        worker = Executors.newSingleThreadExecutor();

        Button open = new Button(this);
        open.setAllCaps(false);
        open.setText(FIXTURE_BUTTON);
        open.setOnClickListener(view -> showDialogs());
        FrameLayout safeContent = new FrameLayout(this);
        safeContent.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });
        safeContent.addView(open, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(safeContent);
        safeContent.requestApplyInsets();
        showDialogs();
    }

    private void seedSyntheticProfiles() {
        String longName = "同名测试用中文超长连接名称".repeat(5);
        String host = "https://same-computer-host-with-a-long-layout-name.fixture-android-testing.invalid:";
        addProfile(longName, host + "43127", "bridge-alpha", "inst-alpha",
                "synthetic-layout-token-alpha");
        addProfile(longName, host + "43128", "bridge-bravo", "inst-bravo",
                "synthetic-layout-token-bravo");
    }

    private void addProfile(String name, String endpoint, String bridgeId, String instanceId,
                            String syntheticToken) {
        try {
            AgentConnectionStore.Config profile = store.addBridge(name,
                    AgentConnectionStore.Kind.HERMES, endpoint, bridgeId, instanceId, syntheticToken);
            Map<String, Boolean> features = new LinkedHashMap<>();
            features.put("run_submission", true);
            features.put("run_status", true);
            features.put("run_stop", false);
            features.put("run_attachment", false);
            features.put("runtime_verified", true);
            if (!store.applyProbeSuccess(profile.id, profile.revision,
                    new AgentConnectionClient.ProbeResult("合成能力检查完成", bridgeId,
                            instanceId, features))) {
                throw new IllegalStateException("Unable to seed isolated profile");
            }
        } catch (Exception error) {
            throw new IllegalStateException("Unable to seed synthetic settings fixture", error);
        }
    }

    private void showDialogs() {
        new AgentConnectionDialogs(this, store, worker, () -> { }, null, null).show();
    }

    void writeFixtureScreenshot(String fileName, byte[] png) throws IOException {
        boolean allowed = false;
        for (String candidate : SCREENSHOT_NAMES) {
            if (candidate.equals(fileName)) allowed = true;
        }
        if (!allowed || png == null || png.length == 0) {
            throw new IllegalArgumentException("Unexpected UI fixture screenshot");
        }
        File fixtureRoot = new File(getFilesDir(), "ui-fixtures");
        File fixtureDirectory = new File(fixtureRoot, preferencesName);
        if (!fixtureDirectory.isDirectory() && !fixtureDirectory.mkdirs()) {
            throw new IOException("Unable to create UUID-owned screenshot directory");
        }
        File output = new File(fixtureDirectory, fileName);
        try (FileOutputStream stream = new FileOutputStream(output, false)) {
            stream.write(png);
            stream.getFD().sync();
        }
    }

    @Override protected void onDestroy() {
        if (worker != null) worker.shutdownNow();
        super.onDestroy();
    }
}

public final class AgentSettingsLayoutHarnessActivity extends AgentSettingsLayoutHarnessBaseActivity {
    public static final class TwoX extends AgentSettingsLayoutHarnessBaseActivity {
        @Override protected float localFontScaleOverride() { return 2.0f; }
    }
}
