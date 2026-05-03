package com.prince.turtlekeyboard.integration;

import android.content.Context;
import android.content.pm.PackageManager;

import androidx.annotation.Nullable;

import com.prince.split.SplitStore;
import com.prince.split.kbd.AppProfile;
import com.prince.split.kbd.AppProfileRegistry;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * App profile + enrollment store. Three layers, queried in order:
 *
 * <ol>
 *   <li><b>Seed</b> — fixed list of well-known apps with display names and tags. Used by
 *       integrations (e.g. Split looks up {@code "payment"} tag). Seeded apps that already
 *       light up an integration are auto-enrolled so the user is never asked to add an
 *       app whose value is already on screen.</li>
 *   <li><b>PackageManager</b> — for any package not in the seed, the device-installed
 *       label fills in the display name. No tags. Lets the registry return a usable
 *       profile for arbitrary apps the user encounters.</li>
 *   <li><b>SplitStore (persistent)</b> — per-package enrollment + suppression flags
 *       written by {@link #enroll}/{@link #suppress}.</li>
 * </ol>
 */
public final class PersistentAppProfileRegistry implements AppProfileRegistry {

    private static final String KEY_ENROLLED       = "app.%s.enrolled";
    private static final String KEY_SUPPRESS       = "app.%s.suppressed";
    /** CSV indexes kept in lockstep with the per-pkg flags so the IME and the settings
     *  UI can enumerate without scanning every key. */
    private static final String KEY_ENROLLED_LIST  = "app.enrolled_list";
    private static final String KEY_SUPPRESS_LIST  = "app.suppressed_list";

    private final Context appContext;
    private final SplitStore store;
    private final Map<String, AppProfile> seed;

    public PersistentAppProfileRegistry(Context appContext, SplitStore store) {
        this.appContext = appContext.getApplicationContext();
        this.store = store;
        this.seed = buildSeed();
    }

    private static Map<String, AppProfile> buildSeed() {
        Map<String, AppProfile> m = new LinkedHashMap<>();
        seedEntry(m, "com.google.android.apps.nbu.paisa.user", "GPay",    "payment");
        seedEntry(m, "com.phonepe.app",                        "PhonePe", "payment");
        seedEntry(m, "com.phonepe.app.preprod",                "PhonePe", "payment");
        seedEntry(m, "net.one97.paytm",                        "Paytm",   "payment");
        seedEntry(m, "in.org.npci.upiapp",                     "BHIM",    "payment");
        return Collections.unmodifiableMap(m);
    }

    private static void seedEntry(Map<String, AppProfile> m, String pkg, String name, String... tags) {
        m.put(pkg, new AppProfile(pkg, name, new HashSet<>(Arrays.asList(tags))));
    }

    @Override @Nullable
    public AppProfile get(@Nullable String pkg) {
        if (pkg == null) return null;
        AppProfile s = seed.get(pkg);
        if (s != null) return s;

        // Fall back to whatever PackageManager can resolve. Tagless profile means
        // tag-driven integrations (Split's "payment" check) correctly skip.
        String label = resolveLabel(pkg);
        if (label == null) return null;
        return new AppProfile(pkg, label, Collections.emptySet());
    }

    @Override
    public Status statusFor(@Nullable String pkg) {
        if (pkg == null) return Status.UNKNOWN;
        // Seeded apps with at least one tag are pre-enrolled — Split (or future
        // tag-driven integrations) is already surfacing value, no need to ask.
        AppProfile seedProfile = seed.get(pkg);
        if (seedProfile != null && !seedProfile.tags.isEmpty()) return Status.ENROLLED;

        if (store.getInt(String.format(KEY_SUPPRESS, pkg), 0) == 1) return Status.SUPPRESSED;
        if (store.getInt(String.format(KEY_ENROLLED, pkg), 0) == 1) return Status.ENROLLED;
        return Status.UNKNOWN;
    }

    @Override
    public void enroll(String pkg) {
        if (pkg == null) return;
        store.putInt(String.format(KEY_ENROLLED, pkg), 1);
        Set<String> all = readList(KEY_ENROLLED_LIST);
        if (all.add(pkg)) writeList(KEY_ENROLLED_LIST, all);
    }

    @Override
    public void suppress(String pkg) {
        if (pkg == null) return;
        store.putInt(String.format(KEY_SUPPRESS, pkg), 1);
        Set<String> all = readList(KEY_SUPPRESS_LIST);
        if (all.add(pkg)) writeList(KEY_SUPPRESS_LIST, all);
    }

    @Override
    public void unenroll(String pkg) {
        if (pkg == null) return;
        store.putInt(String.format(KEY_ENROLLED, pkg), 0);
        Set<String> all = readList(KEY_ENROLLED_LIST);
        if (all.remove(pkg)) writeList(KEY_ENROLLED_LIST, all);
    }

    @Override
    public void unsuppress(String pkg) {
        if (pkg == null) return;
        store.putInt(String.format(KEY_SUPPRESS, pkg), 0);
        Set<String> all = readList(KEY_SUPPRESS_LIST);
        if (all.remove(pkg)) writeList(KEY_SUPPRESS_LIST, all);
    }

    @Override
    public Set<String> enrolledPackages() {
        Set<String> seeded = new LinkedHashSet<>();
        // Seeded apps that auto-enroll (currently: payment-tagged) belong here too — the
        // registration replay should treat them just like user-enrolled apps.
        for (Map.Entry<String, AppProfile> e : seed.entrySet()) {
            if (!e.getValue().tags.isEmpty()) seeded.add(e.getKey());
        }
        seeded.addAll(readList(KEY_ENROLLED_LIST));
        return Collections.unmodifiableSet(seeded);
    }

    @Override
    public Set<String> suppressedPackages() {
        return Collections.unmodifiableSet(readList(KEY_SUPPRESS_LIST));
    }

    private Set<String> readList(String key) {
        String csv = store.getString(key, "");
        Set<String> out = new LinkedHashSet<>();
        if (csv == null || csv.isEmpty()) return out;
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private void writeList(String key, Set<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String p : pkgs) {
            if (!first) sb.append(',');
            sb.append(p);
            first = false;
        }
        store.putString(key, sb.toString());
    }

    @Nullable
    private String resolveLabel(String pkg) {
        try {
            PackageManager pm = appContext.getPackageManager();
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            return label == null ? null : label.toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
