package dev.desmond.gptwakeprobe;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

public class MainActivity extends AppCompatActivity {

    private static final int GREEN = 0xFF2E7D32;
    private static final int AMBER = 0xFFF9A825;
    private static final int GREY = 0xFF9E9E9E;
    private static final int RED = 0xFFC62828;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final WakeWordTokenizer tokenizer = new WakeWordTokenizer();

    private View statusDot;
    private TextView statusText, statusDetail, currentWord, currentTokens, preview,
            thresholdValue, logView;
    private MaterialButton toggleBtn, applyWordBtn, resetWordBtn;
    private TextInputLayout wordInputLayout;
    private TextInputEditText wordInput;
    private Slider thresholdSlider;
    private MaterialSwitch evalSwitch;
    private LinearLayout checklist;

    private WakeWordTokenizer.Result pending;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            ui.postDelayed(this, 700);
        }
    };

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setTheme(R.style.AppTheme);
        setContentView(R.layout.activity_main);

        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);
        statusDetail = findViewById(R.id.statusDetail);
        currentWord = findViewById(R.id.currentWord);
        currentTokens = findViewById(R.id.currentTokens);
        preview = findViewById(R.id.preview);
        thresholdValue = findViewById(R.id.thresholdValue);
        logView = findViewById(R.id.logView);
        toggleBtn = findViewById(R.id.toggleBtn);
        applyWordBtn = findViewById(R.id.applyWordBtn);
        resetWordBtn = findViewById(R.id.resetWordBtn);
        wordInputLayout = findViewById(R.id.wordInputLayout);
        wordInput = findViewById(R.id.wordInput);
        thresholdSlider = findViewById(R.id.thresholdSlider);
        evalSwitch = findViewById(R.id.evalSwitch);
        checklist = findViewById(R.id.checklist);

        capContentWidth();
        AudioStateMonitor.install(this);

        currentWord.setText(WakeWordStore.phrase(this));
        currentTokens.setText(WakeWordStore.keywordLine(this).split(" @")[0]);
        thresholdSlider.setValue(KwsEngine.keywordsThreshold);
        thresholdValue.setText(String.format("%.2f", KwsEngine.keywordsThreshold));
        evalSwitch.setChecked(Cfg.evalMode);

        toggleBtn.setOnClickListener(v -> {
            if (MicProbeService.isForegroundNow()) {
                stopService(new Intent(this, MicProbeService.class));
                snack("已停止监听");
            } else if (!ensureReady()) {
                // ensureReady already told the user what is missing
            } else {
                startActivity(new Intent(this, ShimActivity.class)
                        .putExtra(ShimActivity.EXTRA_ACTION, "fgs"));
                snack("正在启动监听…");
            }
        });

        wordInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            public void onTextChanged(CharSequence c, int a, int b, int d) {
            }

            public void afterTextChanged(Editable e) {
                onPhraseTyped(e.toString());
            }
        });

        applyWordBtn.setOnClickListener(v -> applyWakeWord());
        resetWordBtn.setOnClickListener(v -> {
            WakeWordStore.reset(this);
            KwsEngine.customKeywordLine = null;
            wordInput.setText("");
            currentWord.setText(WakeWordStore.phrase(this));
            currentTokens.setText(WakeWordStore.keywordLine(this).split(" @")[0]);
            restartListening();
            snack("已恢复默认唤醒词");
        });

        thresholdSlider.addOnChangeListener((sl, value, fromUser) ->
                thresholdValue.setText(String.format("%.2f", value)));
        thresholdSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            public void onStartTrackingTouch(Slider sl) {
            }

            public void onStopTrackingTouch(Slider sl) {
                KwsEngine.keywordsThreshold = sl.getValue();
                restartListening();
                snack("灵敏度已设为 " + String.format("%.2f", sl.getValue()));
            }
        });

        evalSwitch.setOnCheckedChangeListener((b, checked) -> {
            Cfg.evalMode = checked;
            snack(checked ? "测试模式：命中只震动提示" : "正常模式：命中会打开 ChatGPT 语音");
        });

        new Thread(() -> {
            try {
                tokenizer.load(getAssets());
                ui.post(() -> onPhraseTyped(wordInput.getText() == null
                        ? "" : wordInput.getText().toString()));
            } catch (Throwable t) {
                L.e("TOKENIZER_LOAD_FAIL", t);
            }
        }, "tokenizer-load").start();

        requestPermissions(new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    /** Tablets are very wide; a full-bleed column of cards reads badly, so cap and centre it. */
    private void capContentWidth() {
        View content = findViewById(R.id.content);
        float d = getResources().getDisplayMetrics().density;
        int screen = getResources().getDisplayMetrics().widthPixels;
        int max = (int) (620 * d);
        int side = (int) (20 * d);
        if (screen > max + 2 * side) side = (screen - max) / 2;
        content.setPadding(side, content.getPaddingTop(), side, content.getPaddingBottom());
    }

    // ---------------- wake word ----------------

    private void onPhraseTyped(String phrase) {
        if (phrase == null || phrase.trim().isEmpty()) {
            preview.setText("");
            wordInputLayout.setError(null);
            applyWordBtn.setEnabled(false);
            pending = null;
            return;
        }
        if (!tokenizer.isLoaded()) {
            preview.setText("词典加载中…");
            return;
        }
        WakeWordTokenizer.Result r = tokenizer.convert(phrase);
        pending = r;
        if (!r.ok) {
            wordInputLayout.setError(r.error);
            preview.setText("");
            applyWordBtn.setEnabled(false);
            return;
        }
        wordInputLayout.setError(null);
        preview.setText(r.readable + "\n" + r.tokens);
        preview.setTextColor(r.error != null ? AMBER : GREEN);
        if (r.error != null) wordInputLayout.setError(r.error);
        applyWordBtn.setEnabled(true);
    }

    private void applyWakeWord() {
        if (pending == null || !pending.ok) return;
        String phrase = wordInput.getText() == null ? "" : wordInput.getText().toString().trim();
        WakeWordStore.save(this, phrase, pending.keywordLine);
        KwsEngine.customKeywordLine = pending.keywordLine;
        currentWord.setText(phrase);
        currentTokens.setText(pending.tokens);
        wordInput.setText("");
        restartListening();
        snack("唤醒词已改为「" + phrase + "」");
    }

    private void restartListening() {
        if (!MicProbeService.isForegroundNow()) return;
        stopService(new Intent(this, MicProbeService.class));
        ui.postDelayed(() -> startActivity(new Intent(this, ShimActivity.class)
                .putExtra(ShimActivity.EXTRA_ACTION, "fgs")), 900);
    }

    // ---------------- checklist ----------------

    private boolean micGranted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean notifGranted() {
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean overlayGranted() {
        return Settings.canDrawOverlays(this);
    }

    private boolean chatGptIsAssistant() {
        String s = Settings.Secure.getString(getContentResolver(), "voice_interaction_service");
        return s != null && s.startsWith("com.openai.chatgpt");
    }

    private boolean ensureReady() {
        if (!micGranted()) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            snack("需要麦克风权限");
            return false;
        }
        if (!overlayGranted()) {
            snack("需要「显示在其他应用上层」权限，锁屏唤醒依赖它");
            openOverlaySettings();
            return false;
        }
        return true;
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable t) {
            L.e("OVERLAY_SETTINGS_FAIL", t);
        }
    }

    private void addCheck(String label, boolean ok, String hint, Runnable fix) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 10, 0, 10);

        TextView mark = new TextView(this);
        mark.setText(ok ? "●" : "○");
        mark.setTextColor(ok ? GREEN : (fix == null ? GREY : RED));
        mark.setTextSize(14f);
        row.addView(mark);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(14f);
        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(14);
        tv.setLayoutParams(lp);
        if (!ok && hint != null) {
            tv.setText(label + "\n" + hint);
            tv.setTextSize(13f);
        }
        row.addView(tv);

        if (!ok && fix != null) {
            MaterialButton b = new MaterialButton(this, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setText("设置");
            b.setOnClickListener(v -> fix.run());
            row.addView(b);
        }
        checklist.addView(row);
    }

    // ---------------- render ----------------

    private void render() {
        WakeController c = MicProbeService.controller();
        boolean fgs = MicProbeService.isForegroundNow();
        boolean recording = AudioProbe.isRunning();

        String state = c == null ? (fgs ? "启动中" : "未启动") : c.state().name();
        int color = GREY;
        String label = "未启动";

        if (c != null) {
            switch (c.state()) {
                case KWS_LISTENING:
                    label = Cfg.evalMode ? "监听中（测试模式）" : "监听中";
                    color = GREEN;
                    break;
                case KWS_MODEL_LOADING:
                case STARTING:
                    label = "正在加载模型";
                    color = AMBER;
                    break;
                case MIC_HANDOFF:
                case CHATGPT_LAUNCHING:
                    label = "正在唤起 ChatGPT";
                    color = AMBER;
                    break;
                case VOICE_ACTIVE:
                    label = "ChatGPT 语音进行中";
                    color = GREEN;
                    break;
                case KWS_REACQUIRING:
                    label = "正在恢复监听";
                    color = AMBER;
                    break;
                case EXTERNAL_COMMUNICATION:
                    label = "检测到通话，已暂停";
                    color = AMBER;
                    break;
                case ERROR:
                    label = "出错了";
                    color = RED;
                    break;
                default:
                    label = "未启动";
                    color = GREY;
            }
        } else if (fgs) {
            label = "启动中";
            color = AMBER;
        }

        statusText.setText(label);
        statusDot.getBackground().setColorFilter(color, PorterDuff.Mode.SRC_IN);
        toggleBtn.setText(fgs ? "停止" : "启动");

        statusDetail.setText("状态 " + state
                + "  ·  麦克风 " + (recording ? "开" : "关")
                + "  ·  " + (c == null ? "" : c.counters()));

        LinearProgressIndicator bar = findViewById(R.id.levelBar);
        bar.setProgress(Math.min(100, (int) (AudioProbe.lastRms() / 20)));

        checklist.removeAllViews();
        addCheck("麦克风权限", micGranted(), "唤醒词识别需要它",
                () -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1));
        addCheck("通知权限", notifGranted(), "常驻通知需要它",
                () -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1));
        addCheck("显示在其他应用上层", overlayGranted(),
                "锁屏和开机自启依赖它", this::openOverlaySettings);
        addCheck("ChatGPT 是系统默认助理", chatGptIsAssistant(),
                "否则锁屏时 ChatGPT 拿不到麦克风",
                () -> {
                    try {
                        startActivity(new Intent("android.settings.VOICE_INPUT_SETTINGS"));
                    } catch (Throwable ignored) {
                    }
                });

        String[] lines = L.dump().split("\n");
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (int i = lines.length - 1; i >= 0 && shown < 12; i--) {
            String l = lines[i];
            if (l.isEmpty()) continue;
            if (l.contains("KWS_STATS") || l.contains("CONFIGS") || l.contains("POWER")) continue;
            sb.insert(0, l + "\n");
            shown++;
        }
        logView.setText(sb.toString().trim());
    }

    private void snack(String msg) {
        Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        KwsEngine.customKeywordLine = WakeWordStore.keywordLine(this);
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }
}
