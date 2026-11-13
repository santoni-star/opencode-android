package com.opencode.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.webkit.HttpAuthHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
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
    private static final String SCREEN_GUIDE = "guide";
    private static final String SCREEN_SETUP = "setup";
    private static final String SCREEN_WEB = "web";

    private SharedPreferences preferences;
    private Prefs runtimePrefs;
    private String screen = SCREEN_HOME;
    private String theme;
    private String font;
    private String distro;
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
    private TerminalView terminal;
    private WebView webView;
    private boolean terminalInitialized;
    private volatile boolean busy;
    private boolean webLoading;
    private String runtimeStage;

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
        distro = "debian";
        terminalFontSize = preferences.getInt("terminal_font_size", 14);
        Session.get().addListener(sessionListener);
        render();
    }

    @Override
    protected void onDestroy() {
        Session.get().removeListener(sessionListener);
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
            else if (SCREEN_GUIDE.equals(screen)) buildGuide(content);
            else buildSetup(content);
            scroll.addView(content);
            root.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            if (SCREEN_SETTINGS.equals(screen) && settingsScrollY > 0) {
                scroll.post(() -> scroll.scrollTo(0, settingsScrollY));
            }
        }

        if (!(SCREEN_CONSOLE.equals(screen) && focusMode)) {
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
            navigate(SCREEN_GUIDE);
            return true;
        });
        menu.show();
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
        TextView eyebrow = label(text("home_eyebrow"), 11, palette.accent, true);
        eyebrow.setLetterSpacing(0.11f);
        content.addView(eyebrow);
        addGap(content, 7);
        content.addView(label(text("home_title"), 29, palette.text, true));
        addGap(content, 7);
        content.addView(label(text("home_description"), 14, palette.muted, false));
        addGap(content, 20);

        LinearLayout notice = card();
        TextView noticeTitle = label("◌  " + runtimeStatusText(), 15, palette.text, true);
        notice.addView(noticeTitle);
        addGap(notice, 6);
        notice.addView(label(text(Env.get(this).rootfsInstalled()
                ? "runtime_ready_explanation" : "runtime_explanation"), 13, palette.muted, false));
        content.addView(notice);
        addGap(content, 20);

        content.addView(sectionTitle(text("os_heading")));
        addGap(content, 9);
        addDistroCard(content, "debian", text("debian_title"), text("debian_description"), text("recommended"));
        addGap(content, 9);
        addDistroCard(content, "ubuntu", text("ubuntu_title"),
                text("ubuntu_unavailable"), text("unavailable"));
        addGap(content, 17);

        LinearLayout tool = card();
        LinearLayout toolHeader = horizontal();
        TextView toolIcon = label("⌘", 23, palette.accent, true);
        toolIcon.setGravity(Gravity.CENTER);
        toolIcon.setBackground(shape(palette.accentSoft, palette.accentSoft, 15));
        toolIcon.setWidth(dp(48));
        toolIcon.setHeight(dp(48));
        toolHeader.addView(toolIcon);
        LinearLayout toolInfo = new LinearLayout(this);
        toolInfo.setOrientation(LinearLayout.VERTICAL);
        toolInfo.setPadding(dp(12), dp(2), 0, 0);
        toolInfo.addView(label("OpenCode", 16, palette.text, true));
        toolInfo.addView(label(text("tool_description"), 12, palette.muted, false));
        toolHeader.addView(toolInfo);
        tool.addView(toolHeader);
        addGap(tool, 12);
        tool.addView(label(text("tool_auth_note"), 12, palette.muted, false));
        content.addView(tool);
        addGap(content, 16);

        content.addView(primaryButton(text("action_prepare"), this::prepareEnvironment));
        addGap(content, 9);
        LinearLayout actions = horizontal();
        actions.addView(secondaryButton(text("action_opencode"),
                        () -> launchMode(GuestCommand.MODE_BOOT, SCREEN_CONSOLE)),
                new LinearLayout.LayoutParams(0, dp(46), 1));
        View spacer = new View(this);
        actions.addView(spacer, new LinearLayout.LayoutParams(dp(9), 1));
        actions.addView(secondaryButton(text("action_shell"),
                        () -> launchMode(GuestCommand.MODE_SHELL, SCREEN_CONSOLE)),
                new LinearLayout.LayoutParams(0, dp(46), 1));
        content.addView(actions);
        addGap(content, 9);
        content.addView(secondaryButton(text("action_web"),
                () -> launchMode(GuestCommand.MODE_WEB, SCREEN_WEB)));
        addGap(content, 17);
        content.addView(label(text("storage_note"), 11, palette.muted, false));
    }

    private void addDistroCard(LinearLayout parent, String id, String title, String description, String badge) {
        boolean available = "debian".equals(id);
        boolean selected = id.equals(distro);
        LinearLayout option = card();
        option.setBackground(shape(selected ? palette.accentSoft : palette.surface,
                selected ? palette.accent : palette.border, 18));
        option.setAlpha(available ? 1f : 0.58f);
        LinearLayout top = horizontal();
        TextView radio = label(selected ? "●" : "○", 17,
                selected ? palette.accent : palette.muted, true);
        top.addView(radio);
        TextView name = label(title, 15, palette.text, true);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1);
        nameParams.leftMargin = dp(9);
        top.addView(name, nameParams);
        if (badge != null) {
            TextView recommended = label(badge, 10, palette.accent, true);
            recommended.setPadding(dp(8), dp(5), dp(8), dp(5));
            recommended.setBackground(shape(palette.surface, palette.surface, 12));
            top.addView(recommended);
        }
        option.addView(top);
        addGap(option, 6);
        TextView detail = label(description + "\n" + text("arch_badge"), 12, palette.muted, false);
        detail.setPadding(dp(26), 0, 0, 0);
        option.addView(detail);
        if (available) {
            option.setOnClickListener(v -> {
                distro = id;
                preferences.edit().putString("distro", id).apply();
                render();
            });
        }
        parent.addView(option);
    }

    private void buildConsole() {
        if (focusMode) root.addView(buildFocusStrip());

        if (!focusMode) {
            LinearLayout controls = horizontal();
            controls.setPadding(dp(12), dp(8), dp(12), dp(3));
            TextView title = label(text("console_title"), 14, palette.text, true);
            controls.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            controls.addView(smallButton(text("action_web"),
                    () -> launchMode(GuestCommand.MODE_WEB, SCREEN_WEB)));
            controls.addView(smallButton(text("action_stop"), this::stopRuntime));
            root.addView(controls);
        }

        if (terminal == null) terminal = new TerminalView(this);
        ViewGroup oldParent = (ViewGroup) terminal.getParent();
        if (oldParent != null) oldParent.removeView(terminal);
        terminal.setTypeface(Typeface.createFromAsset(getAssets(), fontAsset()));
        terminal.setFontFeatureSettings("firacode".equals(font) ? "'calt'" : "");
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

        root.addView(buildKeyPanel());
        root.addView(buildCommandEntry());
    }

    private View buildFocusStrip() {
        LinearLayout strip = horizontal();
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPadding(dp(12), dp(5), dp(12), dp(5));
        strip.setBackgroundColor(palette.surface);
        TextView restore = key("≡", text("focus_exit"), () -> {
            focusMode = false;
            render();
        });
        strip.addView(restore);
        TextView info = label(text("focus_hint"), 11, palette.muted, false);
        info.setPadding(dp(10), 0, 0, 0);
        strip.addView(info);
        return strip;
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
        commandInput.setTypeface(Typeface.createFromAsset(getAssets(), fontAsset()));
        if ("firacode".equals(font)) commandInput.setFontFeatureSettings("'calt'");
        commandInput.setPadding(dp(11), 0, dp(8), 0);
        commandInput.setBackground(shape(palette.input, palette.border, 13));
        commandInput.setText(commandDraft);
        commandInput.setSelection(commandInput.length());
        commandInput.setEnabled(Session.get().running() && !busy);
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
        send.setGravity(Gravity.CENTER);
        send.setContentDescription(text("action_send"));
        send.setBackground(shape(palette.accent, palette.accent, 13));
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        sendParams.leftMargin = dp(8);
        entry.addView(send, sendParams);
        send.setOnClickListener(v -> sendCommand());
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
                        if (SCREEN_CONSOLE.equals(screen) && commandInput != null) {
                            commandInput.setEnabled(Session.get().running());
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
        runtimeCard.addView(label(text("compat_heading"), 14, palette.text, true));
        addGap(runtimeCard, 4);
        runtimeCard.addView(label(text("compat_description"), 12, palette.muted, false));
        addGap(runtimeCard, 8);
        runtimeCard.addView(secondaryButton(runtimePrefs.compatMode()
                ? text("compat_on") : text("compat_off"), () -> {
            runtimePrefs.compatMode(!runtimePrefs.compatMode());
            render();
        }));
        content.addView(runtimeCard);
        addGap(content, 12);
        content.addView(secondaryButton(text("action_guide"), () -> navigate(SCREEN_GUIDE)));
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

    private void buildGuide(LinearLayout content) {
        TextView eyebrow = label(text("guide_eyebrow"), 11, palette.accent, true);
        eyebrow.setLetterSpacing(0.1f);
        content.addView(eyebrow);
        addGap(content, 8);
        content.addView(label(text("guide_title"), 28, palette.text, true));
        addGap(content, 8);
        content.addView(label(text("guide_intro"), 14, palette.muted, false));
        addGap(content, 19);
        addGuideStep(content, "01", text("guide_step1_title"), text("guide_step1_body"));
        addGap(content, 9);
        addGuideStep(content, "02", text("guide_step2_title"), text("guide_step2_body"));
        addGap(content, 9);
        addGuideStep(content, "03", text("guide_step3_title"), text("guide_step3_body"));
        addGap(content, 17);
        content.addView(sectionTitle(text("guide_privacy_title")));
        addGap(content, 8);
        LinearLayout privacy = card();
        privacy.addView(label(text("guide_privacy_body"), 13, palette.muted, false));
        content.addView(privacy);
        addGap(content, 16);
        content.addView(secondaryButton(text("action_prepare"), this::prepareEnvironment));
    }

    private void addGuideStep(LinearLayout parent, String number, String title, String description) {
        LinearLayout step = card();
        LinearLayout row = horizontal();
        TextView badge = label(number, 12, palette.accent, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(shape(palette.accentSoft, palette.accentSoft, 13));
        badge.setWidth(dp(38));
        badge.setHeight(dp(38));
        row.addView(badge);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(11), 0, 0, 0);
        body.addView(label(title, 14, palette.text, true));
        body.addView(label(description, 12, palette.muted, false));
        row.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
        step.addView(row);
        parent.addView(step);
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
        content.addView(secondaryButton(text("action_guide"), () -> navigate(SCREEN_GUIDE)));
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
            case "home_eyebrow": return ukrainian ? "ВАШ ПРОСТІР ДЛЯ РОЗРОБКИ" : "YOUR MOBILE DEV SPACE";
            case "home_title": return ukrainian ? "OpenCode.\nПрямо на Android." : "OpenCode.\nOn your terms.";
            case "home_description": return ukrainian ? "Локальне Linux-середовище для роботи з агентом кодування." : "A Linux workspace on your phone, ready for your coding agent.";
            case "runtime_title": return ukrainian ? "Середовище ще не встановлено" : "Linux runtime not installed";
            case "runtime_explanation": return ukrainian ? "Коли Linux runtime буде готовий, тут з’явиться запуск OpenCode, вебінтерфейсу та shell." : "OpenCode, its web UI and a Linux shell become available after a verified runtime is installed.";
            case "runtime_ready_explanation": return ukrainian ? "Середовище Debian зберігає конфігурацію OpenCode і робочі файли між запусками." : "Debian is ready. OpenCode settings and workspace files persist between sessions.";
            case "status_running": return ukrainian ? "Працює" : "Running";
            case "status_web": return ukrainian ? "Вебсервер працює" : "Web server running";
            case "status_shell": return ukrainian ? "Shell працює" : "Shell running";
            case "status_ready": return ukrainian ? "OpenCode готовий" : "OpenCode ready";
            case "status_linux_ready": return ukrainian ? "Linux готовий" : "Linux ready";
            case "os_heading": return ukrainian ? "Операційна система" : "Choose your Linux";
            case "debian_title": return "Debian Slim";
            case "debian_description": return ukrainian ? "Компактна система. Рекомендовано для першого запуску." : "A lean base system. A great place to start.";
            case "ubuntu_title": return "Ubuntu Minimal";
            case "ubuntu_description": return ukrainian ? "Знайоме мінімальне середовище Ubuntu." : "A familiar, minimal Ubuntu environment.";
            case "recommended": return ukrainian ? "Рекомендовано" : "Recommended";
            case "arch_badge": return ukrainian
                    ? "Перевірена ціль · Debian Trixie ARM64"
                    : "Verified target · Debian Trixie ARM64";
            case "unavailable": return ukrainian ? "Поки недоступно" : "Not available yet";
            case "ubuntu_unavailable": return ukrainian ? "Ubuntu ще не має перевіреного образу для цього runtime." : "No verified Ubuntu image is available for this runtime yet.";
            case "tool_description": return ukrainian ? "AI-агент для програмування" : "The AI coding agent";
            case "tool_auth_note": return ukrainian ? "Ви самостійно налаштовуєте провайдера й авторизацію в OpenCode." : "Provider credentials are configured by you, directly in OpenCode.";
            case "action_prepare": return ukrainian ? "Підготувати середовище" : "Prepare environment";
            case "action_opencode": return ukrainian ? "Запустити OpenCode" : "Start OpenCode";
            case "action_shell": return ukrainian ? "Відкрити shell" : "Open shell";
            case "action_web": return ukrainian ? "Веб" : "Web";
            case "action_stop": return ukrainian ? "Зупинити" : "Stop";
            case "action_cancel": return ukrainian ? "Скасувати" : "Cancel";
            case "action_console": return ukrainian ? "Відкрити консоль" : "Open console";
            case "action_guide": return ukrainian ? "Початок роботи" : "Getting started";
            case "storage_note": return ukrainian ? "Ваші файли, проєкти й налаштування мають зберігатися окремо від системи." : "Your files, projects and settings should live separately from the Linux system.";
            case "terminal_hint": return ukrainian
                    ? "Вітаємо в OpenCode Android.\n\nПідготуйте Debian, щоб запустити OpenCode або shell.\nРобочі файли зберігаються у приватному сховищі застосунку."
                    : "Welcome to OpenCode Android.\n\nPrepare Debian to start OpenCode or a shell.\nWorkspace files persist in the app's private storage.";
            case "console_title": return ukrainian ? "Сесія термінала" : "Terminal session";
            case "focus_exit": return ukrainian ? "Повернути панель" : "Show controls";
            case "focus_hint": return ukrainian ? "Режим фокусу · натисніть ≡, щоб повернути панель" : "Focus mode · tap ≡ to restore controls";
            case "command_hint": return ukrainian ? "Команда або повідомлення…" : "Type a command or message…";
            case "action_send": return ukrainian ? "Надіслати команду" : "Send command";
            case "settings_title": return ukrainian ? "Налаштування вигляду" : "Make it yours";
            case "settings_intro": return ukrainian ? "Оберіть тему й шрифт термінала." : "Choose a theme and a typeface for your terminal.";
            case "appearance_heading": return ukrainian ? "Зовнішній вигляд" : "Appearance";
            case "theme_heading": return ukrainian ? "Тема" : "Theme";
            case "theme_system": return ukrainian ? "Як у системі" : "Follow system";
            case "theme_light": return ukrainian ? "Світла" : "Light";
            case "theme_dark": return ukrainian ? "Темна" : "Dark";
            case "font_heading": return ukrainian ? "Моноширинний шрифт" : "Terminal font";
            case "font_size_heading": return ukrainian ? "Розмір тексту термінала" : "Terminal text size";
            case "font_jetbrains": return ukrainian ? "Чіткий і збалансований" : "Crisp, carefully balanced";
            case "font_hack": return ukrainian ? "Класика з підтримкою Powerline" : "A classic with Powerline support";
            case "font_firacode": return ukrainian ? "Лігатури для програмного коду" : "Programming ligatures";
            case "font_dejavu": return ukrainian ? "Надзвичайно розбірливий" : "Familiar and highly readable";
            case "font_ubuntu": return ukrainian ? "Відкриті форми літер" : "Distinctive open letterforms";
            case "environment_heading": return ukrainian ? "Середовище" : "Environment";
            case "selected_system": return ukrainian ? "Вибраний дистрибутив" : "Selected distribution";
            case "settings_storage_note": return ukrainian ? "Тема, шрифт і дистрибутив зберігаються на пристрої." : "Your theme, font and distribution choice are saved on this device.";
            case "compat_heading": return ukrainian ? "Сумісність chroot-ng" : "chroot-ng compatibility";
            case "compat_description": return ukrainian ? "Увімкніть переписування системних викликів, якщо звичайний запуск не працює на вашому пристрої." : "Enable syscall rewriting only if the default runtime fails on your device.";
            case "compat_on": return ukrainian ? "Увімкнено · натисніть, щоб вимкнути" : "Enabled · tap to disable";
            case "compat_off": return ukrainian ? "Вимкнено · натисніть, щоб увімкнути" : "Disabled · tap to enable";
            case "guide_eyebrow": return ukrainian ? "ЛАСКАВО ПРОСИМО" : "WELCOME";
            case "guide_title": return ukrainian ? "Початок роботи" : "A good place to start";
            case "guide_intro": return ukrainian ? "OpenCode Android створено, щоб зручно запускати інструменти розробника в Linux-середовищі на Android." : "OpenCode Android is designed to bring a comfortable Linux development workspace to your Android device.";
            case "guide_step1_title": return ukrainian ? "Підготуйте Linux" : "Set up Linux";
            case "guide_step1_body": return ukrainian ? "Підготуйте Debian Trixie для ARM64. Rootfs завантажується з офіційного образу та перевіряється за SHA-256." : "Prepare Debian Trixie for ARM64. The rootfs comes from the official image and is checked by SHA-256.";
            case "guide_step2_title": return ukrainian ? "Налаштуйте OpenCode" : "Configure OpenCode";
            case "guide_step2_body": return ukrainian ? "Встановлення використовує офіційний пакет npm. Налаштуйте провайдера всередині OpenCode." : "Installation uses the official npm package. Configure your provider inside OpenCode.";
            case "guide_step3_title": return ukrainian ? "Працюйте локально" : "Work locally";
            case "guide_step3_body": return ukrainian ? "Використовуйте TUI, shell або вебінтерфейс, прив’язаний лише до localhost." : "Use the TUI, shell or a web interface bound to localhost.";
            case "guide_privacy_title": return ukrainian ? "Ваші облікові дані — ваші" : "Your credentials stay yours";
            case "guide_privacy_body": return ukrainian ? "Застосунок не збирає API-ключі. Вебсервер доступний лише через 127.0.0.1; встановлення потребує інтернету." : "The app does not collect API keys. The web server binds to 127.0.0.1; installation requires internet access.";
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
            boolean dark = "dark".equals(theme) || ("system".equals(theme) && systemDark);
            if (dark) {
                return new Palette(0xFF111318, 0xFF1A1D24, 0xFF11141A, 0xFF22262E,
                        0xFF0D1015, 0xFFDCE4E0, 0xFFE9ECE9, 0xFF9BA5A1, 0xFF83B89D,
                        0xFF22352D, 0xFFF0C27B, 0xFF3B3224, 0xFF2B3038, false);
            }
            return new Palette(0xFFF5F5F2, 0xFFFFFFFF, 0xFFF8F8F6, 0xFFFAFAF9,
                    0xFF20252B, 0xFFE4EAE7, 0xFF202722, 0xFF717973, 0xFF537A64,
                    0xFFE9F2EC, 0xFF8A642D, 0xFFFBF0DE, 0xFFE7E9E4, true);
        }
    }
}
