package dev.jason.gboardpatches.extension.accesspointcount;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.ImageView;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import dev.jason.gboardpatches.extension.flagsettings.GboardFlagRuntimeContext;

/**
 * Runtime hook that toggles a rounded key-style background on buttons in Gboard's top toolbar,
 * dynamically matching the background color of the active theme's keyboard keys.
 */
public final class GboardToolbarButtonBackgroundRuntime {
    public static final String PREF_KEY_TOOLBAR_BUTTON_BG_ENABLED =
            "pref_toolbar_button_bg_enabled";
    public static final boolean DEFAULT_TOOLBAR_BUTTON_BG_ENABLED = false;

    private static final Map<Window, ViewTreeObserver.OnGlobalLayoutListener> ATTACHED_WINDOWS =
            new WeakHashMap<>();
    private static final Map<View, Drawable> ORIGINAL_BACKGROUNDS = new WeakHashMap<>();
    private static final Map<View, Integer> APPLIED_COLORS = new WeakHashMap<>();

    private GboardToolbarButtonBackgroundRuntime() {
    }

    public static boolean readEnabled(SharedPreferences preferences) {
        if (preferences == null) {
            return DEFAULT_TOOLBAR_BUTTON_BG_ENABLED;
        }
        try {
            Object raw = preferences.getAll().get(PREF_KEY_TOOLBAR_BUTTON_BG_ENABLED);
            if (raw instanceof Boolean value) {
                return value.booleanValue();
            }
            if (raw instanceof String value) {
                if ("true".equalsIgnoreCase(value)) {
                    return true;
                }
                if ("false".equalsIgnoreCase(value)) {
                    return false;
                }
            }
            return DEFAULT_TOOLBAR_BUTTON_BG_ENABLED;
        } catch (Throwable ignored) {
            return DEFAULT_TOOLBAR_BUTTON_BG_ENABLED;
        }
    }

    public static boolean writeEnabled(SharedPreferences preferences, boolean enabled) {
        if (preferences == null) {
            return false;
        }
        try {
            return preferences.edit()
                    .putBoolean(PREF_KEY_TOOLBAR_BUTTON_BG_ENABLED, enabled)
                    .commit();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void onInputViewStarted(Object receiver) {
        try {
            if (!(receiver instanceof InputMethodService service)) {
                return;
            }
            Dialog dialog = service.getWindow();
            Window window = dialog == null ? null : dialog.getWindow();
            if (window == null) {
                return;
            }
            View decor = window.getDecorView();
            if (decor == null) {
                return;
            }
            attachLayoutObserver(window, decor);
            applyToDecor(decor);
        } catch (Throwable ignored) {
            // Never affect showing the IME.
        }
    }

    private static void attachLayoutObserver(Window window, View decor) {
        synchronized (ATTACHED_WINDOWS) {
            if (ATTACHED_WINDOWS.containsKey(window)) {
                return;
            }
            ViewTreeObserver.OnGlobalLayoutListener listener = () -> {
                try {
                    applyToDecor(decor);
                } catch (Throwable ignored) {
                }
            };
            ViewTreeObserver observer = decor.getViewTreeObserver();
            if (observer != null && observer.isAlive()) {
                observer.addOnGlobalLayoutListener(listener);
                ATTACHED_WINDOWS.put(window, listener);
            }
        }
    }

    private static void applyToDecor(View root) {
        SharedPreferences prefs = null;
        try {
            prefs = GboardFlagRuntimeContext.preferencesOrNull();
        } catch (Throwable ignored) {
        }
        boolean enabled = readEnabled(prefs);
        if (!enabled && ORIGINAL_BACKGROUNDS.isEmpty()) {
            return;
        }
        Integer keyColor = enabled ? findKeyBackgroundColor(root, false) : null;
        if (enabled && keyColor == null) {
            return;
        }
        traverseHeader(root, false, enabled, keyColor != null ? keyColor.intValue() : Color.TRANSPARENT);
    }

    private static Integer findKeyBackgroundColor(View view, boolean insideBody) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return null;
        }
        boolean nowInBody = insideBody || isKeyboardBodyContainer(view);
        if (nowInBody && isRegularKeyCandidate(view)) {
            Integer color = extractSolidColor(view.getBackground());
            if (color != null && Color.alpha(color) >= 16) {
                return color;
            }
        }
        if (view instanceof ViewGroup group) {
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                Integer found = findKeyBackgroundColor(group.getChildAt(i), nowInBody);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean isRegularKeyCandidate(View view) {
        if (view.getBackground() == null) {
            return false;
        }
        String tag = tagOf(view);
        if (tag.contains("action") || tag.contains("space") || tag.contains("body")
                || tag.contains("base") || tag.contains("header")) {
            return false;
        }
        if (tag.contains("key")) {
            return true;
        }
        int w = view.getWidth();
        int h = view.getHeight();
        return w > 0 && h > 0 && w <= h * 2 && view.isClickable();
    }

    private static Integer extractSolidColor(Drawable drawable) {
        if (drawable == null) {
            return null;
        }
        if (drawable instanceof ColorDrawable cd) {
            int c = cd.getColor();
            return Color.alpha(c) >= 16 ? c : null;
        }
        if (drawable instanceof GradientDrawable gd) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ColorStateList csl = gd.getColor();
                if (csl != null) {
                    int c = csl.getDefaultColor();
                    if (Color.alpha(c) >= 16) {
                        return c;
                    }
                }
            }
            return null;
        }
        if (drawable instanceof StateListDrawable sld) {
            Drawable current = sld.getCurrent();
            if (current != null && current != drawable) {
                return extractSolidColor(current);
            }
        }
        if (drawable instanceof DrawableWrapper wrapper) {
            Drawable inner = wrapper.getDrawable();
            if (inner != null && inner != drawable) {
                return extractSolidColor(inner);
            }
        }
        if (drawable instanceof LayerDrawable ld) {
            for (int i = ld.getNumberOfLayers() - 1; i >= 0; i--) {
                Integer c = extractSolidColor(ld.getDrawable(i));
                if (c != null) {
                    return c;
                }
            }
        }
        return null;
    }

    private static void traverseHeader(View view, boolean insideHeader, boolean enabled, int keyColor) {
        if (view == null) {
            return;
        }
        boolean nowInHeader = insideHeader || isTopToolbarContainer(view);
        if (nowInHeader && isKeyboardBodyContainer(view)) {
            return;
        }

        if (nowInHeader && isToolbarButtonCandidate(view)) {
            applyBackgroundState(view, enabled, keyColor);
        }

        if (view instanceof ViewGroup group) {
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                traverseHeader(group.getChildAt(i), nowInHeader, enabled, keyColor);
            }
        }
    }

    private static void applyBackgroundState(View view, boolean enabled, int keyColor) {
        if (enabled) {
            synchronized (APPLIED_COLORS) {
                Integer prev = APPLIED_COLORS.get(view);
                if (prev != null && prev.intValue() == keyColor) {
                    return;
                }
            }
            synchronized (ORIGINAL_BACKGROUNDS) {
                if (!ORIGINAL_BACKGROUNDS.containsKey(view)) {
                    ORIGINAL_BACKGROUNDS.put(view, view.getBackground());
                }
            }
            float density = view.getResources().getDisplayMetrics().density;
            int insetPx = Math.max(1, Math.round(3f * density));
            float radiusPx = Math.max(8f * density, view.getHeight() * 0.45f);
            GradientDrawable pill = new GradientDrawable();
            pill.setShape(GradientDrawable.RECTANGLE);
            pill.setColor(keyColor);
            pill.setCornerRadius(radiusPx);
            view.setBackground(new InsetDrawable(pill, insetPx, insetPx, insetPx, insetPx));
            synchronized (APPLIED_COLORS) {
                APPLIED_COLORS.put(view, keyColor);
            }
        } else {
            Drawable original;
            boolean hadOriginal;
            synchronized (ORIGINAL_BACKGROUNDS) {
                hadOriginal = ORIGINAL_BACKGROUNDS.containsKey(view);
                original = ORIGINAL_BACKGROUNDS.remove(view);
            }
            synchronized (APPLIED_COLORS) {
                APPLIED_COLORS.remove(view);
            }
            if (hadOriginal) {
                view.setBackground(original);
            }
        }
    }

    private static boolean isTopToolbarContainer(View view) {
        String tag = tagOf(view);
        if (tag.contains("header") || tag.contains("access-point") || tag.contains("access_point")
                || tag.contains("toolbar") || tag.contains("strip")) {
            return !tag.contains("body");
        }
        String idName = resourceEntryNameOf(view);
        return idName.contains("keyboard_header")
                || idName.contains("access_points_bar")
                || idName.contains("access_point")
                || idName.contains("header_area")
                || idName.contains("suggestion_strip");
    }

    private static boolean isKeyboardBodyContainer(View view) {
        String tag = tagOf(view);
        if (tag.contains("keyboard-body-area")) {
            return true;
        }
        String idName = resourceEntryNameOf(view);
        return idName.contains("keyboard_body");
    }

    private static boolean isToolbarButtonCandidate(View view) {
        String tag = tagOf(view);
        if (tag.contains("keyboard-header-area") || tag.contains("keyboard-base-area")) {
            return false;
        }
        if (tag.contains("access-point") || tag.contains("access_point")
                || tag.contains("key_top") || tag.contains("pill")
                || tag.contains("button")) {
            return true;
        }
        String idName = resourceEntryNameOf(view);
        if (idName.contains("access_point") || idName.contains("key_pos")
                || idName.contains("button") || idName.contains("pill")
                || idName.contains("back") || idName.contains("close")
                || idName.contains("menu")) {
            return true;
        }
        return (view instanceof ImageView || view.isClickable())
                && view.getWidth() > 0 && view.getHeight() > 0
                && view.getWidth() <= view.getHeight() * 3;
    }

    private static String tagOf(View view) {
        Object tag = view.getTag();
        return tag instanceof CharSequence cs ? cs.toString().toLowerCase(Locale.US) : "";
    }

    private static String resourceEntryNameOf(View view) {
        int id = view.getId();
        if (id == View.NO_ID || id <= 0) {
            return "";
        }
        try {
            return view.getResources().getResourceEntryName(id).toLowerCase(Locale.US);
        } catch (Throwable ignored) {
            return "";
        }
    }
}
