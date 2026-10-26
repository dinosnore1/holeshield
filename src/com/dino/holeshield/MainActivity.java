package com.dino.holeshield;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.InetAddress;
import java.net.URL;
import java.net.HttpURLConnection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * One activity, five screens, no fragments and no libraries. Every action is a
 * focusable row or button, so the same UI works with a TV remote, a D-pad and
 * touch. The layout compacts itself on phones and uses a fullscreen immersive
 * presentation only on television devices.
 */
public class MainActivity extends Activity {

    private enum Screen { HOME, SETTINGS, LISTS, PIHOLE, LOG }

    private static final int REQ_VPN = 41;
    private static final int REQ_NOTIFICATION = 42;

    private Prefs prefs;
    private boolean isTv;
    private Screen screen = Screen.HOME;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);

    private FrameLayout root;
    private ScrollView scroll;
    private LinearLayout page;
    private View initialFocus;

    private PiholeClient.Summary pihole;
    private boolean piholeLoading;
    private long piholeFetchedAt;
    private boolean autoStarted;
    private boolean vpnConsentNeeded;
    private boolean discovering;

    // Home screen views, updated in place so focus is never lost.
    private ShieldView shield;
    private TextView chip;
    private TextView heroTitle;
    private TextView heroSub;
    private TextView primaryLabel;
    private TextView statQueries;
    private TextView statBlocked;
    private TextView statRate;
    private TextView netTitle;
    private TextView netDetail;
    private TextView netHint;
    private LinearLayout recentBox;
    private String recentSignature = "";

    // Pi-hole screen views.
    private TextView piholeTitle;
    private TextView piholeSub;
    private TextView piholeQ;
    private TextView piholeB;
    private TextView piholePct;
    private TextView piholeGravity;
    private TextView piholeError;
    private TextView piholeServer;
    private LinearLayout piholeTopBox;
    private LinearLayout piholeClientBox;
    private String piholeTopSignature = "";
    private String piholeClientSignature = "";

    // Blocklist screen views.
    private TextView listCount;
    private TextView listUpdated;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateLive();
            handler.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            prefs = Prefs.load(context);
            vpnConsentNeeded = VpnService.prepare(context) != null;
            if (screen == Screen.SETTINGS) {
                show(Screen.SETTINGS);
            } else {
                updateLive();
            }
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        isTv = isTelevision(this);
        Ui.setCompact(!isTv);
        setRequestedOrientation(isTv
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        Ui.init(this);
        prefs = Prefs.load(this);
        vpnConsentNeeded = VpnService.prepare(this) != null;
        if (!DnsVpnService.running && !DnsVpnService.starting) {
            Stats.load(this);
        }
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (isTv) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        immersive();
        setContentView(createShell());
        show(Screen.HOME);
        requestNotificationPermission();
        if (!DnsVpnService.running && !DnsVpnService.starting) {
            loadGravityAsync();
        }
    }

    private void loadGravityAsync() {
        Blocklist.loadAsync(this, () -> {
            if (isFinishing() || isDestroyed()) return;
            if (screen == Screen.LISTS) show(Screen.LISTS);
            else updateLive();
        });
    }

    private void immersive() {
        if (isTv) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            return;
        }
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Ui.BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private static boolean isTelevision(Context c) {
        return Device.isTv(c);
    }

    private View createShell() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        root.addView(new Backdrop(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        page = Ui.column(this);
        int h = Ui.dp(this, isTv ? 44 : 20);
        int v = Ui.dp(this, isTv ? 34 : 20);
        page.setPadding(h, v, h, v);
        scroll.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (!isTv && Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                scroll.setPadding(0, bars.top, 0, bars.bottom);
                return insets;
            });
        }
        return root;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override protected void onResume() {
        super.onResume();
        isTv = isTelevision(this);
        Ui.setCompact(!isTv);
        if (Ui.init(this) && root != null) {
            setContentView(createShell());
            show(screen);
        }
        prefs = Prefs.load(this);
        vpnConsentNeeded = VpnService.prepare(this) != null;
        if (!autoStarted && prefs.protectionWanted && !DnsVpnService.running
                && !vpnConsentNeeded) {
            autoStarted = true;
            startProtection(false);
        }
        updateLive();
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(DnsVpnService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, f);
        }
    }

    @Override protected void onStop() {
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception ignored) { }
        super.onStop();
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        isTv = isTelevision(this);
        Ui.setCompact(!isTv);
        setRequestedOrientation(isTv
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        Ui.init(this);
        if (root != null) {
            setContentView(createShell());
            show(screen);
        }
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        // Every setting is saved where it changes. Saving this snapshot here
        // can overwrite lastUpdate written by a background gravity update.
        super.onPause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (screen != Screen.HOME) {
            show(Screen.HOME);
        } else {
            super.onBackPressed();
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            show(Screen.SETTINGS);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            vpnConsentNeeded = VpnService.prepare(this) != null;
            if (resultCode == RESULT_OK) {
                prefs.protectionWanted = true;
                prefs.save(this);
                startProtection(false);
            } else {
                prefs.protectionWanted = false;
                prefs.save(this);
                Ui.toast(this, "Protection needs VPN permission");
            }
            show(Screen.HOME);
        }
    }

    // ------------------------------------------------------------------ screens

    private void show(Screen next) {
        screen = next;
        initialFocus = null;
        clearRefs();
        page.removeAllViews();
        View content;
        switch (next) {
            case SETTINGS: content = settingsScreen(); break;
            case LISTS: content = listsScreen(); break;
            case PIHOLE: content = piholeScreen(); break;
            case LOG: content = logScreen(); break;
            default: content = homeScreen(); break;
        }
        page.addView(content, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        scroll.scrollTo(0, 0);
        if (initialFocus != null) {
            final View focus = initialFocus;
            focus.postDelayed(() -> focus.requestFocus(), 80);
        }
        updateLive();
    }

    private void clearRefs() {
        shield = null;
        chip = null;
        heroTitle = null;
        heroSub = null;
        primaryLabel = null;
        statQueries = null;
        statBlocked = null;
        statRate = null;
        netTitle = null;
        netDetail = null;
        netHint = null;
        recentBox = null;
        recentSignature = "";
        piholeTitle = null;
        piholeSub = null;
        piholeQ = null;
        piholeB = null;
        piholePct = null;
        piholeGravity = null;
        piholeError = null;
        listCount = null;
        listUpdated = null;
    }

    private LinearLayout homeScreen() {
        LinearLayout col = Ui.column(this);

        ShieldView logo = new ShieldView(this);
        logo.setOn(true);
        logo.setLayoutParams(Ui.lp(Ui.dp(this, isTv ? 42 : 36),
                Ui.dp(this, isTv ? 42 : 36)));
        LinearLayout brand = Ui.column(this);
        TextView name = Ui.text(this, isTv ? "HOLE SHIELD TV" : "HOLE SHIELD",
                isTv ? 19 : 17, Ui.TEXT, true);
        name.setLetterSpacing(0.10f);
        brand.addView(name);
        TextView tag = Ui.caption(this, "SYSTEM-WIDE DNS FILTER");
        tag.setTextSize(9.5f);
        brand.addView(tag);

        TextView settings = Ui.button(this, "Settings", false);
        settings.setMinWidth(Ui.dp(this, isTv ? 120 : 0));
        settings.setOnClickListener(v -> show(Screen.SETTINGS));
        chip = Ui.chip(this);

        if (isTv) {
            LinearLayout header = Ui.row(this);
            header.addView(logo);
            header.addView(brand, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, 14), 0, 0, 0));
            header.addView(new View(this), Ui.lp(0, 1, 1f));
            header.addView(chip);
            header.addView(settings, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, 16), 0, 0, 0));
            add(col, header, 0);
        } else {
            LinearLayout header = Ui.column(this);
            LinearLayout top = Ui.row(this);
            top.addView(logo);
            top.addView(brand, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, 10), 0, 0, 0));
            top.addView(new View(this), Ui.lp(0, 1, 1f));
            top.addView(settings);
            header.addView(top);
            header.addView(chip, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 10), 0, 0));
            add(col, header, 0);
        }

        // Hero
        LinearLayout hero = isTv ? Ui.row(this) : Ui.column(this);
        if (!isTv) hero.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable heroBg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{
                        Ui.mix(Ui.BG, Ui.PRIMARY_CONTAINER, 0.55f),
                        Ui.mix(Ui.SURFACE_CONTAINER, Ui.BG, 0.35f),
                        Ui.mix(Ui.BG, Ui.TERTIARY_CONTAINER, 0.30f)
                });
        heroBg.setCornerRadius(Ui.dp(this, isTv ? 32 : 24));
        heroBg.setStroke(Ui.dp(this, 1), Ui.withAlpha(Ui.PRIMARY, 0x55));
        hero.setBackground(heroBg);
        int heroPad = isTv ? 34 : 20;
        hero.setPadding(Ui.dp(this, heroPad), Ui.dp(this, isTv ? 28 : 18),
                Ui.dp(this, heroPad), Ui.dp(this, isTv ? 28 : 18));

        shield = new ShieldView(this);
        int shieldSize = isTv ? 152 : 88;
        shield.setLayoutParams(Ui.lp(Ui.dp(this, shieldSize), Ui.dp(this, shieldSize)));
        hero.addView(shield);

        LinearLayout heroText = Ui.column(this);
        heroText.addView(Ui.caption(this, "PROTECTION"));
        heroTitle = Ui.text(this, "", isTv ? 32 : 26, Ui.TEXT, true);
        heroTitle.setLetterSpacing(-0.01f);
        heroText.addView(heroTitle, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 6), 0, 0));
        heroSub = Ui.text(this, "", isTv ? 14 : 13, Ui.DIM, false);
        heroSub.setLineSpacing(Ui.dp(this, isTv ? 4 : 3), 1f);
        if (isTv) heroSub.setMaxWidth(Ui.dp(this, 760));
        heroText.addView(heroSub, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 8), 0, 0));

        LinearLayout heroButtons = isTv ? Ui.row(this) : Ui.column(this);
        TextView primary = Ui.button(this, "", true);
        primaryLabel = primary;
        primary.setOnClickListener(v -> toggleProtection());
        TextView setup = Ui.button(this, "Open server", false);
        setup.setOnClickListener(v -> show(Screen.PIHOLE));
        if (isTv) {
            heroButtons.addView(primary);
            heroButtons.addView(setup, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, 12), 0, 0, 0));
        } else {
            heroButtons.addView(primary, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            heroButtons.addView(setup, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 10), 0, 0));
        }
        heroText.addView(heroButtons, Ui.margins(Ui.lp(isTv
                        ? ViewGroup.LayoutParams.WRAP_CONTENT
                        : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, isTv ? 18 : 14), 0, 0));
        if (isTv) {
            hero.addView(heroText, Ui.margins(Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    Ui.dp(this, 28), 0, 0, 0));
        } else {
            hero.addView(heroText, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 14), 0, 0));
        }
        add(col, hero, isTv ? 22 : 16);
        initialFocus = primary;

        addPrivateDnsWarning(col);

        // Stats row
        LinearLayout stats = Ui.row(this);
        statQueries = statCard(stats, isTv ? "DNS QUERIES" : "QUERIES",
                "today, all clients");
        statBlocked = statCard(stats, "BLOCKED", "by gravity & rules");
        statRate = statCard(stats, "BLOCK RATE", "of all lookups");
        add(col, stats, isTv ? 18 : 14);

        // Pi-hole card
        LinearLayout net = card(22);
        LinearLayout netHead = Ui.row(this);
        netHead.addView(Ui.caption(this, "BUILT-IN FILTER"));
        netHead.addView(new View(this), Ui.lp(0, 1, 1f));
        TextView open = Ui.button(this, "Open", false);
        open.setMinWidth(Ui.dp(this, 96));
        open.setOnClickListener(v -> show(Screen.PIHOLE));
        netHead.addView(open);
        net.addView(netHead);
        netTitle = Ui.text(this, "", isTv ? 22 : 20, Ui.TEXT, true);
        net.addView(netTitle, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 10), 0, 0));
        netDetail = Ui.text(this, "", isTv ? 14 : 13, Ui.DIM, false);
        net.addView(netDetail, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 5), 0, 0));
        netHint = Ui.text(this, "", isTv ? 12 : 11, Ui.FAINT, false);
        net.addView(netHint, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 4), 0, 0));
        add(col, net, isTv ? 18 : 14);

        // Recently blocked
        LinearLayout recent = card(22);
        LinearLayout recentHead = Ui.row(this);
        recentHead.addView(Ui.caption(this, "RECENTLY BLOCKED"));
        recentHead.addView(new View(this), Ui.lp(0, 1, 1f));
        TextView all = Ui.button(this, "All", false);
        all.setMinWidth(Ui.dp(this, 78));
        all.setOnClickListener(v -> show(Screen.LOG));
        recentHead.addView(all);
        recent.addView(recentHead);
        recentBox = Ui.column(this);
        recent.addView(recentBox, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 8), 0, 0));
        add(col, recent, isTv ? 18 : 14);

        TextView footer = Ui.text(this, isTv
                        ? "OK select   ·   ← → move   ·   BACK return   ·   MENU settings"
                        : "Tap a row to change it   ·   BACK returns",
                11, Ui.FAINT, false);
        footer.setGravity(Gravity.CENTER);
        add(col, footer, isTv ? 22 : 18);

        updateHomeLive();
        return col;
    }

    private void addPrivateDnsWarning(LinearLayout col) {
        String mode = null;
        String host = null;
        try {
            mode = Settings.Global.getString(getContentResolver(), "private_dns_mode");
            host = Settings.Global.getString(getContentResolver(), "private_dns_specifier");
        } catch (Exception ignored) { }
        if (!"hostname".equals(mode)) return;

        LinearLayout warn = Ui.row(this);
        warn.setBackground(Ui.rounded(this, Ui.TERTIARY_CONTAINER, 24, 1,
                Ui.withAlpha(Ui.TERTIARY, 0x66)));
        int warnPad = isTv ? 24 : 16;
        warn.setPadding(Ui.dp(this, warnPad), Ui.dp(this, isTv ? 18 : 14),
                Ui.dp(this, warnPad), Ui.dp(this, isTv ? 18 : 14));

        LinearLayout text = Ui.column(this);
        TextView title = Ui.caption(this, "PRIVATE DNS IS ON");
        title.setTextColor(Ui.ON_TERTIARY_CONTAINER);
        text.addView(title);
        String name = host == null || host.trim().isEmpty()
                ? "a private DNS hostname" : host.trim();
        TextView body = Ui.text(this, "This device is set to " + name
                + ". Turn Private DNS off in the device's network settings; otherwise "
                + "DNS filtering may stop working.", 13, Ui.TEXT, false);
        body.setLineSpacing(Ui.dp(this, 3), 1f);
        if (isTv) body.setMaxWidth(Ui.dp(this, 1050));
        text.addView(body, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 6), 0, 0));
        warn.addView(text);

        add(col, warn, 14);
    }

    private TextView statCard(LinearLayout parent, String label, String sub) {
        LinearLayout c = Ui.column(this);
        c.setBackground(Ui.rounded(this, Ui.CARD, isTv ? 24 : 20, 1, Ui.STROKE));
        c.setPadding(Ui.dp(this, isTv ? 24 : 16), Ui.dp(this, isTv ? 20 : 14),
                Ui.dp(this, isTv ? 24 : 16), Ui.dp(this, isTv ? 18 : 12));

        TextView cap = Ui.caption(this, label);
        c.addView(cap);
        TextView value = Ui.text(this, "0", isTv ? 34 : 26, Ui.TEXT, true);
        c.addView(value, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, isTv ? 10 : 7), 0, 0));
        c.addView(Ui.text(this, sub, isTv ? 12 : 11, Ui.FAINT, false));

        LinearLayout.LayoutParams lp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = Ui.dp(this, isTv ? 14 : 8);
        if (parent.getChildCount() == 2) lp.rightMargin = 0;
        parent.addView(c, lp);
        return value;
    }

    private LinearLayout card(int padding) {
        LinearLayout l = Ui.column(this);
        int p = isTv ? padding : Math.min(18, padding);
        l.setBackground(Ui.rounded(this, Ui.CARD, isTv ? 28 : 22, 1, Ui.STROKE));
        l.setPadding(Ui.dp(this, p), Ui.dp(this, isTv ? 22 : 16),
                Ui.dp(this, p), Ui.dp(this, isTv ? 22 : 16));
        return l;
    }

    // ------------------------------------------------------------ live updating

    private void updateLive() {
        if (screen == Screen.HOME) updateHomeLive();
        if (screen == Screen.PIHOLE) updatePiholeLive();
        if (screen == Screen.LISTS) updateListsLive();
    }

    private void pausedChip(String label) {
        Ui.setChip(chip, label, Ui.ON_TERTIARY_CONTAINER,
                Ui.TERTIARY_CONTAINER, Ui.withAlpha(Ui.TERTIARY, 0x66));
    }

    private void updateHomeLive() {
        boolean running = DnsVpnService.running;
        if (shield != null) shield.setOn(running);

        if (primaryLabel != null) {
            primaryLabel.setText(DnsVpnService.starting ? "Starting…"
                    : running ? "Turn off protection" : "Turn on protection");
        }

        if (chip != null) {
            if (running && !prefs.blockingActive()) {
                pausedChip("PAUSED");
            } else if (running) {
                Ui.setChip(chip, "PROTECTED", Ui.ON_PRIMARY_CONTAINER,
                        Ui.PRIMARY_CONTAINER, Ui.withAlpha(Ui.PRIMARY, 0x66));
            } else if (DnsVpnService.starting) {
                pausedChip("STARTING");
            } else if (!prefs.protectionWanted) {
                Ui.setChip(chip, "OFF", Ui.DIM, Ui.SURFACE_CONTAINER_HIGH,
                        Ui.OUTLINE_VARIANT);
            } else if (vpnConsentNeeded) {
                pausedChip("SETUP NEEDED");
            } else {
                pausedChip(DnsVpnService.lastError.isEmpty() ? "STARTING" : "SETUP NEEDED");
            }
        }

        if (heroTitle != null) {
            if (running && !prefs.blockingActive()) {
                heroTitle.setText("Blocking is paused");
            } else if (running) {
                heroTitle.setText("Hole Shield is filtering this device");
            } else if (!prefs.protectionWanted) {
                heroTitle.setText("Protection is off");
            } else if (vpnConsentNeeded) {
                heroTitle.setText("One tap to turn it on");
            } else if (!DnsVpnService.lastError.isEmpty()) {
                heroTitle.setText("Protection needs attention");
            } else {
                heroTitle.setText("Starting protection…");
            }
        }

        if (heroSub != null) {
            if (running && !prefs.blockingActive()) {
                heroSub.setText("DNS still resolves through the embedded upstream, but "
                        + "gravity and custom block rules are off until you resume.");
            } else if (running) {
                heroSub.setText("Every app on this device uses the built-in filter. Gravity, "
                        + "allow/deny rules and the query log run on the device itself.");
            } else if (!prefs.protectionWanted) {
                heroSub.setText("One switch stops ad and tracker DNS lookups for every app, "
                        + "including the home screen. No root needed.");
            } else if (vpnConsentNeeded) {
                heroSub.setText("Android asks for VPN permission once. It is only used to "
                        + "filter DNS on this device; no traffic is forwarded anywhere else.");
            } else if (!DnsVpnService.lastError.isEmpty()) {
                heroSub.setText(DnsVpnService.lastError);
            } else {
                heroSub.setText("Waiting for Android to bring the VPN up…");
            }
        }

        if (statQueries != null) statQueries.setText(Ui.count(Stats.queries()));
        if (statBlocked != null) statBlocked.setText(Ui.count(Stats.blocked()));
        if (statRate != null) statRate.setText(Ui.percent(Stats.rate()));

        if (netTitle != null) {
            if (!running) {
                netTitle.setText("Built-in filter is off");
                netTitle.setTextColor(Ui.DIM);
            } else if (!prefs.blockingActive()) {
                netTitle.setText("Blocking is paused");
                netTitle.setTextColor(Ui.AMBER);
            } else {
                netTitle.setText("Hole Shield is running on this device");
                netTitle.setTextColor(Ui.GREEN);
            }
        }

        if (netDetail != null) {
            if (!running) {
                netDetail.setText("Turn on protection to run the embedded DNS filter, "
                        + "dashboard and query log.");
            } else {
                String url = NetInfo.dashboardUrl(this, prefs);
                netDetail.setText(url.isEmpty() ? "Web dashboard is off"
                        : "Dashboard: " + url);
            }
        }

        if (netHint != null) {
            if (!running) {
                netHint.setText(Ui.count(Blocklist.blockedCount()) + " domains on gravity");
            } else {
                String dns = NetInfo.lanDnsUrl(this, prefs);
                String hint = (dns.isEmpty() ? "LAN DNS off" : "LAN DNS " + dns)
                        + " · " + Ui.count(Blocklist.blockedCount()) + " domains";
                if (!DnsVpnService.webError.isEmpty()) hint += " · web: " + DnsVpnService.webError;
                if (!DnsVpnService.lanDnsError.isEmpty()) hint += " · dns: " + DnsVpnService.lanDnsError;
                netHint.setText(hint);
            }
        }

        updateRecent();
    }

    private void updateRecent() {
        if (recentBox == null) return;
        List<Stats.Blocked> recent = Stats.recent();
        String signature = recent.size() + "|" + (recent.isEmpty() ? "" : recent.get(0).name
                + recent.get(0).at);
        if (signature.equals(recentSignature)) return;
        recentSignature = signature;
        recentBox.removeAllViews();
        if (recent.isEmpty()) {
            TextView empty = Ui.text(this,
                    "Nothing blocked yet. That is either very good or protection is off.",
                    14, Ui.FAINT, false);
            recentBox.addView(empty);
            return;
        }
        int shown = Math.min(6, recent.size());
        for (int i = 0; i < shown; i++) {
            Stats.Blocked b = recent.get(i);
            LinearLayout row = Ui.row(this);
            TextView dot = Ui.text(this, "●", 8, Ui.GREEN, false);
            row.addView(dot);
            TextView name = Ui.text(this, b.name, 14, Ui.TEXT, false);
            name.setSingleLine(true);
            row.addView(name, Ui.margins(Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    Ui.dp(this, 10), 0, 0, 0));
            row.addView(Ui.text(this, new SimpleDateFormat("HH:mm", Locale.US)
                    .format(new Date(b.at)), 11, Ui.FAINT, false));
            recentBox.addView(row, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), 0, i == 0 ? 0 : Ui.dp(this, 8), 0, 0));
        }
    }

    // ------------------------------------------------------------- Pi-hole screen

    private LinearLayout piholeScreen() {
        LinearLayout col = Ui.column(this);
        add(col, subHeader("Server", "Embedded DNS filter · dashboard · query log"), 0);

        LinearLayout status = card(26);
        piholeTitle = Ui.text(this, "Filter is starting…", isTv ? 27 : 24, Ui.TEXT, true);
        status.addView(piholeTitle);
        piholeSub = Ui.text(this, "", isTv ? 13 : 12, Ui.DIM, false);
        status.addView(piholeSub, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 5), 0, 0));
        piholeError = Ui.text(this, "", isTv ? 12 : 11.5f, Ui.AMBER, false);
        piholeError.setLineSpacing(Ui.dp(this, 3), 1f);
        status.addView(piholeError, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 8), 0, 0));
        piholeServer = Ui.text(this, "", isTv ? 13 : 12, Ui.DIM, false);
        piholeServer.setLineSpacing(Ui.dp(this, 4), 1f);
        status.addView(piholeServer, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 10), 0, 0));
        add(col, status, isTv ? 20 : 14);

        if (isTv) {
            LinearLayout stats = Ui.row(this);
            piholeQ = statCard(stats, "QUERIES", "today, all clients");
            piholeB = statCard(stats, "BLOCKED", "gravity & rules");
            piholePct = statCard(stats, "PERCENT", "of all queries");
            piholeGravity = statCard(stats, "ON LISTS", "domains");
            add(col, stats, 18);
        } else {
            LinearLayout stats1 = Ui.row(this);
            piholeQ = statCard(stats1, "QUERIES", "today, all clients");
            piholeB = statCard(stats1, "BLOCKED", "gravity & rules");
            add(col, stats1, 14);
            LinearLayout stats2 = Ui.row(this);
            piholePct = statCard(stats2, "PERCENT", "of all queries");
            piholeGravity = statCard(stats2, "ON LISTS", "domains");
            add(col, stats2, 8);
        }

        TextView pause5 = Ui.button(this, "Pause 5 min", false);
        pause5.setOnClickListener(v -> setPiholeBlocking(false, 300));
        TextView pause30 = Ui.button(this, "Pause 30 min", false);
        pause30.setOnClickListener(v -> setPiholeBlocking(false, 1800));
        TextView pause = Ui.button(this, "Pause", false);
        pause.setOnClickListener(v -> setPiholeBlocking(false, 0));
        TextView resume = Ui.button(this, "Resume", true);
        resume.setOnClickListener(v -> setPiholeBlocking(true, 0));
        TextView update = Ui.button(this, "Update gravity", false);
        update.setOnClickListener(v -> updateBlocklist());
        if (isTv) {
            LinearLayout actions = Ui.row(this);
            actions.addView(pause5);
            actions.addView(pause30, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, 10), 0, 0, 0));
            actions.addView(pause, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, 10), 0, 0, 0));
            actions.addView(resume, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, 10), 0, 0, 0));
            actions.addView(update, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, 10), 0, 0, 0));
            add(col, actions, 18);
        } else {
            LinearLayout actions = Ui.column(this);
            LinearLayout first = Ui.row(this);
            first.addView(pause5);
            first.addView(pause30, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, 8), 0, 0, 0));
            actions.addView(first);
            LinearLayout second = Ui.row(this);
            second.addView(pause);
            second.addView(resume, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, 8), 0, 0, 0));
            actions.addView(second, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 8), 0, 0));
            actions.addView(update, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 8), 0, 0));
            add(col, actions, 14);
        }
        initialFocus = pause5;

        LinearLayout top = card(22);
        top.addView(Ui.caption(this, "TOP BLOCKED DOMAINS"));
        piholeTopBox = Ui.column(this);
        top.addView(piholeTopBox, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 8), 0, 0));
        add(col, top, isTv ? 18 : 14);

        LinearLayout clients = card(22);
        clients.addView(Ui.caption(this, "TOP CLIENTS"));
        piholeClientBox = Ui.column(this);
        clients.addView(piholeClientBox, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 8), 0, 0));
        add(col, clients, isTv ? 18 : 14);

        LinearLayout help = card(22);
        help.addView(Ui.caption(this, "EMBEDDED SERVER"));
        TextView body = Ui.text(this, "This device filters its own DNS through the VPN on port 53. "
                + "The dashboard and API listen on the web port, and other devices can use "
                + "the LAN DNS port. Android does not allow an unrooted app to bind port 53, "
                + "so LAN clients must use the high port shown above. Allow rules always win "
                + "over block rules. Pausing stops gravity and custom blocks for every client "
                + "but keeps DNS resolving.", 13, Ui.DIM, false);
        body.setLineSpacing(Ui.dp(this, isTv ? 4 : 3), 1f);
        help.addView(body, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 10), 0, 0));
        LinearLayout buttons = isTv ? Ui.row(this) : Ui.column(this);
        TextView rules = Ui.button(this, "Add a rule", false);
        rules.setOnClickListener(v -> show(Screen.LISTS));
        TextView log = Ui.button(this, "Query log", false);
        log.setOnClickListener(v -> show(Screen.LOG));
        TextView edit = Ui.button(this, "Settings", false);
        edit.setOnClickListener(v -> show(Screen.SETTINGS));
        if (isTv) {
            buttons.addView(rules);
            buttons.addView(log, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, 10), 0, 0, 0));
            buttons.addView(edit, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, 10), 0, 0, 0));
        } else {
            buttons.addView(rules, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            buttons.addView(log, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 8), 0, 0));
            buttons.addView(edit, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 8), 0, 0));
        }
        help.addView(buttons, Ui.margins(Ui.lp(isTv
                        ? ViewGroup.LayoutParams.WRAP_CONTENT
                        : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 16), 0, 0));
        add(col, help, isTv ? 18 : 14);

        updatePiholeLive();
        return col;
    }

    private void updatePiholeLive() {
        if (piholeTitle == null) return;
        boolean running = DnsVpnService.running;
        boolean paused = running && !prefs.blockingActive();
        if (!running) {
            piholeTitle.setText("Filter is off");
            piholeTitle.setTextColor(Ui.DIM);
        } else if (paused) {
            piholeTitle.setText("Blocking is paused");
            piholeTitle.setTextColor(Ui.AMBER);
        } else {
            piholeTitle.setText("Hole Shield is blocking");
            piholeTitle.setTextColor(Ui.GREEN);
        }

        String url = NetInfo.dashboardUrl(this, prefs);
        String dns = NetInfo.lanDnsUrl(this, prefs);
        String sub;
        if (!running) {
            sub = "Turn on protection from the home screen to start the server.";
        } else {
            sub = (url.isEmpty() ? "Web dashboard off" : url)
                    + (dns.isEmpty() ? " · LAN DNS off" : " · LAN DNS " + dns);
        }
        piholeSub.setText(sub);

        StringBuilder err = new StringBuilder();
        if (!running && !DnsVpnService.lastError.isEmpty()) err.append(DnsVpnService.lastError);
        if (running && paused) {
            long left = prefs.blockingRemainingSeconds();
            err.append("Blocking is off for every client.");
            if (left > 0) err.append(" Resumes in ").append(left).append("s.");
        }
        if (running && !DnsVpnService.webError.isEmpty()) {
            if (err.length() > 0) err.append("\n");
            err.append("Dashboard: ").append(DnsVpnService.webError);
        }
        if (running && !DnsVpnService.lanDnsError.isEmpty()) {
            if (err.length() > 0) err.append("\n");
            err.append("LAN DNS: ").append(DnsVpnService.lanDnsError);
        }
        piholeError.setText(err.toString());

        if (piholeServer != null) {
            String pass = prefs.webPassword == null || prefs.webPassword.isEmpty()
                    ? "not set" : "set";
            piholeServer.setText("Dashboard: " + (url.isEmpty() ? "off" : url)
                    + "\nLAN DNS: " + (dns.isEmpty() ? "off" : dns)
                    + "\nWeb password: " + pass
                    + "\nWeb server: " + (DnsVpnService.webRunning ? "listening" : "off")
                    + "   ·   LAN DNS: " + (DnsVpnService.lanDnsRunning ? "listening" : "off")
                    + "\nPort 53 on the LAN needs root; use " + prefs.lanDnsPort + " instead.");
        }

        if (piholeQ != null) piholeQ.setText(Ui.count(Stats.queries()));
        if (piholeB != null) piholeB.setText(Ui.count(Stats.blocked()));
        if (piholePct != null) piholePct.setText(Ui.percent(Stats.rate()));
        if (piholeGravity != null) piholeGravity.setText(Ui.count(Blocklist.blockedCount()));

        if (piholeTopBox != null) {
            List<Stats.Top> tops = Stats.topDomains(true, 8);
            StringBuilder sig = new StringBuilder();
            for (Stats.Top t : tops) sig.append(t.key).append(t.count).append('|');
            if (!sig.toString().equals(piholeTopSignature)
                    || piholeTopBox.getChildCount() == 0) {
                piholeTopSignature = sig.toString();
                piholeTopBox.removeAllViews();
                if (tops.isEmpty()) {
                    piholeTopBox.addView(Ui.text(this, "No blocked queries yet.",
                            13, Ui.FAINT, false));
                } else {
                    for (int i = 0; i < tops.size(); i++) {
                        Stats.Top t = tops.get(i);
                        LinearLayout row = Ui.row(this);
                        row.addView(Ui.text(this, String.valueOf(i + 1), 11, Ui.FAINT, false));
                        TextView name = Ui.text(this, t.key, 14, Ui.TEXT, false);
                        name.setSingleLine(true);
                        row.addView(name, Ui.margins(Ui.lp(0,
                                ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                                Ui.dp(this, 12), 0, 0, 0));
                        row.addView(Ui.text(this, Ui.count(t.count), 12, Ui.FAINT, false));
                        piholeTopBox.addView(row, Ui.margins(Ui.lp(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT),
                                0, i == 0 ? 0 : Ui.dp(this, 7), 0, 0));
                    }
                }
            }
        }

        if (piholeClientBox != null) {
            List<Stats.Top> tops = Stats.topClients(8);
            StringBuilder sig = new StringBuilder();
            for (Stats.Top t : tops) sig.append(t.key).append(t.count).append('|');
            if (!sig.toString().equals(piholeClientSignature)
                    || piholeClientBox.getChildCount() == 0) {
                piholeClientSignature = sig.toString();
                piholeClientBox.removeAllViews();
                if (tops.isEmpty()) {
                    piholeClientBox.addView(Ui.text(this, "No queries yet.",
                            13, Ui.FAINT, false));
                } else {
                    for (Stats.Top t : tops) {
                        LinearLayout row = Ui.row(this);
                        TextView name = Ui.text(this,
                                DnsEngine.clientName(t.key) + "  ·  " + t.key,
                                13, Ui.TEXT, false);
                        name.setSingleLine(true);
                        row.addView(name, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                        row.addView(Ui.text(this, Ui.count(t.count), 12, Ui.FAINT, false));
                        piholeClientBox.addView(row, Ui.margins(Ui.lp(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT),
                                0, Ui.dp(this, 7), 0, 0));
                    }
                }
            }
        }
    }

    private void setPiholeBlocking(boolean on, int seconds) {
        prefs = Prefs.load(this);
        if (on) prefs.resumeBlocking();
        else prefs.pauseBlocking(seconds);
        prefs.save(this);
        reloadService();
        Ui.toast(this, on ? "Blocking resumed"
                : seconds > 0 ? "Blocking paused for " + (seconds / 60) + " min"
                : "Blocking paused until you resume");
        updatePiholeLive();
    }

    private void maybeFetchPihole(boolean force) {
        if (!prefs.hasPihole()) return;
        long now = System.currentTimeMillis();
        if (!force && now - piholeFetchedAt < 20000L) return;
        if (piholeLoading) return;
        piholeLoading = true;
        piholeFetchedAt = now;
        final Prefs snapshot = prefs;
        final int knownApi = prefs.apiVersion;
        io.execute(() -> {
            final PiholeClient.Summary result = PiholeClient.fetch(snapshot);
            handler.post(() -> {
                pihole = result;
                piholeLoading = false;
                if (result.ok && result.version != knownApi) {
                    prefs.save(this);      // remember v5/v6 so we skip the probe next time
                }
                updateLive();
            });
        });
    }

    // --------------------------------------------------------- blocklist screen

    private LinearLayout listsScreen() {
        LinearLayout col = Ui.column(this);
        add(col, subHeader("Gravity & rules", "Blocklist sources, allow/deny and regex"), 0);

        LinearLayout summary = card(26);
        LinearLayout top = Ui.row(this);
        LinearLayout left = Ui.column(this);
        left.addView(Ui.caption(this, "GRAVITY"));
        listCount = Ui.text(this, Ui.count(Blocklist.blockedCount()) + " domains",
                isTv ? 30 : 26, Ui.TEXT, true);
        left.addView(listCount, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 6), 0, 0));
        listUpdated = Ui.text(this, "updated " + Ui.ago(prefs.lastUpdate),
                isTv ? 12 : 11.5f, Ui.FAINT, false);
        left.addView(listUpdated);
        top.addView(left, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView update = Ui.button(this, "Update now", true);
        update.setOnClickListener(v -> updateBlocklist());
        top.addView(update);
        summary.addView(top);
        add(col, summary, isTv ? 20 : 14);
        initialFocus = update;

        LinearLayout rows = Ui.column(this);
        rows.addView(settingRow("Gravity blocking",
                "Apply downloaded lists and custom rules on this device",
                prefs.localBlocking ? "On" : "Off", () -> {
                    prefs.localBlocking = !prefs.localBlocking;
                    prefs.save(this);
                    reloadService();
                    show(Screen.LISTS);
                }), rowLp(0));

        rows.addView(settingRow("Block subdomains of list entries",
                "Match ads.example.com when example.com is on a list",
                prefs.blockSubdomains ? "On" : "Off", () -> {
                    prefs.blockSubdomains = !prefs.blockSubdomains;
                    prefs.save(this);
                    Blocklist.setBlockSubdomains(prefs.blockSubdomains);
                    show(Screen.LISTS);
                }), rowLp(1));

        int customBlocked = countLines(prefs.customBlocked);
        int customAllowed = countLines(prefs.customAllowed);
        rows.addView(settingRow("Add a custom rule", "Exact, wildcard or regex; allow or deny",
                (customBlocked + customAllowed) + " custom", this::addCustomRule), rowLp(2));
        rows.addView(settingRow("Block CNAME targets",
                "Also inspect CNAMEs in upstream answers",
                prefs.blockCname ? "On" : "Off", () -> {
                    prefs.blockCname = !prefs.blockCname;
                    prefs.save(this);
                    show(Screen.LISTS);
                }), rowLp(3));
        rows.addView(settingRow("Clear custom rules", "Remove every custom allow/deny entry",
                "", this::clearCustom), rowLp(4));
        rows.addView(settingRow("Blocklist sources",
                "One URL per line; hosts or adblock format",
                listUrlCount(prefs.blocklistUrls) + " lists",
                this::editListUrls), rowLp(5));
        rows.addView(settingRow("Add maximum blocklists",
                "Append the comprehensive ad, tracker and TV-telemetry preset",
                Blocklists.hasAll(prefs.blocklistUrls) ? "Active" : "Add all",
                this::addMaximumLists), rowLp(6));
        add(col, rows, 0);

        LinearLayout note = card(22);
        note.addView(Ui.caption(this, "WHAT DNS CAN AND CANNOT DO"));
        TextView body = Ui.text(this, "The maximum preset is aggressive; if a service breaks, "
                + "add an exact allow rule. DNS filtering removes most banner, pre-roll and "
                + "tracker requests. It cannot remove ads served from the same domain as the "
                + "video, such as YouTube in-stream ads. It also cannot hide the holes left by "
                + "a blocked request. The downloaded list is stored on the device; nothing is "
                + "uploaded.", 13, Ui.DIM, false);
        body.setLineSpacing(Ui.dp(this, 4), 1f);
        note.addView(body, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 10), 0, 0));
        add(col, note, 18);

        updateListsLive();
        return col;
    }

    private void updateListsLive() {
        if (listCount != null) listCount.setText(Ui.count(Blocklist.blockedCount()) + " domains");
        if (listUpdated != null) {
            listUpdated.setText(Blocklist.isUpdating() ? "updating…"
                    : "updated " + Ui.ago(prefs.lastUpdate));
        }
    }

    private void updateBlocklist() {
        if (Blocklist.isUpdating()) {
            Ui.toast(this, "An update is already running");
            return;
        }
        Ui.toast(this, "Downloading blocklist…");
        updateListsLive();
        Blocklist.updateAsync(this, (ok, message) -> {
            prefs = Prefs.load(this);
            Ui.toast(this, message);
            if (screen == Screen.LISTS) show(Screen.LISTS);
        });
    }

    private void addMaximumLists() {
        if (Blocklists.hasAll(prefs.blocklistUrls)) {
            Ui.toast(this, "Maximum blocklists are already configured");
            return;
        }
        Ui.builder(this)
                .setTitle("Add maximum blocklists?")
                .setMessage("Adds " + Blocklists.count() + " curated ad, tracker, malware "
                        + "and TV-telemetry lists. Existing custom sources and rules are "
                        + "kept. Downloading the full set can take a few minutes.")
                .setPositiveButton("Add and update", (d, w) -> {
                    prefs.blocklistUrls = Blocklists.mergeMissing(prefs.blocklistUrls);
                    prefs.lastUpdate = 0L;
                    prefs.save(this);
                    show(Screen.LISTS);
                    updateBlocklist();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addCustomRule() {
        String[] items = {
                "Block exact domain",
                "Block wildcard (*.example.com)",
                "Block regex (/pattern/)",
                "Allow exact domain",
                "Allow wildcard (*.example.com)",
                "Allow regex (/pattern/)"
        };
        Ui.builder(this)
                .setTitle("Add a custom rule")
                .setItems(items, (d, which) -> {
                    boolean allow = which >= 3;
                    int kind = which % 3 == 1 ? Blocklist.WILDCARD
                            : which % 3 == 2 ? Blocklist.REGEX : Blocklist.EXACT;
                    promptRule(allow, kind);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptRule(boolean allow, int kind) {
        String title = (allow ? "Allow " : "Block ")
                + (kind == Blocklist.WILDCARD ? "wildcard"
                : kind == Blocklist.REGEX ? "regex" : "domain");
        edit(title, "", false, value -> {
            if (Blocklist.addRule(this, allow, kind, value)) {
                prefs = Prefs.load(this);
                Ui.toast(this, allow ? "Allowed" : "Blocked");
                show(Screen.LISTS);
            } else {
                Ui.toast(this, kind == Blocklist.REGEX
                        ? "That regex is not valid"
                        : "Use a domain like ads.example.com");
            }
        });
    }

    private void editListUrls() {
        editMultiline("Blocklist sources", prefs.blocklistUrls, value -> {
            StringBuilder clean = new StringBuilder();
            for (String line : value.split("\\n")) {
                String url = line.trim();
                if (url.isEmpty() || url.startsWith("#")) continue;
                if (!validUrl(url, true)) {
                    Ui.toast(this, "Not an http(s) URL: " + url);
                    return;
                }
                if (clean.length() > 0) clean.append('\n');
                clean.append(url);
            }
            prefs.blocklistUrls = clean.toString();
            prefs.save(this);
            show(Screen.LISTS);
        });
    }

    private static int listUrlCount(String text) {
        if (text == null) return 0;
        int n = 0;
        for (String line : text.split("\\n")) {
            if (!line.trim().isEmpty() && !line.trim().startsWith("#")) n++;
        }
        return n;
    }

    private void clearCustom() {
        Ui.builder(this)
                .setTitle("Clear custom rules?")
                .setMessage("Your added block and allow entries will be removed. "
                        + "The downloaded list stays.")
                .setPositiveButton("Clear", (d, w) -> {
                    prefs.customBlocked = "";
                    prefs.customAllowed = "";
                    prefs.save(this);
                    Blocklist.clearCustomInMemory();
                    show(Screen.LISTS);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static int countLines(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        int n = 0;
        for (String line : text.split("[\\n,]+")) {
            if (!line.trim().isEmpty()) n++;
        }
        return n;
    }

    // ---------------------------------------------------------------- log screen

    private LinearLayout logScreen() {
        LinearLayout col = Ui.column(this);
        add(col, subHeader("Query log", "Newest first · the last 2,000 lookups stay on the device"), 0);

        LinearLayout actions = Ui.row(this);
        TextView clear = Ui.button(this, "Clear log", false);
        clear.setOnClickListener(v -> {
            Stats.clearLog(this);
            show(Screen.LOG);
        });
        actions.addView(clear);
        add(col, actions, isTv ? 8 : 6);

        List<Stats.Entry> entries = Stats.log(200);
        LinearLayout list = card(22);
        if (entries.isEmpty()) {
            TextView empty = Ui.text(this, "No queries logged yet today. "
                    + "Turn on protection and open an app to generate DNS traffic.",
                    14, Ui.DIM, false);
            empty.setLineSpacing(Ui.dp(this, 4), 1f);
            list.addView(empty);
        } else {
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
            for (int i = 0; i < entries.size(); i++) {
                Stats.Entry e = entries.get(i);
                int color = e.blocked ? Ui.RED : "CACHED".equals(e.status) ? Ui.BLUE
                        : "ERROR".equals(e.status) ? Ui.AMBER : Ui.GREEN;
                if (isTv) {
                    LinearLayout row = Ui.row(this);
                    row.addView(Ui.text(this, fmt.format(new Date(e.at)), 11, Ui.FAINT, false));
                    TextView status = Ui.text(this, e.status, 10, color, true);
                    status.setMinWidth(Ui.dp(this, 86));
                    row.addView(status, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, 10), 0, 0, 0));
                    TextView name = Ui.text(this, e.domain, 14, Ui.TEXT, false);
                    name.setSingleLine(true);
                    row.addView(name, Ui.margins(Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                            Ui.dp(this, 10), 0, 0, 0));
                    row.addView(Ui.text(this, e.typeName(), 10, Ui.FAINT, false));
                    TextView client = Ui.text(this, DnsEngine.clientName(e.client),
                            10, Ui.FAINT, false);
                    client.setMinWidth(Ui.dp(this, 90));
                    client.setGravity(Gravity.END);
                    row.addView(client, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, 12), 0, 0, 0));
                    list.addView(row, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT), 0,
                            i == 0 ? 0 : Ui.dp(this, 8), 0, 0));
                } else {
                    LinearLayout entry = Ui.column(this);
                    LinearLayout meta = Ui.row(this);
                    meta.addView(Ui.text(this, fmt.format(new Date(e.at)),
                            10.5f, Ui.FAINT, false));
                    TextView status = Ui.text(this, e.status, 9.5f, color, true);
                    meta.addView(status, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, 8), 0, 0, 0));
                    meta.addView(Ui.text(this, e.typeName(), 9.5f, Ui.FAINT, false),
                            Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, 8), 0, 0, 0));
                    meta.addView(new View(this), Ui.lp(0, 1, 1f));
                    TextView client = Ui.text(this, DnsEngine.clientName(e.client),
                            9.5f, Ui.FAINT, false);
                    client.setSingleLine(true);
                    client.setMaxWidth(Ui.dp(this, 120));
                    client.setGravity(Gravity.END);
                    meta.addView(client);
                    entry.addView(meta);
                    TextView name = Ui.text(this, e.domain, 14, Ui.TEXT, false);
                    name.setSingleLine(true);
                    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    entry.addView(name, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 4), 0, 0));
                    list.addView(entry, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT), 0,
                            i == 0 ? 0 : Ui.dp(this, 10), 0, 0));
                }
            }
        }
        add(col, list, isTv ? 20 : 14);

        TextView note = Ui.text(this, "Counts reset at midnight. The dashboard keeps the same "
                + "log on its Query log table and exposes it at /api/queries.",
                isTv ? 12 : 11.5f, Ui.FAINT, false);
        add(col, note, isTv ? 16 : 12);
        return col;
    }

    // ----------------------------------------------------------- settings screen

    private LinearLayout settingsScreen() {
        LinearLayout col = Ui.column(this);
        add(col, subHeader("Settings", isTv
                ? "D-pad: up/down moves, OK changes"
                : "Tap a row to change it"), 0);

        col.addView(section("PROTECTION"));
        LinearLayout p = Ui.column(this);
        p.addView(settingRow("Protection", "DNS filtering for every app on this device",
                DnsVpnService.running ? "On" : "Off", () -> {
                    toggleProtection();
                    show(Screen.HOME);
                }), rowLp(0));
        p.addView(settingRow("Start at boot", "Resume after the device restarts",
                prefs.startOnBoot ? "On" : "Off", () -> {
                    prefs.startOnBoot = !prefs.startOnBoot;
                    prefs.save(this);
                    show(Screen.SETTINGS);
                }), rowLp(1));
        p.addView(settingRow("Gravity blocking", "Apply downloaded lists and custom rules on this device",
                prefs.localBlocking ? "On" : "Off", () -> {
                    prefs.localBlocking = !prefs.localBlocking;
                    prefs.save(this);
                    reloadService();
                    show(Screen.SETTINGS);
                }), rowLp(2));
        p.addView(settingRow("Force all DNS through the filter",
                "Capture hard-coded public resolvers (recommended)",
                prefs.forceDns ? "On" : "Off", () -> {
                    prefs.forceDns = !prefs.forceDns;
                    prefs.save(this);
                    restartIfRunning();
                    show(Screen.SETTINGS);
                }), rowLp(3));
        add(col, p, 8);

        col.addView(section("BUILT-IN FILTER"));
        LinearLayout ph = Ui.column(this);
        ph.addView(settingRow("Web dashboard", "Serve the dashboard and API on the LAN",
                prefs.webEnabled ? "On" : "Off", () -> {
                    prefs.webEnabled = !prefs.webEnabled;
                    prefs.save(this);
                    restartIfRunning();
                    show(Screen.SETTINGS);
                }), rowLp(0));
        ph.addView(settingRow("Web dashboard port", "Default 8080; port 80 is privileged",
                String.valueOf(prefs.webPort), () -> edit("Web dashboard port",
                        String.valueOf(prefs.webPort), false, value -> {
                            int port = parsePort(value, -1);
                            if (port < 0) {
                                Ui.toast(this, "Port must be 1–65535");
                                return;
                            }
                            prefs.webPort = port;
                            prefs.save(this);
                            restartIfRunning();
                            show(Screen.SETTINGS);
                        })), rowLp(1));
        ph.addView(settingRow("Web password", "Optional; the dashboard asks for it",
                prefs.webPassword == null || prefs.webPassword.isEmpty() ? "not set" : "set",
                () -> edit("Web password", "", true,
                        "Leave empty to clear the password", value -> {
                    prefs.webPassword = value;
                    prefs.save(this);
                    Ui.toast(this, value.isEmpty() ? "Web password cleared"
                            : "Web password saved");
                    show(Screen.SETTINGS);
                })), rowLp(2));
        ph.addView(settingRow("LAN DNS server", "UDP and TCP listener for other devices",
                prefs.lanDns ? "On" : "Off", () -> {
                    prefs.lanDns = !prefs.lanDns;
                    prefs.save(this);
                    restartIfRunning();
                    show(Screen.SETTINGS);
                }), rowLp(3));
        ph.addView(settingRow("LAN DNS port", "5354 by default; port 53 needs root",
                String.valueOf(prefs.lanDnsPort), () -> edit("LAN DNS port",
                        String.valueOf(prefs.lanDnsPort), false, value -> {
                            int port = parsePort(value, -1);
                            if (port < 0) {
                                Ui.toast(this, "Port must be 1–65535");
                                return;
                            }
                            prefs.lanDnsPort = port;
                            prefs.save(this);
                            restartIfRunning();
                            show(Screen.SETTINGS);
                        })), rowLp(4));
        ph.addView(settingRow("Blocking", "Open the server screen to pause or resume",
                prefs.blockingActive() ? "On" : "Paused", () -> show(Screen.PIHOLE)),
                rowLp(5));
        add(col, ph, 8);

        col.addView(section("UPSTREAM"));
        LinearLayout up = Ui.column(this);
        up.addView(settingRow("Fallback DNS", "Used for allowed and cache-miss lookups",
                fallbackLabel(), () -> {
                    if ("doh".equals(prefs.fallback)) prefs.fallback = "plain";
                    else if ("plain".equals(prefs.fallback)) prefs.fallback = "none";
                    else prefs.fallback = "doh";
                    prefs.save(this);
                    restartIfRunning();
                    show(Screen.SETTINGS);
                }), rowLp(0));
        up.addView(settingRow("Encrypted DNS server", "DoH endpoint for the fallback",
                Prefs.hostOf(prefs.dohUrl), () -> edit("Encrypted DNS URL",
                        prefs.dohUrl, false, value -> {
                            if (!validUrl(value, false)) {
                                Ui.toast(this, "Use an https:// DoH URL");
                                return;
                            }
                            prefs.dohUrl = value;
                            prefs.save(this);
                            restartIfRunning();
                            show(Screen.SETTINGS);
                        })), rowLp(1));
        add(col, up, 8);

        col.addView(section("GRAVITY"));
        LinearLayout bl = Ui.column(this);
        bl.addView(settingRow("Update gravity now", "Download every enabled list and reload",
                Blocklist.isUpdating() ? "working" : Ui.ago(prefs.lastUpdate),
                this::updateBlocklist), rowLp(0));
        bl.addView(settingRow("Blocklist sources", "One hosts/adblock URL per line",
                listUrlCount(prefs.blocklistUrls) + " lists", this::editListUrls), rowLp(1));
        bl.addView(settingRow("Test protection", "Resolve a known ad domain through the VPN",
                "Run", this::testProtection), rowLp(2));
        add(col, bl, 8);

        col.addView(section("SYSTEM"));
        LinearLayout sys = Ui.column(this);
        sys.addView(settingRow("Restart protection", "Apply network changes",
                "Restart", () -> {
                    restartIfRunning();
                    Ui.toast(this, DnsVpnService.running ? "Restarting…" : "Protection is off");
                }), rowLp(0));
        sys.addView(settingRow(isTv ? "About Hole Shield TV" : "About Hole Shield",
                "Version 2.0.0 · Material You · no analytics",
                "", this::about), rowLp(1));
        add(col, sys, 8);

        TextView foot = Ui.text(this, "Changing the web/LAN ports, upstream or capture "
                + "settings restarts the VPN automatically. Port 53 on the LAN needs root; "
                + "the LAN DNS listener uses the high port shown on the server screen.",
                12, Ui.FAINT, false);
        add(col, foot, 18);

        initialFocus = p.getChildAt(0);
        return col;
    }

    private TextView section(String title) {
        TextView t = Ui.caption(this, title);
        t.setTextSize(11);
        t.setPadding(0, Ui.dp(this, isTv ? 20 : 14), 0, Ui.dp(this, isTv ? 8 : 6));
        return t;
    }

    private LinearLayout settingRow(String title, String subtitle, String value, Runnable action) {
        LinearLayout r = Ui.focusableCard(this, isTv ? 20 : 18);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout text = Ui.column(this);
        TextView t = Ui.text(this, title, isTv ? 16 : 15, Ui.TEXT, true);
        text.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = Ui.text(this, subtitle, isTv ? 11.5f : 11, Ui.FAINT, false);
            text.addView(s, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(this, 3), 0, 0));
        }
        r.addView(text, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (value != null && !value.isEmpty()) {
            TextView v = Ui.text(this, value, isTv ? 13 : 12.5f, Ui.GREEN, true);
            v.setSingleLine(true);
            v.setMaxWidth(Ui.dp(this, isTv ? 360 : 200));
            r.addView(v, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, isTv ? 16 : 10), 0, 0, 0));
        }
        TextView arrow = Ui.text(this, "›", isTv ? 22 : 20, Ui.FAINT, false);
        r.addView(arrow, Ui.margins(Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT), Ui.dp(this, isTv ? 10 : 6), 0, 0, 0));
        r.setOnClickListener(v -> action.run());
        return r;
    }

    private LinearLayout.LayoutParams rowLp(int index) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, index == 0 ? 0 : isTv ? 8 : 6);
        return lp;
    }

    private LinearLayout subHeader(String title, String subtitle) {
        LinearLayout head = Ui.row(this);
        TextView back = Ui.button(this, "‹ Back", false);
        back.setMinWidth(Ui.dp(this, isTv ? 104 : 80));
        back.setOnClickListener(v -> show(Screen.HOME));
        head.addView(back);
        LinearLayout text = Ui.column(this);
        TextView t = Ui.text(this, title, isTv ? 28 : 24, Ui.TEXT, true);
        text.addView(t);
        text.addView(Ui.text(this, subtitle, isTv ? 12 : 11.5f, Ui.FAINT, false));
        head.addView(text, Ui.margins(Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                Ui.dp(this, isTv ? 20 : 12), 0, 0, 0));
        return head;
    }

    // ------------------------------------------------------------------ actions

    private void toggleProtection() {
        prefs = Prefs.load(this);
        if (DnsVpnService.starting) {
            Ui.toast(this, "The VPN is already starting");
            return;
        }
        if (DnsVpnService.running) {
            prefs.protectionWanted = false;
            prefs.save(this);
            // stopService() cannot stop a VpnService while the system holds
            // its BIND_VPN_SERVICE binding, so ask the service to close the
            // tunnel itself.
            Intent stop = new Intent(this, DnsVpnService.class);
            stop.setAction(DnsVpnService.ACTION_STOP);
            startService(stop);
            handler.postDelayed(this::updateLive, 400);
        } else {
            if (!prefs.vpnDisclosureAccepted) {
                showVpnDisclosure();
                return;
            }
            prefs.protectionWanted = true;
            prefs.save(this);
            startProtection(true);
            handler.postDelayed(this::updateLive, 600);
        }
    }

    private void showVpnDisclosure() {
        Ui.builder(this)
                .setTitle("VPN disclosure")
                .setMessage("Hole Shield uses Android's VpnService to run a local, "
                        + "DNS-only VPN on this device. It does not connect to a remote "
                        + "server and does not route your browsing, video or app traffic."
                        + "\n\nDNS queries are checked on this device against downloaded "
                        + "blocklists. A local query log (domain, time and client) is kept "
                        + "on this device for the dashboard and statistics and is never "
                        + "uploaded. If you enable LAN DNS or the web dashboard, other "
                        + "devices on your network can query this device."
                        + "\n\nYou can turn protection off at any time. Tap Agree to "
                        + "continue to Android's VPN permission prompt.")
                .setPositiveButton("Agree and continue", (d, w) -> {
                    prefs = Prefs.load(this);
                    prefs.vpnDisclosureAccepted = true;
                    prefs.protectionWanted = true;
                    prefs.save(this);
                    startProtection(true);
                })
                .setNegativeButton("Cancel", (d, w) -> {
                    prefs = Prefs.load(this);
                    prefs.protectionWanted = false;
                    prefs.save(this);
                })
                .show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATION);
        }
    }

    private void startProtection(boolean ask) {
        Intent prepare = VpnService.prepare(this);
        vpnConsentNeeded = prepare != null;
        if (prepare != null) {
            if (ask) {
                startActivityForResult(prepare, REQ_VPN);
            } else {
                Ui.toast(this, "VPN permission is needed");
            }
            return;
        }
        startForegroundService(new Intent(this, DnsVpnService.class));
    }

    private void reloadService() {
        if (!DnsVpnService.running) return;
        Intent i = new Intent(this, DnsVpnService.class);
        i.setAction(DnsVpnService.ACTION_RELOAD);
        startService(i);
    }

    private void restartIfRunning() {
        if (!DnsVpnService.running) return;
        prefs.protectionWanted = true;
        prefs.save(this);
        Intent i = new Intent(this, DnsVpnService.class);
        i.setAction(DnsVpnService.ACTION_RESTART);
        startService(i);
    }

    private String fallbackLabel() {
        if ("doh".equals(prefs.fallback)) return "Encrypted (DoH)";
        if ("plain".equals(prefs.fallback)) return "Plain DNS";
        return "Off";
    }

    private boolean validUrl(String value, boolean allowHttp) {
        try {
            URL u = new URL(value.trim());
            if (allowHttp) {
                return u.getProtocol().equals("http") || u.getProtocol().equals("https");
            }
            return u.getProtocol().equals("https") && u.getHost().contains(".");
        } catch (Exception e) {
            return false;
        }
    }

    // ----------------------------------------------------- Pi-hole discovery

    private void discoverPihole() {
        if (discovering) {
            Ui.toast(this, "Already scanning…");
            return;
        }
        discovering = true;
        Ui.toast(this, "Looking for Pi-hole on the local network…");
        io.execute(() -> {
            LinkedHashSet<String> candidates = new LinkedHashSet<>();
            candidates.add("pi.hole");
            candidates.add("pihole.local");
            candidates.add("pi.local");
            candidates.add("raspberrypi.local");
            try {
                ConnectivityManager cm = getSystemService(ConnectivityManager.class);
                if (cm != null) {
                    // Skip the VPN itself: it reports our own DNS server and no
                    // gateway, while the LAN we want is the underlying network.
                    for (Network network : cm.getAllNetworks()) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                            continue;
                        }
                        LinkProperties lp = cm.getLinkProperties(network);
                        if (lp == null) continue;
                        for (RouteInfo route : lp.getRoutes()) {
                            if (route.isDefaultRoute()) {
                                addCandidate(candidates, route.getGateway());
                            }
                        }
                        for (InetAddress dns : lp.getDnsServers()) {
                            addCandidate(candidates, dns);
                        }
                    }
                }
            } catch (Exception ignored) { }

            List<String> hosts = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (String host : candidates) {
                if (host == null || host.isEmpty() || "127.0.0.1".equals(host)
                        || "::1".equals(host)) {
                    continue;
                }
                int version = probePihole(host);
                if (version > 0) {
                    hosts.add(host);
                    labels.add(host + "   ·   API v" + version);
                }
            }
            handler.post(() -> {
                discovering = false;
                showDiscovered(hosts, labels);
            });
        });
    }

    private static void addCandidate(LinkedHashSet<String> out, InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return;
        }
        String host = cleanAddress(address.getHostAddress());
        if (!host.isEmpty()) out.add(host);
    }

    private static String cleanAddress(String address) {
        if (address == null) return "";
        int zone = address.indexOf('%');
        if (zone > 0) address = address.substring(0, zone);
        if (address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1);
        }
        return address;
    }

    /** Brackets a bare IPv6 literal so it can be used in a URL. */
    private static String hostForUrl(String host) {
        if (host == null) return "";
        String h = host.trim();
        if (h.startsWith("[") || h.indexOf(':') < 0) return h;
        return "[" + h + "]";
    }

    private static int parsePort(String text, int fallback) {
        try {
            int port = Integer.parseInt(text.trim());
            return port >= 1 && port <= 65535 ? port : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /** @return 6, 5 or 0 (not a Pi-hole / no answer). */
    private static int probePihole(String host) {
        String base = "http://" + hostForUrl(host);
        try {
            HttpURLConnection c = (HttpURLConnection)
                    new URL(base + "/api/auth").openConnection();
            c.setConnectTimeout(1300);
            c.setReadTimeout(1300);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code == 401 || code == 403 || code == 405) {
                c.disconnect();
                return 6;
            }
            if (code == 200) {
                String body = readSmall(c, 4096);
                c.disconnect();
                if (body.contains("session") || body.contains("\"valid\"")) return 6;
            } else {
                c.disconnect();
            }
        } catch (Exception ignored) { }

        try {
            HttpURLConnection c = (HttpURLConnection)
                    new URL(base + "/admin/api.php?summaryRaw").openConnection();
            c.setConnectTimeout(1300);
            c.setReadTimeout(1300);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code == 200) {
                String body = readSmall(c, 4096);
                c.disconnect();
                if (body.contains("dns_queries_today") || body.toLowerCase(Locale.US)
                        .contains("unauthorized")) {
                    return 5;
                }
            } else {
                c.disconnect();
            }
        } catch (Exception ignored) { }
        return 0;
    }

    private static String readSmall(HttpURLConnection c, int limit) {
        try (java.io.InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < limit) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private void showDiscovered(List<String> hosts, List<String> labels) {
        if (isFinishing() || isDestroyed()) return;
        if (hosts.isEmpty()) {
            Ui.builder(this)
                    .setTitle("No Pi-hole answered")
                    .setMessage("Tried pi.hole, common mDNS names, the gateway and the device's "
                            + "current DNS servers on port 80.\n\nIf Pi-hole uses another port "
                            + "or HTTPS, enter its address manually in Settings.")
                    .setPositiveButton("Enter address", (d, w) -> show(Screen.SETTINGS))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        String[] items = labels.toArray(new String[0]);
        Ui.builder(this)
                .setTitle(hosts.size() == 1 ? "Pi-hole found" : "Choose your Pi-hole")
                .setItems(items, (d, which) -> useDiscovered(hosts.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void useDiscovered(String found) {
        String host = found == null ? "" : found.trim();
        int port = 80;
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            if (close > 0) {
                String rest = host.substring(close + 1);
                host = host.substring(1, close);
                if (rest.startsWith(":")) port = parsePort(rest.substring(1), 80);
            }
        } else {
            int colon = host.indexOf(':');
            if (colon > 0 && host.indexOf(':', colon + 1) < 0) {
                port = parsePort(host.substring(colon + 1), 80);
                host = host.substring(0, colon);
            }
        }
        if (host.isEmpty()) return;
        prefs.piholeHost = host;
        prefs.piholePort = port;
        prefs.usePihole = true;
        prefs.save(this);
        pihole = null;
        piholeFetchedAt = 0;
        restartIfRunning();
        show(Screen.SETTINGS);
        Ui.toast(this, "Using " + host + " for DNS");
    }

    private void edit(String title, String current, boolean secret, Consumer<String> done) {
        edit(title, current, secret, null, done);
    }

    private void edit(String title, String current, boolean secret, String hint,
                      Consumer<String> done) {
        LinearLayout box = Ui.column(this);
        int pad = Ui.dp(this, 22);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(secret ? "" : current);
        input.setSelection(input.getText().length());
        input.setTextSize(16);
        input.setInputType(secret
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        input.setHint(hint != null ? hint
                : secret ? "Leave empty to keep the saved password" : current);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        Ui.styleInput(input);
        box.addView(input, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = Ui.builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String value = input.getText().toString().trim();
                    done.accept(value);
                })
                .setNegativeButton("Cancel", null)
                .create();
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                done.accept(input.getText().toString().trim());
                dialog.dismiss();
                return true;
            }
            return false;
        });
        dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dialog.show();
        Ui.tintDialog(dialog);
        input.requestFocus();
    }

    private void editMultiline(String title, String current, Consumer<String> done) {
        LinearLayout box = Ui.column(this);
        int pad = Ui.dp(this, 22);
        box.setPadding(pad, Ui.dp(this, 8), pad, 0);

        EditText input = new EditText(this);
        input.setSingleLine(false);
        input.setMinLines(5);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setText(current == null ? "" : current);
        input.setTextSize(14);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setHint("https://example.com/hosts");
        Ui.styleInput(input);
        box.addView(input, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                Ui.dp(this, 170)));

        Ui.builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton("Save", (d, w) ->
                        done.accept(input.getText().toString()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void testProtection() {
        Ui.toast(this, "Testing…");
        io.execute(() -> {
            boolean local = Blocklist.isBlocked("doubleclick.net");
            String adResult;
            try {
                InetAddress[] addresses = InetAddress.getAllByName("doubleclick.net");
                boolean sink = false;
                StringBuilder seen = new StringBuilder();
                for (InetAddress a : addresses) {
                    if (seen.length() > 0) seen.append(", ");
                    seen.append(a.getHostAddress());
                    if (a.isAnyLocalAddress() || "0.0.0.0".equals(a.getHostAddress())
                            || "::".equals(a.getHostAddress())) {
                        sink = true;
                    }
                }
                adResult = sink ? "blocked → " + seen
                        : "resolved to " + seen + " (not blocked)";
            } catch (Exception e) {
                adResult = "could not resolve (" + e.getMessage() + ")";
            }

            String normal;
            try {
                InetAddress a = InetAddress.getByName("example.com");
                normal = "resolves to " + a.getHostAddress();
            } catch (Exception e) {
                normal = "failed (" + e.getMessage() + ")";
            }

            String message = "doubleclick.net: " + adResult
                    + "\n\nLocal list: " + (local ? "contains it" : "does not contain it")
                    + "\nProtection: " + (DnsVpnService.running ? "running" : "off")
                    + "\nexample.com: " + normal
                    + "\n\nIf protection is on and the ad domain still resolves, Pi-hole "
                    + "may be allowing it, or an app is using DNS-over-TLS.";
            handler.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                Ui.builder(this)
                        .setTitle("Protection test")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show();
            });
        });
    }

    private void about() {
        String message = (isTv ? "Hole Shield TV 2.0.0" : "Hole Shield 2.0.0") + "\n\n"
                + "A self-contained DNS filter for Android TV and Android "
                + "phones. The app runs the gravity engine, query log, dashboard/API and "
                + "an optional LAN DNS server. This device's own DNS is captured through "
                + "a local, DNS-only VPN.\n\n"
                + "No analytics, no accounts. The only network traffic is DNS, gravity "
                + "downloads, and dashboard requests on your LAN.\n\n"
                + "Limits: Android blocks port 53 for unrooted apps, so other devices must "
                + "use the high LAN DNS port. DNS blocking cannot remove ads served from "
                + "the same domain as the content (YouTube in-stream ads, for example). "
                + "Some apps use DNS-over-TLS and need the capture option enabled.";
        Ui.builder(this)
                .setTitle("About")
                .setMessage(message)
                .setPositiveButton("Close", null)
                .show();
    }

    private void add(LinearLayout parent, View child, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, topMarginDp);
        parent.addView(child, lp);
    }
}
