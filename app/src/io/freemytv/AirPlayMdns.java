package io.freemytv;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;

import io.freemytv.airplay.Advert;

import java.util.Map;

/**
 * Announces the receiver with Bonjour through Android's own mDNS (NsdManager): _airplay._tcp as "Name" and
 * _raop._tcp as "AABBCCDDEEFF@Name", both on the receiver's port, with UxPlay's TXT records. A failed registration
 * is retried 3 times; a rename (another device with the same name) is logged.
 */
final class AirPlayMdns {
    private static final int RETRIES = 3;
    private static final long RETRY_MS = 5_000, ANSWER_MS = 10_000;

    private final NsdManager nsd;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Service airplay, raop;

    AirPlayMdns(Context c, Advert.Identity id, String name, String code, int port) {
        nsd = c.getSystemService(NsdManager.class);
        airplay = new Service(info(name, "_airplay._tcp", port, Advert.airplayTxt(id, code)));
        raop = new Service(info(Advert.raopName(id, name), "_raop._tcp", port, Advert.raopTxt(id, code)));
    }

    private static NsdServiceInfo info(String name, String type, int port, Map<String, String> txt) {
        NsdServiceInfo info = new NsdServiceInfo();
        info.setServiceName(name);
        info.setServiceType(type);
        info.setPort(port);
        for (Map.Entry<String, String> e : txt.entrySet()) info.setAttribute(e.getKey(), e.getValue());
        return info;
    }

    /** Any thread. */
    void start() {
        main.post(() -> {
            airplay.register(0);
            raop.register(0);
        });
    }

    /** Any thread. */
    void stop() {
        main.post(() -> {
            airplay.unregister();
            raop.unregister();
        });
    }

    /** One registration; NsdManager wants a fresh listener each time. Main thread only. */
    private final class Service {
        final NsdServiceInfo info;
        final String baseName;
        NsdManager.RegistrationListener listener;
        boolean wanted, registered;
        int suffix = 1;

        Service(NsdServiceInfo info) {
            this.info = info;
            this.baseName = info.getServiceName();
        }

        void register(final int attempt) {
            wanted = true;
            if (listener != null || nsd == null) return;
            final NsdManager.RegistrationListener l = new NsdManager.RegistrationListener() {
                @Override public void onServiceRegistered(NsdServiceInfo registered) {
                    main.post(() -> Service.this.registered = true);
                    String name = registered.getServiceName();
                    if (name != null && !name.equals(info.getServiceName())) {
                        AppLog.w("airplay: " + info.getServiceType() + " renamed to \"" + name + "\" (the name is taken)");
                    } else {
                        AppLog.w("airplay: " + info.getServiceType() + " registered");
                    }
                }

                @Override public void onRegistrationFailed(NsdServiceInfo failed, int error) {
                    AppLog.w("airplay: " + info.getServiceType() + " registration failed (" + error + ")");
                    // Callbacks come on NsdManager's own thread: state changes happen on the main one.
                    final NsdManager.RegistrationListener self = this;
                    main.post(() -> {
                        if (listener == self) listener = null;
                        if (wanted && attempt < RETRIES) main.postDelayed(() -> { if (wanted) register(attempt + 1); }, RETRY_MS);
                    });
                }

                @Override public void onServiceUnregistered(NsdServiceInfo gone) {
                    AppLog.d("airplay: " + info.getServiceType() + " unregistered");
                }

                @Override public void onUnregistrationFailed(NsdServiceInfo gone, int error) {
                    AppLog.w("airplay: " + info.getServiceType() + " unregistration failed (" + error + ")");
                }
            };
            listener = l;
            registered = false;
            try {
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l);
            } catch (RuntimeException e) {
                listener = null;
                AppLog.w("airplay: " + info.getServiceType() + " not registered: " + e.getMessage());
                return;
            }
            // Android 14's advertiser can drop a registration whose name another service of the TV already uses
            // without ever calling back: no answer in 10 s, try again as "Name (2)", "Name (3)".
            main.postDelayed(() -> {
                if (listener != l || registered || !wanted || suffix >= 3) return;
                suffix++;
                String next = baseName + " (" + suffix + ")";
                AppLog.w("airplay: no answer from mDNS for " + info.getServiceType() + " in 10 s, trying \"" + next + "\"");
                unregister();
                info.setServiceName(next);
                register(attempt);
            }, ANSWER_MS);
        }

        void unregister() {
            wanted = false;
            NsdManager.RegistrationListener l = listener;
            listener = null;
            if (l == null) return;
            try {
                nsd.unregisterService(l);
            } catch (RuntimeException e) {
                AppLog.d("airplay: unregister: " + e.getMessage());
            }
        }
    }
}
