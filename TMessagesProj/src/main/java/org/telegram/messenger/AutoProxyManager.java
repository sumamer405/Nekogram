package org.telegram.messenger;

import android.content.SharedPreferences;
import android.os.SystemClock;
import android.widget.Toast;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AutoProxyManager implements NotificationCenter.NotificationCenterDelegate {

    private static final String TAG = "AutoProxyManager";
    private static final AutoProxyManager INSTANCE = new AutoProxyManager();

    // 20 minutes auto-update interval
    public static final long UPDATE_INTERVAL_MINUTES = 20;

    // Ready MTProto / Proxy Subscription Feeds on GitHub
    public static final List<String> PROXY_SOURCES = Arrays.asList(
            "https://raw.githubusercontent.com/0xRadikal/Free-Telegram-Proxy/main/top100.txt",
            "https://raw.githubusercontent.com/0xRadikal/Free-Telegram-Proxy/main/top50.txt",
            "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/proxy_all.txt",
            "https://raw.githubusercontent.com/Grim1313/mtproto-for-telegram/master/all_proxies.txt"
    );

    // Initial fallback seed proxies (guarantees out-of-the-box connection on first boot)
    private static final String[] SEED_PROXIES = new String[]{
            "tg://proxy?server=212.108.98.24.suser.ir&port=2020&secret=00000000000000000000000000000000",
            "tg://proxy?server=eyiran.suser.ir&port=2020&secret=00000000000000000000000000000000",
            "tg://proxy?server=digitalvpn-proxy.ru&port=6677&secret=73c03865b903152df8e00f8a519ca4a7",
            "tg://proxy?server=my.silvpn.su&port=443&secret=eeea7a1978e167c0842ce31a16749038da6d792e6e657763646e2e7275",
            "tg://proxy?server=89.108.88.184&port=443&secret=ee00d85706e58f9b5ead3b6c8a08d984df636c6f75642e6d61696c2e7275",
            "tg://proxy?server=connect.0xl1r.cfd&port=853&secret=eea1f31ce5039ccb8331e906d5ab00a31777622e7275",
            "tg://proxy?server=mtproto.online&port=443&secret=ee139e0ee36150c1ea3bf299796586b5457777772e7674622e7275",
            "tg://proxy?server=topproxt.asia&port=443&secret=eebbb00c5d6d8a742b1a762499eb7d4912617669746f2e7275",
            "tg://proxy?server=217.144.187.230&port=443&secret=ee68ae8284982465dfcb198342d01cdedb6f7a6f6e2e7275"
    };

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean isUpdating = new AtomicBoolean(false);
    private int connectionStuckCounter = 0;

    public static AutoProxyManager getInstance() {
        return INSTANCE;
    }

    public static void init() {
        INSTANCE.start();
    }

    private void start() {
        // Ensure proxy rotation is enabled by default
        SharedConfig.proxyRotationEnabled = true;

        // Load seed proxies if proxy list is empty
        SharedConfig.loadProxyList();
        if (SharedConfig.proxyList.isEmpty()) {
            loadSeedProxies();
        }

        // Register observers for connection state and proxy check
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxyCheckDone);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);

        // Schedule periodic update: first run after 3s, then every 20 minutes
        scheduler.scheduleWithFixedDelay(this::fetchProxiesFromGitHub, 3, UPDATE_INTERVAL_MINUTES * 60, TimeUnit.SECONDS);

        // Schedule watchdog every 7s to ensure user is connected ("خودکار همیشه وصل بشه تلگرام")
        scheduler.scheduleWithFixedDelay(this::checkConnectionWatchdog, 5, 7, TimeUnit.SECONDS);
    }

    private void loadSeedProxies() {
        boolean added = false;
        for (String uri : SEED_PROXIES) {
            try {
                ProxySettings settings = ProxySettings.parse(uri);
                if (settings != null && settings.isValid()) {
                    SharedConfig.ProxyInfo proxyInfo = new SharedConfig.ProxyInfo(settings);
                    SharedConfig.proxyList.add(proxyInfo);
                    added = true;
                }
            } catch (Throwable ignored) {}
        }
        if (added) {
            SharedConfig.saveProxyList();
        }
    }

    public void updateNow(boolean manual) {
        scheduler.execute(() -> {
            fetchProxiesFromGitHub();
            if (manual) {
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        Toast.makeText(ApplicationLoader.applicationContext, "پروکسی‌ها بروزرسانی شدند / Proxies updated", Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                });
            }
        });
    }

    private void fetchProxiesFromGitHub() {
        if (!isUpdating.compareAndSet(false, true)) {
            return;
        }
        try {
            List<ProxySettings> fetched = new ArrayList<>();
            for (String sourceUrl : PROXY_SOURCES) {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(sourceUrl);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Telegram-Client)");
                    conn.setInstanceFollowRedirects(true);
                    if (conn.getResponseCode() == 200) {
                        try (InputStream is = conn.getInputStream();
                             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                line = line.trim();
                                if (line.isEmpty() || line.startsWith("#")) {
                                    continue;
                                }
                                ProxySettings settings = ProxySettings.parse(line);
                                if (settings != null && settings.isValid()) {
                                    fetched.add(settings);
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (conn != null) {
                        try {
                            conn.disconnect();
                        } catch (Throwable ignored) {}
                    }
                }
            }

            if (!fetched.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> applyFetchedProxies(fetched));
            }
        } finally {
            isUpdating.set(false);
        }
    }

    private void applyFetchedProxies(List<ProxySettings> fetched) {
        SharedConfig.loadProxyList();
        Set<String> existingKeys = new HashSet<>();
        for (SharedConfig.ProxyInfo p : SharedConfig.proxyList) {
            existingKeys.add(p.settings.getAddress() + ":" + p.settings.getPort());
        }

        int addedCount = 0;
        for (ProxySettings settings : fetched) {
            String key = settings.getAddress() + ":" + settings.getPort();
            if (!existingKeys.contains(key)) {
                existingKeys.add(key);
                SharedConfig.ProxyInfo info = new SharedConfig.ProxyInfo(settings);
                SharedConfig.proxyList.add(0, info);
                addedCount++;
            }
        }

        // Cap to top 60 proxies to keep the app fast and responsive
        while (SharedConfig.proxyList.size() > 60) {
            SharedConfig.proxyList.remove(SharedConfig.proxyList.size() - 1);
        }

        SharedConfig.saveProxyList();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);

        // Ping and test all proxies
        checkAllProxies();

        // Ensure auto-connect
        autoConnectBestProxy(false);
    }

    public void checkAllProxies() {
        AndroidUtilities.runOnUIThread(() -> {
            int account = UserConfig.selectedAccount;
            for (int i = 0; i < SharedConfig.proxyList.size(); i++) {
                SharedConfig.ProxyInfo proxyInfo = SharedConfig.proxyList.get(i);
                if (proxyInfo.checking) {
                    continue;
                }
                proxyInfo.checking = true;
                ConnectionsManager.getInstance(account).checkProxy(proxyInfo.settings, time -> AndroidUtilities.runOnUIThread(() -> {
                    proxyInfo.availableCheckTime = SystemClock.elapsedRealtime();
                    proxyInfo.checking = false;
                    if (time == -1) {
                        proxyInfo.available = false;
                        proxyInfo.ping = 0;
                    } else {
                        proxyInfo.ping = time;
                        proxyInfo.available = true;
                    }
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, proxyInfo);
                }));
            }
        });
    }

    private void checkConnectionWatchdog() {
        AndroidUtilities.runOnUIThread(() -> {
            int account = UserConfig.selectedAccount;
            int state = ConnectionsManager.getInstance(account).getConnectionState();

            if (state == ConnectionsManager.ConnectionStateConnected) {
                connectionStuckCounter = 0;
                return;
            }

            connectionStuckCounter++;
            // If stuck in connecting/waiting for > 7-14 seconds or proxy is disabled
            if (connectionStuckCounter >= 2 || !SharedConfig.isProxyEnabled()) {
                autoConnectBestProxy(true);
            }
        });
    }

    private void autoConnectBestProxy(boolean forceSwitch) {
        if (SharedConfig.proxyList.isEmpty()) {
            return;
        }

        SharedConfig.ProxyInfo best = null;
        long minPing = Long.MAX_VALUE;

        // 1. Pick available proxy with lowest ping
        for (SharedConfig.ProxyInfo p : SharedConfig.proxyList) {
            if (p.available && p.ping > 0 && p.ping < minPing) {
                minPing = p.ping;
                best = p;
            }
        }

        // 2. If none checked as available yet, pick first proxy
        if (best == null) {
            best = SharedConfig.proxyList.get(0);
        }

        boolean needSwitch = forceSwitch || SharedConfig.currentProxy == null || !SharedConfig.isProxyEnabled();
        if (needSwitch && best != null) {
            SharedConfig.currentProxy = best;
            SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
            editor.putBoolean("proxy_enabled", true);
            best.settings.toSharedPreferences(editor);
            editor.apply();

            ConnectionsManager.setProxySettings(true, best.settings);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyChangedByRotation);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxyCheckDone) {
            int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
            if (state != ConnectionsManager.ConnectionStateConnected) {
                autoConnectBestProxy(false);
            }
        } else if (id == NotificationCenter.didUpdateConnectionState && account == UserConfig.selectedAccount) {
            int state = ConnectionsManager.getInstance(account).getConnectionState();
            if (state == ConnectionsManager.ConnectionStateConnected) {
                connectionStuckCounter = 0;
            }
        }
    }
}
