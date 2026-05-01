package com.prince.split;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Known host packages where contextual integrations can light up. */
public final class HostApp {

    public static final class Info {
        public final String pkg;
        public final String displayName;
        Info(String pkg, String displayName) {
            this.pkg = pkg;
            this.displayName = displayName;
        }
    }

    private static final Map<String, Info> PAYMENT;
    static {
        Map<String, Info> m = new LinkedHashMap<>();
        put(m, "com.google.android.apps.nbu.paisa.user", "GPay");
        put(m, "com.phonepe.app",                        "PhonePe");
        put(m, "com.phonepe.app.preprod",                "PhonePe");
        put(m, "net.one97.paytm",                        "Paytm");
        put(m, "in.org.npci.upiapp",                     "BHIM");
        PAYMENT = Collections.unmodifiableMap(m);
    }
    private static void put(Map<String, Info> m, String pkg, String name) {
        m.put(pkg, new Info(pkg, name));
    }

    private HostApp() {}

    @Nullable
    public static Info paymentInfoFor(String pkg) {
        return pkg == null ? null : PAYMENT.get(pkg);
    }

    public static boolean isPayment(String pkg) {
        return paymentInfoFor(pkg) != null;
    }
}
