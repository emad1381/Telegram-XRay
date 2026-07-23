package org.telegram.messenger;

import android.content.SharedPreferences;
import android.os.SystemClock;

import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ProxyRotationController implements NotificationCenter.NotificationCenterDelegate {
    private final static ProxyRotationController INSTANCE = new ProxyRotationController();

    public final static int DEFAULT_TIMEOUT_INDEX = 1;
    public final static List<Integer> ROTATION_TIMEOUTS = Arrays.asList(
            5, 10, 15, 30, 60
    );

    private boolean isCurrentlyChecking;
    private int pendingChecks;
    private long checkGeneration;
    private Runnable checkProxyAndSwitchRunnable = () -> {
        isCurrentlyChecking = true;
        final long generation = ++checkGeneration;

        int currentAccount = UserConfig.selectedAccount;
        pendingChecks = 0;
        for (int i = 0; i < SharedConfig.proxyList.size(); i++) {
            SharedConfig.ProxyInfo proxyInfo = SharedConfig.proxyList.get(i);
            if (proxyInfo.checking || SystemClock.elapsedRealtime() - proxyInfo.availableCheckTime < 2 * 60 * 1000) {
                continue;
            }
            pendingChecks++;
            proxyInfo.checking = true;
            if (proxyInfo.proxyType == SharedConfig.ProxyInfo.PROXY_TYPE_XRAY_VLESS) {
                final long start = SystemClock.elapsedRealtime();
                Utilities.globalQueue.postRunnable(() -> {
                    long time = -1;
                    boolean ok = false;
                    java.net.Socket socket = new java.net.Socket();
                    try {
                        socket.connect(new java.net.InetSocketAddress(proxyInfo.address, proxyInfo.port), 2000);
                        ok = true;
                        time = SystemClock.elapsedRealtime() - start;
                    } catch (Exception ignored) {
                    } finally {
                        try {
                            socket.close();
                        } catch (Exception ignored) {
                        }
                    }
                    final long result = ok ? time : -1;
                    AndroidUtilities.runOnUIThread(() -> finishProxyCheck(generation, proxyInfo, result));
                });
            } else {
                proxyInfo.proxyCheckPingId = ConnectionsManager.getInstance(currentAccount).checkProxy(proxyInfo.address, proxyInfo.port, proxyInfo.username, proxyInfo.password, proxyInfo.secret, time -> AndroidUtilities.runOnUIThread(() -> {
                    finishProxyCheck(generation, proxyInfo, time);
                }));
            }
        }

        if (pendingChecks == 0) {
            switchToAvailable();
        }
    };

    private void finishProxyCheck(long generation, SharedConfig.ProxyInfo proxyInfo, long time) {
        proxyInfo.availableCheckTime = SystemClock.elapsedRealtime();
        proxyInfo.checking = false;
        proxyInfo.available = time >= 0;
        proxyInfo.ping = time >= 0 ? time : 0;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, proxyInfo);
        if (generation != checkGeneration || pendingChecks <= 0) {
            return;
        }
        pendingChecks--;
        if (pendingChecks == 0) {
            switchToAvailable();
        }
    }

    public static void init() {
        INSTANCE.initInternal();
    }

    @SuppressWarnings("ComparatorCombinators")
    private void switchToAvailable() {
        isCurrentlyChecking = false;

        if (!SharedConfig.proxyRotationEnabled) {
            return;
        }

        List<SharedConfig.ProxyInfo> sortedList = new ArrayList<>(SharedConfig.proxyList);
        Collections.sort(sortedList, (o1, o2) -> Long.compare(o1.ping, o2.ping));
        SharedConfig.ProxyInfo best = null;
        for (SharedConfig.ProxyInfo info : sortedList) {
            if (!info.checking && info.available && info.ping > 0) {
                best = info;
                break;
            }
        }
        if (best == null) {
            return;
        }

        SharedConfig.ProxyInfo current = SharedConfig.currentProxy;
        boolean currentIsHealthy = current != null && current.available && current.ping > 0;
        // Keep a healthy current proxy unless the measured alternative is
        // materially faster. This prevents constant switching on tiny jitter.
        if (best == current || (currentIsHealthy && best.ping * 100 >= current.ping * 80)) {
            return;
        }

        SharedConfig.ProxyInfo info = best;

        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        editor.putString("proxy_ip", info.address);
        editor.putString("proxy_pass", info.password);
        editor.putString("proxy_user", info.username);
        editor.putInt("proxy_port", info.port);
        editor.putString("proxy_secret", info.secret);
        editor.putBoolean("proxy_enabled", true);

        if (!info.secret.isEmpty()) {
            editor.putBoolean("proxy_enabled_calls", false);
        }
        editor.apply();

        SharedConfig.currentProxy = info;
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyChangedByRotation);
        ConnectionsManager.setProxySettings(true, SharedConfig.currentProxy.address, SharedConfig.currentProxy.port, SharedConfig.currentProxy.username, SharedConfig.currentProxy.password, SharedConfig.currentProxy.secret);
    }

    private void initInternal() {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            NotificationCenter.getInstance(i).addObserver(this, NotificationCenter.didUpdateConnectionState);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxyCheckDone);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxyCheckDone) {
            // finishProxyCheck waits for the complete batch before choosing a
            // proxy; reacting to each individual ping causes oscillation.
            return;
        } else if (id == NotificationCenter.proxySettingsChanged) {
            AndroidUtilities.cancelRunOnUIThread(checkProxyAndSwitchRunnable);
        } else if (id == NotificationCenter.didUpdateConnectionState && account == UserConfig.selectedAccount) {
            if (!SharedConfig.isProxyEnabled() && !SharedConfig.proxyRotationEnabled || SharedConfig.proxyList.size() <= 1) {
                return;
            }

            int state = ConnectionsManager.getInstance(account).getConnectionState();

            if (state == ConnectionsManager.ConnectionStateConnectingToProxy) {
                if (!isCurrentlyChecking) {
                    AndroidUtilities.runOnUIThread(checkProxyAndSwitchRunnable, ROTATION_TIMEOUTS.get(SharedConfig.proxyRotationTimeout) * 1000L);
                }
            } else {
                AndroidUtilities.cancelRunOnUIThread(checkProxyAndSwitchRunnable);
            }
        }
    }
}
