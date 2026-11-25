package dev.jason.gboardpatches.extension.accesspointcount;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.inputmethodservice.InputMethodService;
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
 * Runtime hook that toggles (hides/restores) the pill/circle background drawables
 * behind buttons on Gboard's top toolbar (header / access points strip).
 */
public final class GboardToolbarButtonBackgroundRuntime {
    public static final String PREF_KEY_HIDE_TOOLBAR_BUTTON_BG =
            "pref_toolbar_button_bg_hidden";
    public static final boolean DEFAULT_HIDE_TOOLBAR_BUTTON_BG = false;

    private static final Map<Window, ViewTreeObserver.OnGlobalLayoutListener> ATTACHED_WINDOWS =
            new WeakHashMap<>();
    private static final Map<View, Drawable> ORIGINAL_BACKGROUNDS = new WeakHashMap<>();

    private GboardToolbarButtonBackgroundRuntime() {
    }

    public static boolean readHidden(SharedPreferences preferences) {
        if (preferences == null) {
            return DEFAULT_HIDE_TOOLBAR_BUTTON_BG;
        }
        try {
            Object raw = preferences.getAll().get(PREF_KEY_HIDE_TOOLBAR_BUTTON_BG);
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
            return DEFAULT_HIDE_TOOLBAR_BUTTON_BG;
        } catch (Throwable ignored) {
            return DEFAULT_HIDE_TOOLBAR_BUTTON_BG;
        }
    }

    public static boolean writeHidden(SharedPreferences preferences, boolean hidden) {
        if (preferences == null) {
            return false;
        }
        try {
            return preferences.edit()
                    .putBoolean(PREF_KEY_HIDE_TOOLBAR_BUTTON_BG, hidden)
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
        boolean hide = readHidden(prefs);
        if (!hide && ORIGINAL_BACKGROUNDS.isEmpty()) {
            return;
        }
        traverse(root, false, hide);
    }

    private static void traverse(View view, boolean insideHeader, boolean hide) {
        if (view == null) {
            return;
        }
        boolean nowInHeader = insideHeader || isTopToolbarContainer(view);
        if (nowInHeader && isKeyboardBodyContainer(view)) {
            return;
        }

        if (nowInHeader && isToolbarButtonCandidate(view)) {
            applyBackgroundState(view, hide);
        }

        if (view instanceof ViewGroup group) {
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                traverse(group.getChildAt(i), nowInHeader, hide);
            }
        }
    }

    private static void applyBackgroundState(View view, boolean hide) {
        if (hide) {
            Drawable current = view.getBackground();
            if (current != null && !isTransparentColorDrawable(current)) {
                synchronized (ORIGINAL_BACKGROUNDS) {
                    if (!ORIGINAL_BACKGROUNDS.containsKey(view)) {
                        ORIGINAL_BACKGROUNDS.put(view, current);
                    }
                }
                view.setBackgroundColor(Color.TRANSPARENT);
            }
        } else {
            Drawable original;
            synchronized (ORIGINAL_BACKGROUNDS) {
                original = ORIGINAL_BACKGROUNDS.remove(view);
            }
            if (original != null) {
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
        if (view.getBackground() == null) {
            return false;
        }
        String tag = tagOf(view);
        if (tag.contains("keyboard-header-area") || tag.contains("keyboard-base-area")) {
            return false;
        }
        if (tag.contains("access-point") || tag.contains("access_point")
                || tag.contains("key_top") || tag.contains("pill")
                || tag.contains("button") || tag.contains("icon")) {
            return true;
        }
        String idName = resourceEntryNameOf(view);
        if (idName.contains("access_point") || idName.contains("key_pos")
                || idName.contains("icon") || idName.contains("button")
                || idName.contains("pill") || idName.contains("back")
                || idName.contains("close") || idName.contains("menu")) {
            return true;
        }
        return (view instanceof ImageView || view.isClickable())
                && view.getWidth() > 0 && view.getHeight() > 0
                && view.getWidth() <= view.getHeight() * 4;
    }

    private static boolean isTransparentColorDrawable(Drawable drawable) {
        return drawable instanceof ColorDrawable cd && cd.getColor() == Color.TRANSPARENT;
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
