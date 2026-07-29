package com.desmond.gptwake;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

public class MainActivity extends AppCompatActivity {

    /** Ordered setup steps. The banner always offers the first one that is not satisfied. */
    private enum Step {MIC, NOTIFICATIONS, OVERLAY, ASSISTANT, DONE}

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final WakeWordTokenizer tokenizer = new WakeWordTokenizer();

    private View statusDot;
    private TextView statusText, statusDetail, currentWord, currentTokens, preview,
            thresholdValue, logView, setupBody;
    private MaterialButton toggleBtn, applyWordBtn, resetWordBtn, setupBtn;
    private MaterialCardView setupCard;
    private TextInputLayout wordInputLayout;
    private TextInputEditText wordInput;
    private Slider thresholdSlider;
    private MaterialSwitch evalSwitch;
    private LinearLayout checklist;
    private LinearProgressIndicator levelBar;

    private WakeWordTokenizer.Result pending;
    private boolean autoPrompted;

    private ActivityResultLauncher<String> requestPermission;
    private ActivityResultLauncher<Intent> openSettings;

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
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        applyInsets();
        bindViews();

        requestPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (!granted) {
                        snack(getString(R.string.setup_incomplete_body));
                    }
                    render();
                });
        openSettings = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), r -> render());

        AudioStateMonitor.install(this);
        loadWakeWordUi();

        toggleBtn.setOnClickListener(v -> onToggle());
        setupBtn.setOnClickListener(v -> runStep(nextStep()));
        applyWordBtn.setOnClickListener(v -> applyWakeWord());
        resetWordBtn.setOnClickListener(v -> resetWakeWord());

        wordInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) {
            }

            public void onTextChanged(CharSequence c, int a, int b, int d) {
            }

            public void afterTextChanged(Editable e) {
                onPhraseTyped(e.toString());
            }
        });

        thresholdSlider.setValue(KwsEngine.keywordsThreshold);
        thresholdValue.setText(String.format("%.2f", KwsEngine.keywordsThreshold));
        thresholdSlider.addOnChangeListener((sl, value, fromUser) ->
                thresholdValue.setText(String.format("%.2f", value)));
        thresholdSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            public void onStartTrackingTouch(Slider sl) {
            }

            public void onStopTrackingTouch(Slider sl) {
                KwsEngine.keywordsThreshold = sl.getValue();
                restartListening();
            }
        });

        evalSwitch.setChecked(Cfg.evalMode);
        evalSwitch.setOnCheckedChangeListener((b, checked) -> Cfg.evalMode = checked);

        new Thread(() -> {
            try {
                tokenizer.load(getAssets());
                ui.post(() -> onPhraseTyped(text(wordInput)));
            } catch (Throwable t) {
                L.e("TOKENIZER_LOAD_FAIL", t);
            }
        }, "tokenizer-load").start();
    }

    private void bindViews() {
        statusDot = findViewById(R.id.statusDot);
        statusText = findViewById(R.id.statusText);
        statusDetail = findViewById(R.id.statusDetail);
        currentWord = findViewById(R.id.currentWord);
        currentTokens = findViewById(R.id.currentTokens);
        preview = findViewById(R.id.preview);
        thresholdValue = findViewById(R.id.thresholdValue);
        logView = findViewById(R.id.logView);
        setupBody = findViewById(R.id.setupBody);
        toggleBtn = findViewById(R.id.toggleBtn);
        applyWordBtn = findViewById(R.id.applyWordBtn);
        resetWordBtn = findViewById(R.id.resetWordBtn);
        setupBtn = findViewById(R.id.setupBtn);
        setupCard = findViewById(R.id.setupCard);
        wordInputLayout = findViewById(R.id.wordInputLayout);
        wordInput = findViewById(R.id.wordInput);
        thresholdSlider = findViewById(R.id.thresholdSlider);
        evalSwitch = findViewById(R.id.evalSwitch);
        checklist = findViewById(R.id.checklist);
        levelBar = findViewById(R.id.levelBar);
    }

    private void applyInsets() {
        View scroll = findViewById(R.id.scroll);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, v.getPaddingTop(), bars.right, bars.bottom);
            return insets;
        });
    }

    private static String text(TextInputEditText e) {
        return e.getText() == null ? "" : e.getText().toString();
    }

    // ---------------- setup / permissions ----------------

    private boolean micGranted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean notifGranted() {
        if (Build.VERSION.SDK_INT < 33) return true;
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

    private Step nextStep() {
        if (!micGranted()) return Step.MIC;
        if (!notifGranted()) return Step.NOTIFICATIONS;
        if (!overlayGranted()) return Step.OVERLAY;
        if (!chatGptIsAssistant()) return Step.ASSISTANT;
        return Step.DONE;
    }

    private void runStep(Step step) {
        switch (step) {
            case MIC:
                askOrOpenSettings(Manifest.permission.RECORD_AUDIO);
                break;
            case NOTIFICATIONS:
                if (Build.VERSION.SDK_INT >= 33) {
                    askOrOpenSettings(Manifest.permission.POST_NOTIFICATIONS);
                }
                break;
            case OVERLAY:
                launch(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                break;
            case ASSISTANT:
                launch(new Intent("android.settings.VOICE_INPUT_SETTINGS"));
                break;
            default:
                break;
        }
    }

    /**
     * Requests a runtime permission, or sends the user to app settings when the system will no
     * longer show the dialog (permanently denied).
     */
    private void askOrOpenSettings(String permission) {
        boolean canAsk = shouldShowRequestPermissionRationale(permission)
                || checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
                && !hasBeenAsked(permission);
        if (canAsk) {
            markAsked(permission);
            requestPermission.launch(permission);
        } else {
            launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    private boolean hasBeenAsked(String p) {
        return getPreferences(MODE_PRIVATE).getBoolean("asked_" + p, false);
    }

    private void markAsked(String p) {
        getPreferences(MODE_PRIVATE).edit().putBoolean("asked_" + p, true).apply();
    }

    private void launch(Intent i) {
        try {
            openSettings.launch(i);
        } catch (Throwable t) {
            L.e("LAUNCH_SETTINGS_FAIL", t);
            snack("这台设备上找不到对应的设置页");
        }
    }

    // ---------------- listening ----------------

    private void onToggle() {
        if (WakeService.isForegroundNow()) {
            stopService(new Intent(this, WakeService.class));
            return;
        }
        Step step = nextStep();
        if (step == Step.MIC || step == Step.OVERLAY) {
            runStep(step);
            return;
        }
        startActivity(new Intent(this, ShimActivity.class)
                .putExtra(ShimActivity.EXTRA_ACTION, "fgs"));
    }

    private void restartListening() {
        if (!WakeService.isForegroundNow()) return;
        WakeController c = WakeService.controller();
        if (c != null) c.restartStream();
    }

    // ---------------- wake word ----------------

    private void loadWakeWordUi() {
        currentWord.setText(WakeWordStore.phrase(this));
        currentTokens.setText(WakeWordStore.keywordLine(this).split(" @")[0]);
    }

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
        wordInputLayout.setError(r.error);   // null clears it; non-null is the short-phrase warning
        // For English the readable form and the model tokens are the same CMU string.
        preview.setText(r.readable.equals(r.tokens) ? r.tokens : r.readable + "\n" + r.tokens);
        preview.setTextColor(MaterialColors.getColor(preview,
                r.error != null ? androidx.appcompat.R.attr.colorError
                        : androidx.appcompat.R.attr.colorPrimary));
        applyWordBtn.setEnabled(true);
    }

    private void applyWakeWord() {
        if (pending == null || !pending.ok) return;
        String phrase = text(wordInput).trim();
        WakeWordStore.save(this, phrase, pending.keywordLine);
        KwsEngine.customKeywordLine = pending.keywordLine;
        loadWakeWordUi();
        wordInput.setText("");
        restartListening();
        snack("唤醒词已改为「" + phrase + "」");
    }

    private void resetWakeWord() {
        WakeWordStore.reset(this);
        KwsEngine.customKeywordLine = WakeWordStore.keywordLine(this);
        wordInput.setText("");
        loadWakeWordUi();
        restartListening();
        snack("已恢复默认唤醒词");
    }

    // ---------------- render ----------------

    private void addCheck(String label, String why, boolean ok, Step step) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = (int) (8 * getResources().getDisplayMetrics().density);
        row.setPadding(0, pad, 0, pad);

        TextView mark = new TextView(this);
        mark.setText(ok ? "✓" : "•");
        mark.setTextColor(MaterialColors.getColor(this,
                ok ? androidx.appcompat.R.attr.colorPrimary
                        : androidx.appcompat.R.attr.colorError, 0));
        row.addView(mark);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart((int) (12 * getResources().getDisplayMetrics().density));
        texts.setLayoutParams(lp);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        texts.addView(title);

        TextView sub = new TextView(this);
        sub.setText(why);
        sub.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        sub.setTextColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0));
        texts.addView(sub);
        row.addView(texts);

        if (!ok) {
            MaterialButton b = new MaterialButton(this, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setText(step == Step.MIC || step == Step.NOTIFICATIONS
                    ? R.string.action_grant : R.string.action_open_settings);
            b.setOnClickListener(v -> runStep(step));
            row.addView(b);
        }
        checklist.addView(row);
    }

    private void render() {
        WakeController c = WakeService.controller();
        boolean fgs = WakeService.isForegroundNow();

        int colorAttr = com.google.android.material.R.attr.colorOutline;
        String label = getString(R.string.action_start);

        if (c != null) {
            switch (c.state()) {
                case KWS_LISTENING:
                    label = Cfg.evalMode ? "监听中（测试模式）" : "监听中";
                    colorAttr = androidx.appcompat.R.attr.colorPrimary;
                    break;
                case STARTING:
                case KWS_MODEL_LOADING:
                    label = "正在加载模型";
                    colorAttr = com.google.android.material.R.attr.colorTertiary;
                    break;
                case MIC_HANDOFF:
                case CHATGPT_LAUNCHING:
                    label = "正在唤起 ChatGPT";
                    colorAttr = com.google.android.material.R.attr.colorTertiary;
                    break;
                case VOICE_ACTIVE:
                    label = "ChatGPT 语音进行中";
                    colorAttr = androidx.appcompat.R.attr.colorPrimary;
                    break;
                case KWS_REACQUIRING:
                    label = "正在恢复监听";
                    colorAttr = com.google.android.material.R.attr.colorTertiary;
                    break;
                case EXTERNAL_COMMUNICATION:
                    label = "检测到通话，已暂停";
                    colorAttr = com.google.android.material.R.attr.colorTertiary;
                    break;
                case ERROR:
                    label = "出错了";
                    colorAttr = androidx.appcompat.R.attr.colorError;
                    break;
                default:
                    label = "未启动";
            }
        } else if (fgs) {
            label = "启动中";
            colorAttr = com.google.android.material.R.attr.colorTertiary;
        } else {
            label = "未启动";
        }

        statusText.setText(label);
        statusDot.getBackground().setColorFilter(
                MaterialColors.getColor(this, colorAttr, 0), PorterDuff.Mode.SRC_IN);
        toggleBtn.setText(fgs ? R.string.action_stop : R.string.action_start);

        statusDetail.setText((c == null ? "STOPPED" : c.state().name())
                + "  ·  麦克风 " + (AudioProbe.isRunning() ? "开" : "关")
                + (c == null ? "" : "  ·  " + c.counters()));
        levelBar.setProgress(Math.min(100, (int) (AudioProbe.lastRms() / 20)));

        Step step = nextStep();
        setupCard.setVisibility(step == Step.DONE ? View.GONE : View.VISIBLE);

        checklist.removeAllViews();
        addCheck(getString(R.string.perm_mic), getString(R.string.perm_mic_why),
                micGranted(), Step.MIC);
        addCheck(getString(R.string.perm_notif), getString(R.string.perm_notif_why),
                notifGranted(), Step.NOTIFICATIONS);
        addCheck(getString(R.string.perm_overlay), getString(R.string.perm_overlay_why),
                overlayGranted(), Step.OVERLAY);
        addCheck(getString(R.string.perm_assistant), getString(R.string.perm_assistant_why),
                chatGptIsAssistant(), Step.ASSISTANT);

        StringBuilder sb = new StringBuilder();
        String[] lines = L.dump().split("\n");
        int shown = 0;
        for (int i = lines.length - 1; i >= 0 && shown < 10; i--) {
            String l = lines[i];
            if (l.isEmpty() || l.contains("KWS_STATS") || l.contains("CONFIGS")
                    || l.contains("POWER")) continue;
            sb.insert(0, l + "\n");
            shown++;
        }
        logView.setText(sb.toString().trim());
    }

    private void snack(String msg) {
        Snackbar.make(findViewById(R.id.root), msg, Snackbar.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        KwsEngine.customKeywordLine = WakeWordStore.keywordLine(this);
        // Prompt for what is still missing the first time the screen is shown on a new device.
        if (!autoPrompted) {
            autoPrompted = true;
            Step step = nextStep();
            if (step == Step.MIC || step == Step.NOTIFICATIONS) runStep(step);
        }
        ui.post(refresh);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(refresh);
        super.onPause();
    }
}
