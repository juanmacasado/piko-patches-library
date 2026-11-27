/*
 * Copyright (C) 2026 juanmacasado <https://github.com/juanmacasado/piko-patches-library>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */
package app.morphe.extension.crimera.mount;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.Log;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets extension activities and broadcast receivers run on a mount (root) install.
 *
 * <p>A mount install replaces the APK contents but the package manager keeps the stock manifest,
 * so components a patch adds to the manifest are never registered. Instead of starting them
 * directly, callers pass their explicit intent through {@link #activityIntent} or
 * {@link #receiverIntent}. When the target is registered (standard install) the intent is returned
 * untouched. Otherwise it is pointed at a component the stock manifest does register, and the
 * real class name is carried in an intent category. The patched {@code appComponentFactory}
 * calls {@link #resolveActivity} and {@link #resolveReceiver}, which swap the stock class name for
 * the carried one, so the system starts a registered component while the process instantiates
 * the extension class.
 *
 * <p>A category is used rather than an extra because the factory runs before the intent is ever
 * unparcelled with the app class loader: reading the extras of an arbitrary stock intent there
 * could fail on app-defined parcelables. Categories are plain strings and are also part of
 * {@link Intent#filterEquals}, so distinct targets keep distinct pending intents.
 *
 * <p>Only stock components that are not exported are used as hosts, like the extension
 * components on a standard install, and the factory hooks refuse a carried target that reached
 * an exported component, so other apps cannot reach an extension component through a host.
 */
@SuppressWarnings({"deprecation", "unused"})
public final class MountComponents {
    private static final String TAG = "piko-mount";
    private static final String CATEGORY_PREFIX = "app.morphe.extension.crimera.mount.TARGET:";

    /** Stock theme attributes that make an activity unusable as a general host. */
    private static final int[] THEME_ATTRS = {
            android.R.attr.windowIsTranslucent,
            android.R.attr.windowNoDisplay,
            android.R.attr.windowIsFloating,
    };

    private static final int UNSUITABLE_ACTIVITY_FLAGS = ActivityInfo.FLAG_NO_HISTORY
            | ActivityInfo.FLAG_FINISH_ON_TASK_LAUNCH
            | ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH
            | ActivityInfo.FLAG_ALLOW_TASK_REPARENTING
            | ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS
            | ActivityInfo.FLAG_MULTIPROCESS;

    private static final String EXTENSION_PACKAGE_PREFIX = "app.morphe.extension.";

    private static final Map<String, Boolean> DECLARED = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> PRIVATE_HOSTS = new ConcurrentHashMap<>();
    private static volatile String opaqueActivityStub;
    private static volatile String translucentActivityStub;
    private static volatile String receiverStub;
    private static volatile boolean activityStubsResolved;
    private static volatile boolean receiverStubResolved;

    /**
     * Set the first time the patched factory resolves a component. Without it a redirected intent
     * would start the stock stub itself, so nothing is redirected until the hook is known to run.
     * The first activity of the process always goes through the factory before any extension
     * code can open a screen or post a notification.
     */
    private static volatile boolean factoryHooked;

    private MountComponents() {
    }

    // region Factory hooks

    /**
     * Called first in the patched {@code AppComponentFactory.instantiateActivity}. Must not touch
     * the intent extras: it also runs for every stock activity.
     */
    public static String resolveActivity(String className, @Nullable Intent intent) {
        factoryHooked = true;
        return resolve(className, intent, true);
    }

    /** Called first in the patched {@code AppComponentFactory.instantiateReceiver}. */
    public static String resolveReceiver(String className, @Nullable Intent intent) {
        factoryHooked = true;
        return resolve(className, intent, false);
    }

    /**
     * Swaps in the carried extension class only when the intent reached a host other apps cannot
     * address. Any app may add the category to an intent for an exported stock component, and
     * the extension components are not exported on a standard install, so such an intent keeps
     * the stock class, which ignores the category.
     */
    private static String resolve(String className, @Nullable Intent intent, boolean activity) {
        String target = carriedTarget(intent);
        if (target == null) return className;
        if (!target.startsWith(EXTENSION_PACKAGE_PREFIX) || !isPrivateHost(className, activity)) {
            Log.w(TAG, "Ignoring " + target + " carried to " + className);
            return className;
        }
        return target;
    }

    private static boolean isPrivateHost(String className, boolean activity) {
        String key = (activity ? "a:" : "r:") + className;
        Boolean cached = PRIVATE_HOSTS.get(key);
        if (cached != null) return cached;

        // Components are only instantiated once the application is created, so the context is set.
        Context context = Utils.getContext();
        if (context == null) return false;
        boolean isPrivate;
        try {
            ComponentName host = new ComponentName(context.getPackageName(), className);
            PackageManager pm = context.getPackageManager();
            int flags = PackageManager.MATCH_DISABLED_COMPONENTS;
            ActivityInfo info = activity
                    ? pm.getActivityInfo(host, flags)
                    : pm.getReceiverInfo(host, flags);
            isPrivate = !info.exported;
        } catch (PackageManager.NameNotFoundException | RuntimeException exception) {
            return false;
        }
        PRIVATE_HOSTS.put(key, isPrivate);
        return isPrivate;
    }

    @Nullable
    private static String carriedTarget(@Nullable Intent intent) {
        if (intent == null) return null;
        try {
            Set<String> categories = intent.getCategories();
            if (categories == null) return null;
            for (String category : categories) {
                if (category != null && category.startsWith(CATEGORY_PREFIX)) {
                    return category.substring(CATEGORY_PREFIX.length());
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    // endregion

    // region Intent rewriting

    /**
     * Returns {@code intent} unchanged when its explicit target activity is registered, or a copy
     * that starts a registered stock activity hosting the target.
     *
     * @param translucent whether the target expects a translucent window (a trampoline with no
     *                    UI of its own). The stub is then chosen among translucent stock
     *                    activities when one exists.
     */
    public static Intent activityIntent(Context context, Intent intent, boolean translucent) {
        ComponentName target = intent.getComponent();
        if (context == null || target == null) return intent;
        if (isDeclared(context, target, true)) return intent;
        if (!factoryHooked) {
            Log.w(TAG, target.getClassName() + " is not registered and no component factory hook ran");
            return intent;
        }

        ensureActivityStubs(context);
        String stub = translucent && translucentActivityStub != null
                ? translucentActivityStub
                : opaqueActivityStub;
        if (stub == null) stub = translucentActivityStub;
        if (stub == null) {
            Log.w(TAG, "No stock activity can host " + target.getClassName());
            return intent;
        }
        return redirect(intent, target, stub);
    }

    /** Returns {@code intent} unchanged when its receiver is registered, or a redirected copy. */
    public static Intent receiverIntent(Context context, Intent intent) {
        ComponentName target = intent.getComponent();
        if (context == null || target == null) return intent;
        if (isDeclared(context, target, false)) return intent;
        if (!factoryHooked) {
            Log.w(TAG, target.getClassName() + " is not registered and no component factory hook ran");
            return intent;
        }

        ensureReceiverStub(context);
        if (receiverStub == null) {
            Log.w(TAG, "No stock receiver can host " + target.getClassName());
            return intent;
        }
        return redirect(intent, target, receiverStub);
    }

    /** Whether {@code intent} was redirected to a stock component by this class. */
    public static boolean isRedirected(@Nullable Intent intent) {
        return carriedTarget(intent) != null;
    }

    private static Intent redirect(Intent intent, ComponentName target, String stub) {
        Intent redirected = new Intent(intent);
        redirected.setComponent(new ComponentName(target.getPackageName(), stub));
        redirected.addCategory(CATEGORY_PREFIX + target.getClassName());
        return redirected;
    }

    private static boolean isDeclared(Context context, ComponentName component, boolean activity) {
        String key = (activity ? "a:" : "r:") + component.flattenToString();
        Boolean cached = DECLARED.get(key);
        if (cached != null) return cached;

        boolean declared;
        PackageManager pm = context.getPackageManager();
        int flags = PackageManager.MATCH_DISABLED_COMPONENTS;
        try {
            if (activity) {
                pm.getActivityInfo(component, flags);
            } else {
                pm.getReceiverInfo(component, flags);
            }
            declared = true;
        } catch (PackageManager.NameNotFoundException exception) {
            declared = false;
        } catch (RuntimeException exception) {
            // Assume a standard install rather than redirecting on a transient failure.
            return true;
        }
        DECLARED.put(key, declared);
        return declared;
    }

    // endregion

    // region Stub selection

    private static synchronized void ensureActivityStubs(Context context) {
        if (activityStubsResolved) return;
        opaqueActivityStub = null;
        translucentActivityStub = null;

        PackageManager pm = context.getPackageManager();
        String packageName = context.getPackageName();
        PackageInfo info;
        try {
            info = pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_ACTIVITIES
            );
        } catch (Exception exception) {
            Log.e(TAG, "Could not list stock activities", exception);
            activityStubsResolved = true;
            return;
        }

        List<ActivityInfo> candidates = new ArrayList<>();
        if (info.activities != null) {
            for (ActivityInfo activity : info.activities) {
                if (isUsableActivity(context, pm, activity)) candidates.add(activity);
            }
        }
        // A stable order keeps the same host across launches.
        candidates.sort(Comparator.comparing(a -> a.name));

        Resources resources = context.getResources();
        for (ActivityInfo activity : candidates) {
            boolean[] theme = themeFlags(resources, activity);
            if (theme == null) continue;
            boolean translucent = theme[0];
            boolean noDisplay = theme[1];
            boolean floating = theme[2];
            if (noDisplay || floating) continue;

            if (translucent) {
                if (translucentActivityStub == null) translucentActivityStub = activity.name;
            } else if (opaqueActivityStub == null) {
                opaqueActivityStub = activity.name;
            }
            if (opaqueActivityStub != null && translucentActivityStub != null) break;
        }
        Log.i(TAG, "Activity stubs: opaque=" + opaqueActivityStub
                + " translucent=" + translucentActivityStub);
        activityStubsResolved = true;
    }

    private static boolean isUsableActivity(Context context, PackageManager pm, ActivityInfo activity) {
        if (activity.targetActivity != null) return false; // activity-alias
        // An exported host would let any app start the extension activity hosted in it.
        if (activity.exported) return false;
        if (activity.launchMode != ActivityInfo.LAUNCH_MULTIPLE) return false;
        if (activity.documentLaunchMode != ActivityInfo.DOCUMENT_LAUNCH_NONE) return false;
        if (activity.permission != null) return false;
        if ((activity.flags & UNSUITABLE_ACTIVITY_FLAGS) != 0) return false;
        if (!isMainProcess(activity)) return false;
        if (activity.taskAffinity != null && !activity.taskAffinity.equals(context.getPackageName())) {
            return false;
        }
        switch (activity.screenOrientation) {
            case ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED:
            case ActivityInfo.SCREEN_ORIENTATION_USER:
            case ActivityInfo.SCREEN_ORIENTATION_FULL_USER:
            case ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR:
            case ActivityInfo.SCREEN_ORIENTATION_SENSOR:
                break;
            default:
                return false;
        }
        return isEnabled(pm, activity);
    }

    /** {@code [translucent, noDisplay, floating]}, or null when the theme cannot be resolved. */
    @Nullable
    private static boolean[] themeFlags(Resources resources, ActivityInfo activity) {
        try {
            Resources.Theme theme = resources.newTheme();
            int themeRes = activity.getThemeResource();
            if (themeRes != 0) theme.applyStyle(themeRes, true);
            int[] attrs = THEME_ATTRS.clone();
            // obtainStyledAttributes requires a sorted attribute array.
            Arrays.sort(attrs);
            TypedArray values = theme.obtainStyledAttributes(attrs);
            try {
                boolean[] flags = new boolean[THEME_ATTRS.length];
                for (int i = 0; i < THEME_ATTRS.length; i++) {
                    int index = Arrays.binarySearch(attrs, THEME_ATTRS[i]);
                    flags[i] = values.getBoolean(index, false);
                }
                return flags;
            } finally {
                values.recycle();
            }
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static synchronized void ensureReceiverStub(Context context) {
        if (receiverStubResolved) return;
        receiverStub = null;

        PackageManager pm = context.getPackageManager();
        PackageInfo info;
        try {
            info = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_RECEIVERS);
        } catch (Exception exception) {
            Log.e(TAG, "Could not list stock receivers", exception);
            receiverStubResolved = true;
            return;
        }

        if (info.receivers != null) {
            List<ActivityInfo> receivers = new ArrayList<>(Arrays.asList(info.receivers));
            receivers.sort(Comparator.comparing(r -> r.name));
            for (ActivityInfo receiver : receivers) {
                // The extension receivers are not exported on a standard install. An exported
                // host would let any app send them broadcasts, such as one deleting downloads.
                if (receiver.exported) continue;
                if (receiver.permission != null) continue;
                if (!isMainProcess(receiver)) continue;
                if (!isEnabled(pm, receiver)) continue;
                receiverStub = receiver.name;
                break;
            }
        }
        Log.i(TAG, "Receiver stub: " + receiverStub);
        receiverStubResolved = true;
    }

    private static boolean isMainProcess(ComponentInfo component) {
        String process = component.processName;
        return process == null || process.equals(component.applicationInfo.processName);
    }

    private static boolean isEnabled(PackageManager pm, ComponentInfo component) {
        try {
            int state = pm.getComponentEnabledSetting(
                    new ComponentName(component.packageName, component.name)
            );
            switch (state) {
                case PackageManager.COMPONENT_ENABLED_STATE_ENABLED:
                    return true;
                case PackageManager.COMPONENT_ENABLED_STATE_DEFAULT:
                    return component.enabled && component.applicationInfo.enabled;
                default:
                    return false;
            }
        } catch (RuntimeException exception) {
            return false;
        }
    }

    // endregion
}
