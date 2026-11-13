package com.opencode.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.content.res.Configuration;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.HttpAuthHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.opencode.android.bootstrap.GuestCommand;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String PREFS = "opencode-android";
    private static final String SCREEN_HOME = "home";
    private static final String SCREEN_CONSOLE = "console";
    private static final String SCREEN_SETTINGS = "settings";
    private static final String SCREEN_SETUP = "setup";
    private static final String SCREEN_WEB = "web";
    private static final String PREF_ONBOARDING_COMPLETED = "onboarding_completed";
    private static final String PREF_SHOW_ON_STARTUP = "show_onboarding_at_startup";
    private static final int ONBOARDING_STEPS = 3;

    private SharedPreferences preferences;
    private Prefs runtimePrefs;
    private String screen = SCREEN_HOME;
    private String theme;
    private String font;
    private int terminalFontSize;
    private boolean focusMode;
    private boolean controlArmed;
    private boolean altArmed;
    private String commandDraft = "";
    private int settingsScrollY;
    private Palette palette;
    private LinearLayout root;
    private ScrollView mainScroll;
    private String mainScrollScreen;
    private EditText commandInput;
    private TextView commandSendButton;
    private TerminalView terminal;
    private WebView webView;
    private boolean terminalInitialized;
    private volatile boolean busy;
    private boolean webLoading;
    private String runtimeStage;
    private Dialog onboardingDialog;
    private int onboardingStep;
    private boolean onboardingShowAtStart;

    private final Session.Listener sessionListener = new Session.Listener() {
        @Override
        public void onOutput(String chunk) {
            runOnUiThread(() -> {
                if (terminal != null) terminal.append(chunk);
            });
        }

        @Override
        public void onExit(int code) {
            runOnUiThread(() -> {
                if (terminal != null && code != 0) {
                    terminal.appendLine("\n[session exited with code " + code + "]");
                }
                if (!busy) {
                    if (GuestCommand.MODE_WEB.equals(Session.get().lastMode())) {
                        webLoading = false;
                        if (webView != null) showWebMessage("web_stopped");
                    }
                    runtimeStage = null;
                    refreshStatus();
                    RunnerService.stop(MainActivity.this);
                }
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        runtimePrefs = new Prefs(this);
        theme = preferences.getString("theme", "system");
        font = preferences.getString("font", "jetbrains");
        terminalFontSize = preferences.getInt("terminal_font_size", 14);
        Session.get().addListener(sessionListener);
        render();
        if (!preferences.getBoolean(PREF_ONBOARDING_COMPLETED, false)
                || preferences.getBoolean(PREF_SHOW_ON_STARTUP, false)) {
            root.post(this::showOnboarding);
        }
    }

    @Override
    protected void onDestroy() {
        Session.get().removeListener(sessionListener);
        if (onboardingDialog != null) {
            onboardingDialog.dismiss();
            onboardingDialog = null;
        }
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if ("system".equals(theme)) render();
    }

    @Override
    public void onBackPressed() {
        if (focusMode) {
            focusMode = false;
            render();
            return;
        }
        if (SCREEN_WEB.equals(screen)) {
            navigate(SCREEN_HOME);
            return;
        }
        if (!SCREEN_HOME.equals(screen)) {
            navigate(SCREEN_HOME);
            return;
        }
        super.onBackPressed();
    }

    private void render() {
        if (commandInput != null) commandDraft = commandInput.getText().toString();
        if (SCREEN_SETTINGS.equals(screen) && SCREEN_SETTINGS.equals(mainScrollScreen)
                && mainScroll != null) {
            settingsScrollY = mainScroll.getScrollY();
        }
        palette = Palette.forTheme(theme, isSystemDark());
        updateSystemBars();

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.background);

        if (!(SCREEN_CONSOLE.equals(screen) && focusMode)) {
            root.addView(buildHeader());
        }

        if (SCREEN_CONSOLE.equals(screen)) {
            buildConsole();
        } else if (SCREEN_WEB.equals(screen)) {
            buildWeb();
        } else {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            mainScroll = scroll;
            mainScrollScreen = screen;
            if (SCREEN_SETTINGS.equals(screen)) {
                scroll.setOnScrollChangeListener((View view, int x, int y, int oldX, int oldY) ->
                        settingsScrollY = y);
            }
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(20), dp(14), dp(20), dp(24));
            if (SCREEN_HOME.equals(screen)) buildHome(content);
            else if (SCREEN_SETTINGS.equals(screen)) buildSettings(content);
            else buildSetup(content);
            scroll.addView(content);
            root.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            if (SCREEN_SETTINGS.equals(screen) && settingsScrollY > 0) {
                scroll.post(() -> scroll.scrollTo(0, settingsScrollY));
            }
        }

        if (SCREEN_SETTINGS.equals(screen)) {
            root.addView(buildNavigation());
        }
        setContentView(root);
    }

    private void updateSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(palette.background);
        window.setNavigationBarColor(palette.background);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        int flags = window.getDecorView().getSystemUiVisibility();
        if (palette.lightStatusIcons) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private boolean isSystemDark() {
        int mode = getResources().getConfiguration().uiMode & 0x30;
        return mode == 0x20;
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(10), dp(14), dp(10));
        header.setBackgroundColor(palette.surface);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        TextView title = label("OpenCode", 18, palette.text, true);
        TextView subtitle = label(text("brand_subtitle"), 10, palette.muted, false);
        subtitle.setLetterSpacing(0.06f);
        brand.addView(title);
        brand.addView(subtitle);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));

        TextView status = label("•  " + runtimeStatusText(), 11,
            Session.get().running() ? palette.accent : palette.warning, true);
        status.setPadding(dp(9), dp(7), dp(9), dp(7));
        status.setBackground(shape(Session.get().running() ? palette.accentSoft
            : palette.warningSoft, Session.get().running() ? palette.accentSoft
            : palette.warningSoft, 20));
        header.addView(status);

        TextView menu = label("•••", 18, palette.text, true);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription(text("menu_accessibility"));
        menu.setMinimumWidth(dp(46));
        menu.setMinimumHeight(dp(44));
        menu.setOnClickListener(v -> showMenu(v));
        header.addView(menu);
        return header;
    }

    private void showMenu(View anchor) {
        android.widget.PopupMenu menu = new android.widget.PopupMenu(this, anchor);
        menu.getMenu().add(text("menu_home")).setOnMenuItemClickListener(item -> {
            navigate(SCREEN_HOME);
            return true;
        });
        menu.getMenu().add(text("menu_console")).setOnMenuItemClickListener(item -> {
            navigate(SCREEN_CONSOLE);
            return true;
        });
        menu.getMenu().add(text("menu_web")).setOnMenuItemClickListener(item -> {
            launchMode(GuestCommand.MODE_WEB, SCREEN_WEB);
            return true;
        });
        menu.getMenu().add(text("menu_settings")).setOnMenuItemClickListener(item -> {
            navigate(SCREEN_SETTINGS);
            return true;
        });
        menu.getMenu().add(text("menu_setup")).setOnMenuItemClickListener(item -> {
            navigate(SCREEN_SETUP);
            return true;
        });
        menu.getMenu().add(text("menu_guide")).setOnMenuItemClickListener(item -> {
            showOnboarding();
            return true;
        });
        menu.show();
    }

    private void showOnboarding() {
        if (isFinishing() || (onboardingDialog != null && onboardingDialog.isShowing())) return;
        onboardingStep = 0;
        onboardingShowAtStart = preferences.getBoolean(PREF_SHOW_ON_STARTUP, false);
        showOnboardingStep();
    }

    private void showOnboardingStep() {
        Dialog dialog = new Dialog(this);
        onboardingDialog = dialog;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(20), dp(22), dp(18));
        card.setBackground(shape(palette.surface, palette.border, 24));

        TextView progress = label(String.format(Locale.getDefault(), "%d / %d",
                onboardingStep + 1, ONBOARDING_STEPS), 11, palette.accent, true);
        progress.setLetterSpacing(0.08f);
        card.addView(progress);
        addGap(card, 10);
        card.addView(label(text("onboarding_step" + (onboardingStep + 1) + "_title"),
                21, palette.text, true));
        addGap(card, 8);
        card.addView(label(text("onboarding_step" + (onboardingStep + 1) + "_body"),
                14, palette.muted, false));
        addGap(card, 15);

        CheckBox repeat = new CheckBox(this);
        repeat.setText(text("onboarding_show_at_start"));
        repeat.setTextColor(palette.text);
        repeat.setButtonTintList(ColorStateList.valueOf(palette.accent));
        repeat.setChecked(onboardingShowAtStart);
        repeat.setOnCheckedChangeListener((button, checked) -> onboardingShowAtStart = checked);
        card.addView(repeat);
        addGap(card, 12);

        LinearLayout actions = horizontal();
        TextView back = secondaryButton(onboardingStep == 0
                ? text("action_skip") : text("action_back"), () -> {
            onboardingShowAtStart = repeat.isChecked();
            if (onboardingStep == 0) {
                completeOnboarding();
            } else {
                onboardingStep--;
                dialog.dismiss();
                showOnboardingStep();
            }
        });
        actions.addView(back, new LinearLayout.LayoutParams(0, dp(46), 1f));
        View gap = new View(this);
        actions.addView(gap, new LinearLayout.LayoutParams(dp(9), 1));
        TextView next = primaryButton(onboardingStep == ONBOARDING_STEPS - 1
                ? text("action_done") : text("action_next"), () -> {
            onboardingShowAtStart = repeat.isChecked();
            if (onboardingStep == ONBOARDING_STEPS - 1) {
                completeOnboarding();
            } else {
                onboardingStep++;
                dialog.dismiss();
                showOnboardingStep();
            }
        });
        actions.addView(next, new LinearLayout.LayoutParams(0, dp(46), 1f));
        card.addView(actions);

        dialog.setContentView(card);
        dialog.setCancelable(true);
        dialog.setOnCancelListener(ignored -> {
            onboardingShowAtStart = repeat.isChecked();
            completeOnboarding();
        });
        dialog.setOnDismissListener(ignored -> {
            if (onboardingDialog == dialog) onboardingDialog = null;
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.9f);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            params.dimAmount = 0.55f;
            window.setAttributes(params);
        }
    }

    private void completeOnboarding() {
        preferences.edit()
                .putBoolean(PREF_ONBOARDING_COMPLETED, true)
                .putBoolean(PREF_SHOW_ON_STARTUP, onboardingShowAtStart)
                .apply();
        Dialog dialog = onboardingDialog;
        onboardingDialog = null;
        if (dialog != null) dialog.dismiss();
    }

    private View buildNavigation() {
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setBackgroundColor(palette.surface);
        nav.setPadding(dp(8), dp(5), dp(8), dp(6));
        nav.addView(navItem("⌂", text("nav_home"), SCREEN_HOME));
        nav.addView(navItem("›_", text("nav_console"), SCREEN_CONSOLE));
        nav.addView(navItem("⚙", text("nav_settings"), SCREEN_SETTINGS));
        return nav;
    }

    private View navItem(String icon, String title, String destination) {
        boolean selected = destination.equals(screen);
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setMinimumHeight(dp(52));
        item.setBackground(shape(selected ? palette.accentSoft : Color.TRANSPARENT,
                Color.TRANSPARENT, 18));
        TextView iconView = label(icon, 17, selected ? palette.accent : palette.muted, true);
        TextView textView = label(title, 10, selected ? palette.accent : palette.muted, selected);
        item.addView(iconView);
        item.addView(textView);
        item.setContentDescription(title);
        item.setOnClickListener(v -> navigate(destination));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(dp(4), 0, dp(4), 0);
        item.setLayoutParams(params);
        return item;
    }

    private void buildHome(LinearLayout content) {
        Env env = Env.get(this);
        boolean rootfsReady = env.rootfsInstalled();
        boolean openCodeReady = env.openCodeInstalled();

        LinearLayout status = card();
        status.addView(label(runtimeStatusText(), 19, palette.text, true));
        addGap(status, 5);
        status.addView(label(text(rootfsReady
                ? (openCodeReady ? "home_ready" : "home_linux_ready")
                : "home_not_ready"), 13, palette.muted, false));
        content.addView(status);
        addGap(content, 12);

        if (!rootfsReady || !openCodeReady) {
            content.addView(primaryButton(text("action_prepare"), this::prepareEnvironment));
        } else {
            content.addView(primaryButton(text("action_opencode"),
                    () -> launchMode(GuestCommand.MODE_BOOT, SCREEN_CONSOLE)));
        }
        addGap(content, 8);
        content.addView(secondaryButton(text("action_console"), () -> navigate(SCREEN_CONSOLE)));
    }

    private void buildConsole() {
        if (!focusMode) {
            LinearLayout controls = horizontal();
            controls.setPadding(dp(12), dp(8), dp(12), dp(3));
            TextView title = label(text("console_title"), 14, palette.text, true);
            controls.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            boolean rootfsReady = Env.get(this).rootfsInstalled();
            controls.addView(smallButton(rootfsReady ? text("action_shell") : text("action_prepare"),
                    () -> {
                        if (rootfsReady) launchMode(GuestCommand.MODE_SHELL, SCREEN_CONSOLE);
                        else prepareEnvironment();
                    }));
            controls.addView(smallButton(text("action_web"),
                    () -> launchMode(GuestCommand.MODE_WEB, SCREEN_WEB)));
            controls.addView(smallButton(text("action_stop"), this::stopRuntime));
            root.addView(controls);
        }

        if (terminal == null) terminal = new TerminalView(this);
        ViewGroup oldParent = (ViewGroup) terminal.getParent();
        if (oldParent != null) oldParent.removeView(terminal);
        applySelectedFont();
        terminal.setFontSp(terminalFontSize);
        if (!terminalInitialized) {
            terminal.clear();
            String backlog = Session.get().backlog();
            if (!backlog.isEmpty()) terminal.append(backlog);
            terminalInitialized = true;
            if (backlog.isEmpty() && !Session.get().running()) {
                terminal.appendLine(text("terminal_hint"));
            }
        }
        LinearLayout.LayoutParams terminalParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        terminalParams.setMargins(dp(12), dp(12), dp(12), dp(10));
        terminal.setBackground(shape(palette.terminal, palette.border, 20));
        root.addView(terminal, terminalParams);

        if (focusMode) root.addView(buildFocusControls());
        else root.addView(buildKeyPanel());
        root.addView(buildCommandEntry());
    }

    private View buildFocusControls() {
        LinearLayout strip = horizontal();
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(8), dp(5), dp(8), dp(5));
        strip.setBackgroundColor(palette.surface);
        TextView restore = key("≡", text("focus_exit"), () -> {
            focusMode = false;
            render();
        });
        strip.addView(restore, new LinearLayout.LayoutParams(dp(48), dp(44)));
        TextView keyboard = key("⇤", text("focus_keyboard"), this::toggleSoftKeyboard);
        LinearLayout.LayoutParams keyboardParams =
                new LinearLayout.LayoutParams(dp(48), dp(44));
        keyboardParams.leftMargin = dp(8);
        strip.addView(keyboard, keyboardParams);
        TextView info = label(text("focus_hint"), 11, palette.muted, false);
        info.setPadding(dp(10), 0, 0, 0);
        strip.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        return strip;
    }

    private void toggleSoftKeyboard() {
        if (commandInput == null || !commandInput.isEnabled()) return;
        InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (inputMethodManager == null) return;
        commandInput.requestFocus();
        inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT,
                InputMethodManager.HIDE_IMPLICIT_ONLY);
    }

    private View buildKeyPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(5), dp(3), dp(5), dp(2));
        panel.setBackgroundColor(palette.surface);
        addKeyRow(panel, new String[]{"≡", "ESC", "TAB", "HOME", "↑", "END", "PGUP"});
        addKeyRow(panel, new String[]{"⌫", "CTRL", "ALT", "←", "↓", "→", "PGDN"});
        return panel;
    }

    private void addKeyRow(LinearLayout panel, String[] keys) {
        LinearLayout row = horizontal();
        for (String value : keys) {
            final String key = value;
            TextView button = key(key, key, () -> {});
            button.setOnClickListener(v -> handleSoftKey(key, button));
            if (("CTRL".equals(key) && controlArmed) || ("ALT".equals(key) && altArmed)) {
                button.setTextColor(palette.accent);
                button.setBackground(shape(palette.accentSoft, palette.accent, 11));
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(45), 1);
            params.setMargins(dp(2), dp(2), dp(2), dp(2));
            row.addView(button, params);
        }
        panel.addView(row);
    }

    private TextView key(String title, String description, Runnable action) {
        TextView key = label(title, 12, palette.text, true);
        key.setGravity(Gravity.CENTER);
        key.setMinWidth(dp(44));
        key.setMinHeight(dp(44));
        key.setBackground(shape(palette.key, palette.border, 11));
        key.setContentDescription(description);
        key.setOnClickListener(v -> action.run());
        return key;
    }

    private void handleSoftKey(String key, TextView button) {
        if ("≡".equals(key)) {
            focusMode = !focusMode;
            render();
        } else if ("CTRL".equals(key)) {
            controlArmed = !controlArmed;
            updateModifierKey(button, controlArmed);
        } else if ("ALT".equals(key)) {
            altArmed = !altArmed;
            updateModifierKey(button, altArmed);
        } else {
            sendTerminalKey(key);
        }
    }

    private void updateModifierKey(TextView button, boolean armed) {
        button.setTextColor(armed ? palette.accent : palette.text);
        button.setBackground(shape(armed ? palette.accentSoft : palette.key,
                armed ? palette.accent : palette.border, 11));
    }

    private View buildCommandEntry() {
        LinearLayout entry = horizontal();
        entry.setGravity(Gravity.CENTER_VERTICAL);
        entry.setPadding(dp(10), dp(7), dp(10), dp(9));
        entry.setBackgroundColor(palette.surface);
        commandInput = new EditText(this);
        commandInput.setSingleLine(true);
        commandInput.setTextSize(terminalFontSize);
        commandInput.setTextColor(palette.text);
        commandInput.setHintTextColor(palette.muted);
        commandInput.setHint(text("command_hint"));
        applySelectedFont();
        commandInput.setPadding(dp(11), 0, dp(8), 0);
        commandInput.setBackground(shape(palette.input, palette.border, 13));
        commandInput.setText(commandDraft);
        commandInput.setSelection(commandInput.length());
        boolean canSubmit = Session.get().running() && !busy;
        commandInput.setEnabled(canSubmit);
        commandInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        commandInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCommand();
                return true;
            }
            return false;
        });
        commandInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                commandDraft = value.toString();
            }

            @Override
            public void afterTextChanged(android.text.Editable value) {}
        });
        entry.addView(commandInput, new LinearLayout.LayoutParams(0, dp(48), 1));

        TextView send = label("↑", 20, Color.WHITE, true);
        commandSendButton = send;
        send.setGravity(Gravity.CENTER);
        send.setContentDescription(text("action_send"));
        send.setEnabled(canSubmit);
        send.setAlpha(canSubmit ? 1f : 0.45f);
        send.setBackground(shape(canSubmit ? palette.accent : palette.input,
                canSubmit ? palette.accent : palette.border, 13));
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        sendParams.leftMargin = dp(8);
        entry.addView(send, sendParams);
        send.setOnClickListener(v -> {
            if (canSubmit) sendCommand();
        });
        return entry;
    }

    private TextView smallButton(String title, Runnable action) {
        TextView button = label(title, 11, palette.text, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(9), dp(7), dp(9), dp(7));
        button.setBackground(shape(palette.key, palette.border, 11));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(36));
        params.setMargins(dp(4), 0, 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private void sendTerminalKey(String key) {
        if (!Session.get().running()) return;
        String value;
        switch (key) {
            case "ESC": value = "\u001b"; break;
            case "TAB": value = "\t"; break;
            case "HOME": value = "\u001b[H"; break;
            case "END": value = "\u001b[F"; break;
            case "↑": value = "\u001b[A"; break;
            case "↓": value = "\u001b[B"; break;
            case "→": value = "\u001b[C"; break;
            case "←": value = "\u001b[D"; break;
            case "PGUP": value = "\u001b[5~"; break;
            case "PGDN": value = "\u001b[6~"; break;
            case "⌫": value = "\u007f"; break;
            default: value = key;
        }
        if (controlArmed && value.length() == 1) {
            value = String.valueOf((char) (Character.toUpperCase(value.charAt(0)) & 0x1f));
        } else if (altArmed) {
            value = "\u001b" + value;
        }
        Session.get().write(value);
        controlArmed = false;
        altArmed = false;
        render();
    }

    private void sendCommand() {
        if (commandInput == null || !Session.get().running()) return;
        String value = commandInput.getText().toString();
        if (value.isEmpty()) return;
        Session.get().submit(value);
        commandInput.setText("");
        commandDraft = "";
    }

    private void prepareEnvironment() {
        launchMode(GuestCommand.MODE_INSTALL, SCREEN_CONSOLE);
    }

    private void launchMode(String mode, String destination) {
        if (busy) {
            Toast.makeText(this, text("runtime_busy"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (Session.get().running() && mode.equals(Session.get().mode())) {
            navigate(destination);
            if (GuestCommand.MODE_WEB.equals(mode)) waitForWebServer();
            return;
        }
        if (Session.get().running()) {
            new AlertDialog.Builder(this)
                    .setTitle(text("stop_current_title"))
                    .setMessage(text("stop_current_body"))
                    .setNegativeButton(text("action_cancel"), null)
                    .setPositiveButton(text("action_stop"), (dialog, which) -> {
                        busy = true;
                        Thread stopper = new Thread(() -> {
                            Session.get().stop();
                            runOnUiThread(() -> {
                                busy = false;
                                beginMode(mode, destination);
                            });
                        }, "opencode-stop-before-start");
                        stopper.setDaemon(true);
                        stopper.start();
                    })
                    .show();
            return;
        }
        beginMode(mode, destination);
    }

    private void beginMode(String mode, String destination) {
        screen = destination;
        focusMode = false;
        controlArmed = false;
        altArmed = false;
        terminalInitialized = false;
        if (terminal != null) terminal.clear();
        webLoading = GuestCommand.MODE_WEB.equals(mode);
        busy = true;
        runtimeStage = text("runtime_preparing");
        render();
        Launcher.start(this, runtimePrefs, mode, new Launcher.Ui() {
            @Override
            public void line(String value) {
                runOnUiThread(() -> {
                    if (terminal != null) terminal.appendLine(value);
                });
            }

            @Override
            public void raw(String value) {
                runOnUiThread(() -> {
                    if (terminal != null) terminal.append(value);
                });
            }

            @Override
            public void busy(boolean value) {
                runOnUiThread(() -> {
                    busy = value;
                    if (!value) {
                        runtimeStage = null;
                        refreshStatus();
                        if (SCREEN_CONSOLE.equals(screen)) {
                            boolean canSubmit = Session.get().running() && !busy;
                            if (commandInput != null) {
                                commandInput.setEnabled(canSubmit);
                            }
                            if (commandSendButton != null) {
                                commandSendButton.setEnabled(canSubmit);
                                commandSendButton.setAlpha(canSubmit ? 1f : 0.45f);
                                commandSendButton.setBackground(shape(
                                        canSubmit ? palette.accent : palette.input,
                                        canSubmit ? palette.accent : palette.border, 13));
                            }
                        }
                    }
                });
            }

            @Override
            public void status(String value) {
                runOnUiThread(() -> runtimeStage = value);
            }
        });
        if (GuestCommand.MODE_WEB.equals(mode)) waitForWebServer();
    }

    private void stopRuntime() {
        if (!Session.get().running()) return;
        if (busy) return;
        busy = true;
        runtimeStage = text("runtime_stopping");
        new Thread(() -> {
            Session.get().stop();
            runOnUiThread(() -> {
                busy = false;
                runtimeStage = null;
                webLoading = false;
                if (SCREEN_WEB.equals(screen) && webView != null) showWebMessage("web_stopped");
                RunnerService.stop(MainActivity.this);
                refreshStatus();
            });
        }, "opencode-stop").start();
    }

    private void buildWeb() {
        if (webView == null) {
            webView = new WebView(this);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            webView.setWebChromeClient(new WebChromeClient());
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler,
                                                      String host, String realm) {
                    if ("127.0.0.1".equals(host)) {
                        handler.proceed("opencode", runtimePrefs.webPassword());
                    } else {
                        handler.cancel();
                    }
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return handleWebNavigation(request.getUrl());
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return handleWebNavigation(Uri.parse(url));
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    webLoading = false;
                }
            });
        }
        ViewGroup oldParent = (ViewGroup) webView.getParent();
        if (oldParent != null) oldParent.removeView(webView);
        root.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        if (webLoading && webView.getUrl() == null) {
            showWebMessage("web_connecting");
        }
    }

    private boolean handleWebNavigation(Uri uri) {
        if ("http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                && uri.getPort() == GuestCommand.WEB_PORT) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, text("web_external_error"), Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void waitForWebServer() {
        if (webLoading && webView != null && webView.getUrl() != null
                && webView.getUrl().startsWith("http://127.0.0.1:")) return;
        webLoading = true;
        Thread probe = new Thread(() -> {
            boolean ready = false;
            for (int attempt = 0; attempt < 600
                    && (Session.get().running() || busy); attempt++) {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection) new URL(
                            "http://127.0.0.1:" + GuestCommand.WEB_PORT + "/")
                            .openConnection();
                    connection.setConnectTimeout(700);
                    connection.setReadTimeout(700);
                    String auth = Base64.encodeToString(
                            ("opencode:" + runtimePrefs.webPassword()).getBytes(
                                    java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP);
                    connection.setRequestProperty("Authorization", "Basic " + auth);
                    int code = connection.getResponseCode();
                    if (code == 200 || code == 401) {
                        ready = true;
                        break;
                    }
                } catch (Exception ignored) {
                    // The loop is a bounded readiness probe; startup errors are shown below.
                } finally {
                    if (connection != null) connection.disconnect();
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            final boolean result = ready;
            runOnUiThread(() -> {
                if (result && webView != null) {
                    webLoading = false;
                    webView.loadUrl("http://127.0.0.1:" + GuestCommand.WEB_PORT + "/");
                } else if (!result) {
                    webLoading = false;
                    if (webView != null) showWebMessage("web_error");
                    if (terminal != null) {
                        terminal.appendLine("[WEB] Server did not become ready. See the session log.");
                    }
                }
            });
        }, "opencode-web-readiness");
        probe.setDaemon(true);
        probe.start();
    }

    private void refreshStatus() {
        if (SCREEN_HOME.equals(screen) || SCREEN_SETTINGS.equals(screen)
                || SCREEN_SETUP.equals(screen) || SCREEN_CONSOLE.equals(screen)
                || SCREEN_WEB.equals(screen)) render();
    }

    private String runtimeStatusText() {
        if (Session.get().running()) {
            if (GuestCommand.MODE_WEB.equals(Session.get().mode())) return text("status_web");
            if (GuestCommand.MODE_SHELL.equals(Session.get().mode())) return text("status_shell");
            return text("status_running");
        }
        if (runtimeStage != null) return runtimeStage;
        Env env = Env.get(this);
        if (env.openCodeInstalled()) return text("status_ready");
        if (env.rootfsInstalled()) return text("status_linux_ready");
        return text("status_not_ready");
    }

    private void showWebMessage(String key) {
        if (webView == null) return;
        webView.loadDataWithBaseURL(null,
                "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                        + "<body style='font:16px sans-serif;padding:24px'>"
                        + text(key) + "</body>",
                "text/html", "UTF-8", null);
    }

    private void buildSettings(LinearLayout content) {
        content.addView(label(text("settings_title"), 27, palette.text, true));
        addGap(content, 5);
        content.addView(label(text("settings_intro"), 13, palette.muted, false));
        addGap(content, 22);

        content.addView(sectionTitle(text("appearance_heading")));
        addGap(content, 9);
        content.addView(label(text("theme_heading"), 13, palette.muted, true));
        addGap(content, 7);
        String[][] themes = {
                {"system", text("theme_system")},
                {"light", text("theme_light")},
                {"dark", text("theme_dark")},
                {"amoled", text("theme_amoled")},
                {"dracula", "Dracula"}
        };
        for (String[] option : themes) {
            addChoice(content, option[0], option[1], null, theme,
                    value -> {
                        theme = value;
                        preferences.edit().putString("theme", value).apply();
                        render();
                    });
        }
        addGap(content, 19);

        content.addView(label(text("font_heading"), 13, palette.muted, true));
        addGap(content, 7);
        String[][] fonts = {
                {"jetbrains", "JetBrains Mono", text("font_jetbrains")},
                {"hack", "Hack", text("font_hack")},
                {"firacode", "Fira Code", text("font_firacode")},
                {"dejavu", "DejaVu Sans Mono", text("font_dejavu")},
                {"ubuntu", "Ubuntu Mono", text("font_ubuntu")}
        };
        for (String[] option : fonts) {
            addChoice(content, option[0], option[1], option[2], font,
                    value -> {
                        font = value;
                        preferences.edit().putString("font", value).apply();
                        applySelectedFont();
                        render();
                    });
        }
        addGap(content, 18);
        content.addView(label(text("font_size_heading"), 13, palette.muted, true));
        addGap(content, 7);
        for (int size : new int[]{12, 14, 16}) {
            String value = Integer.toString(size);
            addChoice(content, value, value + " sp", null, Integer.toString(terminalFontSize),
                    selected -> {
                        terminalFontSize = Integer.parseInt(selected);
                        preferences.edit().putInt("terminal_font_size", terminalFontSize).apply();
                        render();
                    });
        }
        addGap(content, 11);

        content.addView(sectionTitle(text("environment_heading")));
        addGap(content, 8);
        LinearLayout distroCard = card();
        distroCard.addView(label(text("selected_system"), 12, palette.muted, false));
        addGap(distroCard, 4);
        distroCard.addView(label(text("debian_title"), 15, palette.text, true));
        content.addView(distroCard);
        addGap(content, 12);
        LinearLayout runtimeCard = card();
        runtimeCard.addView(label(text("pty_heading"), 14, palette.text, true));
        addGap(runtimeCard, 4);
        runtimeCard.addView(label(text("pty_description"), 12, palette.muted, false));
        addGap(runtimeCard, 8);
        runtimeCard.addView(secondaryButton(runtimePrefs.pty()
                ? text("pty_on") : text("pty_off"), () -> {
            runtimePrefs.pty(!runtimePrefs.pty());
            render();
        }));
        content.addView(runtimeCard);
        addGap(content, 12);
        content.addView(secondaryButton(text("action_guide"), this::showOnboarding));
        addGap(content, 16);
        content.addView(label(text("settings_storage_note"), 12, palette.muted, false));
    }

    private interface ChoiceListener {
        void onSelect(String value);
    }

    private void addChoice(LinearLayout parent, String id, String title, String subtitle,
                           String selected, ChoiceListener listener) {
        boolean active = id.equals(selected);
        LinearLayout choice = card();
        choice.setBackground(shape(active ? palette.accentSoft : palette.surface,
                active ? palette.accent : palette.border, 16));
        LinearLayout row = horizontal();
        TextView mark = label(active ? "●" : "○", 16, active ? palette.accent : palette.muted, true);
        row.addView(mark);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(10), 0, 0, 0);
        info.addView(label(title, 14, palette.text, true));
        if (subtitle != null && !subtitle.isEmpty()) {
            info.addView(label(subtitle, 11, palette.muted, false));
        }
        row.addView(info);
        choice.addView(row);
        choice.setOnClickListener(v -> listener.onSelect(id));
        parent.addView(choice);
        addGap(parent, 7);
    }

    private void buildSetup(LinearLayout content) {
        content.addView(label(text("setup_title"), 27, palette.text, true));
        addGap(content, 6);
        content.addView(label(text("setup_intro"), 14, palette.muted, false));
        addGap(content, 18);
        boolean rootfsReady = Env.get(this).rootfsInstalled();
        boolean openCodeReady = Env.get(this).openCodeInstalled();
        addSetupItem(content, "01", text("setup_step_rootfs"),
                rootfsReady ? text("state_ready") : text("state_not_configured"));
        addGap(content, 9);
        addSetupItem(content, "02", text("setup_step_runtime"),
                rootfsReady ? text("state_ready") : text("state_not_configured"));
        addGap(content, 9);
        addSetupItem(content, "03", text("setup_step_opencode"),
                openCodeReady ? text("state_ready") : text("state_not_configured"));
        addGap(content, 16);
        LinearLayout warning = card();
        warning.addView(label("ⓘ  " + text(openCodeReady
                ? "setup_ready_title" : "setup_ready_to_install"), 14, palette.accent, true));
        addGap(warning, 6);
        warning.addView(label(text("setup_ready_body"), 13, palette.muted, false));
        content.addView(warning);
        addGap(content, 15);
        content.addView(primaryButton(openCodeReady ? text("action_opencode")
                : text("action_prepare"), openCodeReady
                ? () -> launchMode(GuestCommand.MODE_BOOT, SCREEN_CONSOLE)
                : this::prepareEnvironment));
        addGap(content, 8);
        content.addView(secondaryButton(text("action_console"), () -> navigate(SCREEN_CONSOLE)));
        addGap(content, 8);
        content.addView(secondaryButton(text("action_guide"), this::showOnboarding));
    }

    private void addSetupItem(LinearLayout parent, String number, String title, String status) {
        LinearLayout item = card();
        LinearLayout row = horizontal();
        TextView badge = label(number, 12, palette.muted, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(shape(palette.input, palette.border, 12));
        badge.setWidth(dp(36));
        badge.setHeight(dp(36));
        row.addView(badge);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(10), 0, 0, 0);
        body.addView(label(title, 14, palette.text, true));
        body.addView(label(status, 11, palette.muted, false));
        row.addView(body);
        item.addView(row);
        parent.addView(item);
    }

    private LinearLayout card() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setPadding(dp(15), dp(13), dp(15), dp(13));
        view.setBackground(shape(palette.surface, palette.border, 18));
        return view;
    }

    private LinearLayout horizontal() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private TextView sectionTitle(String value) {
        return label(value, 16, palette.text, true);
    }

    private TextView primaryButton(String value, Runnable action) {
        return actionButton(value, palette.accent, Color.WHITE, action);
    }

    private TextView secondaryButton(String value, Runnable action) {
        return actionButton(value, palette.surface, palette.text, action);
    }

    private TextView actionButton(String value, int background, int foreground, Runnable action) {
        TextView button = label(value, 14, foreground, true);
        button.setGravity(Gravity.CENTER);
        button.setMinimumHeight(dp(46));
        button.setPadding(dp(13), dp(9), dp(13), dp(9));
        button.setBackground(shape(background,
                background == palette.surface ? palette.border : background, 15));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setLineSpacing(dp(2), 1f);
        return view;
    }

    private GradientDrawable shape(int color, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != color && Color.alpha(stroke) != 0) {
            drawable.setStroke(dp(1), stroke);
        }
        return drawable;
    }

    private void addGap(LinearLayout parent, int size) {
        View gap = new View(this);
        parent.addView(gap, new LinearLayout.LayoutParams(1, dp(size)));
    }

    private void navigate(String destination) {
        screen = destination;
        if (!SCREEN_SETTINGS.equals(destination)) settingsScrollY = 0;
        mainScroll = null;
        mainScrollScreen = null;
        focusMode = false;
        controlArmed = false;
        altArmed = false;
        render();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String text(String key) {
        boolean ukrainian = Locale.getDefault().getLanguage().equals("uk");
        switch (key) {
            case "brand_subtitle": return ukrainian ? "ЛОКАЛЬНЕ LINUX-СЕРЕДОВИЩЕ" : "LOCAL LINUX WORKSPACE";
            case "status_not_ready": return ukrainian ? "Не налаштовано" : "Not set up";
            case "menu_accessibility": return ukrainian ? "Відкрити меню" : "Open menu";
            case "menu_home": return ukrainian ? "Головна" : "Home";
            case "menu_console": return ukrainian ? "Консоль" : "Console";
            case "menu_web": return ukrainian ? "Вебінтерфейс" : "Web interface";
            case "menu_settings": return ukrainian ? "Налаштування" : "Settings";
            case "menu_guide": return ukrainian ? "Початок роботи" : "Getting started";
            case "menu_setup": return ukrainian ? "Підготувати середовище" : "Set up environment";
            case "nav_home": return ukrainian ? "Головна" : "Home";
            case "nav_console": return ukrainian ? "Консоль" : "Console";
            case "nav_settings": return ukrainian ? "Візуал" : "Visuals";
            case "home_not_ready": return ukrainian
                    ? "Середовище ще не встановлено" : "Environment not installed";
            case "home_linux_ready": return ukrainian
                    ? "Debian готовий · залишився OpenCode" : "Debian is ready · OpenCode is next";
            case "home_ready": return ukrainian
                    ? "OpenCode готовий до запуску" : "OpenCode is ready";
            case "onboarding_step1_title": return ukrainian ? "Підготовка" : "Set up";
            case "onboarding_step1_body": return ukrainian
                    ? "Застосунок завантажить Debian та OpenCode. Потрібні інтернет і вільне місце на пристрої."
                    : "The app downloads Debian and OpenCode. Internet access and free device storage are required.";
            case "onboarding_step2_title": return ukrainian ? "Консоль і керування" : "Console controls";
            case "onboarding_step2_body": return ukrainian
                    ? "Відкрийте OpenCode у консолі. Нижня панель містить службові клавіші; режим фокусу приховує зайві елементи."
                    : "Open OpenCode in the console. The bottom panel has terminal keys; focus mode hides the extras.";
            case "onboarding_step3_title": return ukrainian ? "Налаштуйте під себе" : "Make it yours";
            case "onboarding_step3_body": return ukrainian
                    ? "У налаштуваннях є теми, шрифти та короткий посібник. Ключі провайдера налаштовуються в OpenCode."
                    : "Settings include themes, fonts and this guide. Configure provider credentials inside OpenCode.";
            case "onboarding_show_at_start": return ukrainian
                    ? "Показувати при старті" : "Show at startup";
            case "action_next": return ukrainian ? "Далі" : "Next";
            case "action_back": return ukrainian ? "Назад" : "Back";
            case "action_skip": return ukrainian ? "Пропустити" : "Skip";
            case "action_done": return ukrainian ? "Готово" : "Done";
            case "status_running": return ukrainian ? "Працює" : "Running";
            case "status_web": return ukrainian ? "Вебсервер працює" : "Web server running";
            case "status_shell": return ukrainian ? "Shell працює" : "Shell running";
            case "status_ready": return ukrainian ? "OpenCode готовий" : "OpenCode ready";
            case "status_linux_ready": return ukrainian ? "Linux готовий" : "Linux ready";
            case "debian_title": return "Debian Slim";
            case "action_prepare": return ukrainian ? "Підготувати середовище" : "Prepare environment";
            case "action_opencode": return ukrainian ? "Запустити OpenCode" : "Start OpenCode";
            case "action_shell": return ukrainian ? "Відкрити shell" : "Open shell";
            case "action_web": return ukrainian ? "Веб" : "Web";
            case "action_stop": return ukrainian ? "Зупинити" : "Stop";
            case "action_cancel": return ukrainian ? "Скасувати" : "Cancel";
            case "action_console": return ukrainian ? "Відкрити консоль" : "Open console";
            case "action_guide": return ukrainian ? "Початок роботи" : "Getting started";
            case "terminal_hint": return ukrainian
                    ? "Консоль готова. Підготуйте середовище, щоб почати."
                    : "Console ready. Prepare the environment to begin.";
            case "console_title": return ukrainian ? "Сесія термінала" : "Terminal session";
            case "focus_exit": return ukrainian ? "Повернути панель" : "Show controls";
            case "focus_hint": return ukrainian ? "Режим фокусу · натисніть ≡, щоб повернути панель" : "Focus mode · tap ≡ to restore controls";
            case "focus_keyboard": return ukrainian
                    ? "Показати або сховати клавіатуру" : "Show or hide the keyboard";
            case "command_hint": return ukrainian ? "Команда або повідомлення…" : "Type a command or message…";
            case "action_send": return ukrainian ? "Надіслати команду" : "Send command";
            case "settings_title": return ukrainian ? "Налаштування" : "Settings";
            case "settings_intro": return ukrainian ? "Оберіть тему й шрифт термінала." : "Choose a theme and a typeface for your terminal.";
            case "appearance_heading": return ukrainian ? "Зовнішній вигляд" : "Appearance";
            case "theme_heading": return ukrainian ? "Тема" : "Theme";
            case "theme_system": return ukrainian ? "Як у системі" : "Follow system";
            case "theme_light": return ukrainian ? "Світла" : "Light";
            case "theme_dark": return ukrainian ? "Темна" : "Dark";
            case "theme_amoled": return ukrainian ? "Темна AMOLED" : "AMOLED black";
            case "font_heading": return ukrainian ? "Моноширинний шрифт" : "Terminal font";
            case "font_size_heading": return ukrainian ? "Розмір тексту термінала" : "Terminal text size";
            case "font_jetbrains": return ukrainian ? "Чіткий і збалансований" : "Crisp, carefully balanced";
            case "font_hack": return ukrainian ? "Класика з підтримкою Powerline" : "A classic with Powerline support";
            case "font_firacode": return ukrainian ? "Лігатури для програмного коду" : "Programming ligatures";
            case "font_dejavu": return ukrainian ? "Надзвичайно розбірливий" : "Familiar and highly readable";
            case "font_ubuntu": return ukrainian ? "Відкриті форми літер" : "Distinctive open letterforms";
            case "environment_heading": return ukrainian ? "Середовище" : "Environment";
            case "selected_system": return ukrainian ? "Вибраний дистрибутив" : "Selected distribution";
            case "settings_storage_note": return ukrainian
                    ? "Налаштування вигляду й сумісності зберігаються на пристрої."
                    : "Appearance and compatibility preferences stay on this device.";
            case "pty_heading": return ukrainian ? "PTY термінала" : "Terminal PTY";
            case "pty_description": return ukrainian ? "Залиште PTY увімкненим для стабільного інтерактивного вводу в Linux. Вимкніть лише для простішого стрічкового режиму." : "Keep PTY enabled for interactive Linux input. Disable it only for a simpler stream-only console.";
            case "pty_on": return ukrainian ? "PTY увімкнено · натисніть, щоб вимкнути" : "PTY enabled · tap to disable";
            case "pty_off": return ukrainian ? "PTY вимкнено · натисніть, щоб увімкнути" : "PTY disabled · tap to enable";
            case "setup_title": return ukrainian ? "Підготовка Linux" : "Prepare Linux";
            case "setup_intro": return ukrainian ? "Перевірений шлях встановлення складається з трьох кроків." : "A verified environment needs three installation steps.";
            case "setup_step_rootfs": return ukrainian ? "Rootfs Debian Trixie" : "Debian Trixie rootfs";
            case "setup_step_runtime": return ukrainian ? "ARM64 Linux runtime" : "ARM64 Linux runtime";
            case "setup_step_opencode": return ukrainian ? "OpenCode CLI" : "OpenCode CLI";
            case "state_not_configured": return ukrainian ? "Ще не налаштовано" : "Not configured";
            case "state_ready": return ukrainian ? "Готово" : "Ready";
            case "setup_ready_title": return ukrainian ? "Середовище готове" : "Environment ready";
            case "setup_ready_to_install": return ukrainian ? "Готово до встановлення" : "Ready to install";
            case "setup_ready_body": return ukrainian ? "Підготовка встановлює Debian Trixie, системні пакети та OpenCode CLI. Для завантаження потрібне підключення до мережі." : "Setup installs Debian Trixie, system packages and OpenCode CLI. An internet connection is required.";
            case "runtime_busy": return ukrainian ? "Зачекайте, поки завершиться поточна операція." : "Please wait for the current operation to finish.";
            case "runtime_preparing": return ukrainian ? "Підготовка…" : "Preparing…";
            case "runtime_stopping": return ukrainian ? "Зупинення сесії…" : "Stopping session…";
            case "stop_current_title": return ukrainian ? "Завершити поточну сесію?" : "Stop the current session?";
            case "stop_current_body": return ukrainian ? "Щоб запустити інший режим, поточну сесію потрібно зупинити." : "The current session must stop before another mode can start.";
            case "web_connecting": return ukrainian ? "Запускаємо локальний сервер OpenCode…" : "Starting the local OpenCode server…";
            case "web_stopped": return ukrainian ? "Вебсервер зупинено. Запустіть вебінтерфейс знову з головного екрана." : "The web server is stopped. Start the web interface again from Home.";
            case "web_error": return ukrainian ? "Не вдалося підключитися до OpenCode. Перевірте журнал сесії." : "Could not connect to OpenCode. Check the session log.";
            case "web_external_error": return ukrainian ? "Не вдалося відкрити посилання." : "Could not open this link.";
            default: return key;
        }
    }

    private String fontAsset() {
        switch (font) {
            case "hack": return "fonts/Hack-Regular.ttf";
            case "firacode": return "fonts/FiraCode-VF.ttf";
            case "dejavu": return "fonts/DejaVuSansMono.ttf";
            case "ubuntu": return "fonts/UbuntuMono-Regular.ttf";
            default: return "fonts/JetBrainsMono-Regular.ttf";
        }
    }

    private void applySelectedFont() {
            String asset = fontAsset();
            android.util.Log.d("OpenCodeFont", "applySelectedFont: font=" + font + " asset=" + asset);
            Typeface selectedTypeface = Typeface.createFromAsset(getAssets(), asset);
            android.util.Log.d("OpenCodeFont", "applySelectedFont: typeface=" + (selectedTypeface != null ? selectedTypeface.toString() : "NULL"));
        String features = "firacode".equals(font) ? "'calt'" : "";
        if (terminal != null) {
            terminal.setTypeface(selectedTypeface);
            terminal.setFontFeatureSettings(features);
        }
        if (commandInput != null) {
            commandInput.setTypeface(selectedTypeface);
            commandInput.setFontFeatureSettings(features);
        }
    }

    private static final class Palette {
        final int background;
        final int surface;
        final int input;
        final int key;
        final int terminal;
        final int terminalText;
        final int text;
        final int muted;
        final int accent;
        final int accentSoft;
        final int warning;
        final int warningSoft;
        final int border;
        final boolean lightStatusIcons;

        Palette(int background, int surface, int input, int key, int terminal, int terminalText,
                int text, int muted, int accent, int accentSoft, int warning, int warningSoft,
                int border, boolean lightStatusIcons) {
            this.background = background;
            this.surface = surface;
            this.input = input;
            this.key = key;
            this.terminal = terminal;
            this.terminalText = terminalText;
            this.text = text;
            this.muted = muted;
            this.accent = accent;
            this.accentSoft = accentSoft;
            this.warning = warning;
            this.warningSoft = warningSoft;
            this.border = border;
            this.lightStatusIcons = lightStatusIcons;
        }

        static Palette forTheme(String theme, boolean systemDark) {
            if ("dracula".equals(theme)) {
                return new Palette(0xFF282A36, 0xFF303341, 0xFF222430, 0xFF383B4A,
                        0xFF191A23, 0xFFF8F8F2, 0xFFF8F8F2, 0xFFB8B8C5, 0xFFBD93F9,
                        0xFF393347, 0xFFFFB86C, 0xFF45362A, 0xFF424554, false);
            }
            if ("amoled".equals(theme)) {
                return new Palette(0xFF000000, 0xFF080808, 0xFF050505, 0xFF101010,
                        0xFF000000, 0xFFF0F2EF, 0xFFF0F2EF, 0xFF9CA5A0, 0xFF91D4B0,
                        0xFF10251A, 0xFFFFD18B, 0xFF302416, 0xFF202020, false);
            }
            boolean dark = "dark".equals(theme) || ("system".equals(theme) && systemDark);
            if (dark) {
                return new Palette(0xFF070809, 0xFF111315, 0xFF080A0B, 0xFF171A1C,
                        0xFF020303, 0xFFE0E7E3, 0xFFF0F2EF, 0xFFA0A8A4, 0xFF8ACBA7,
                        0xFF17291F, 0xFFF0C27B, 0xFF322A1E, 0xFF25292A, false);
            }
            return new Palette(0xFFF5F5F2, 0xFFFFFFFF, 0xFFF8F8F6, 0xFFFAFAF9,
                    0xFF20252B, 0xFFE4EAE7, 0xFF202722, 0xFF717973, 0xFF537A64,
                    0xFFE9F2EC, 0xFF8A642D, 0xFFFBF0DE, 0xFFE7E9E4, true);
        }
    }
}
