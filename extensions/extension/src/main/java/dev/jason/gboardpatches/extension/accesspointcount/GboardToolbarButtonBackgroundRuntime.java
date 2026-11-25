package dev.jason.gboardpatches.extension.accesspointcount;

import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

import dev.jason.gboardpatches.extension.flagsettings.GboardFlagRuntimeContext;

/**
 * Runtime hook that toggles a circular key-style background on buttons in Gboard's top toolbar,
 * dynamically matching both the color and Frosted Glass opacity of the active theme's letter keys
 * and the exact diameter of Gboard's native mic toolbar circle.
 */
public final class GboardToolbarButtonBackgroundRuntime {
    public static final String PREF_KEY_TOOLBAR_BUTTON_BG_ENABLED =
            "pref_toolbar_button_bg_enabled";
    public static final boolean DEFAULT_TOOLBAR_BUTTON_BG_ENABLED = false;

    private static final Map<Window, ViewTreeObserver.OnGlobalLayoutListener> ATTACHED_WINDOWS =
            new WeakHashMap<>();
    private static final Map<View, Drawable> ORIGINAL_BACKGROUNDS = new WeakHashMap<>();
    private static final Map<View, AppliedStyle> APPLIED_STYLES = new WeakHashMap<>();
    private static final Map<View, Float> ORIGINAL_DIVIDER_ALPHAS = new WeakHashMap<>();
    private static final Map<LinearLayout, Integer> ORIGINAL_SHOW_DIVIDERS = new WeakHashMap<>();
    private static volatile Integer lastKeyColor = null;
    private static volatile int lastRefDiameter = 0;

    private static final class AppliedStyle {
        final int color;
        final int width;
        final int height;
        final int diameter;
        final boolean pill;

        AppliedStyle(int color, int width, int height, int diameter, boolean pill) {
            this.color = color;
            this.width = width;
            this.height = height;
            this.diameter = diameter;
            this.pill = pill;
        }

        boolean matches(int color, int width, int height, int diameter, boolean pill) {
            return this.color == color
                    && this.width == width
                    && this.height == height
                    && this.diameter == diameter
                    && this.pill == pill;
        }
    }

    private static final class NonPaddingInsetDrawable extends InsetDrawable {
        NonPaddingInsetDrawable(Drawable drawable, int insetLeft, int insetTop,
                int insetRight, int insetBottom) {
            super(drawable, insetLeft, insetTop, insetRight, insetBottom);
        }

        @Override
        public boolean getPadding(Rect padding) {
            padding.set(0, 0, 0, 0);
            return false;
        }
    }

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

    /** Called after GboardFrostedGlassRuntime updates key opacity so toolbar circles stay in sync. */
    public static void onThemeOpacityApplied(View decor) {
        if (decor == null) {
            return;
        }
        try {
            applyToDecor(decor);
        } catch (Throwable ignored) {
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
        Integer keyColor = null;
        int refDiameter = 0;
        boolean shouldApply = false;
        if (enabled) {
            keyColor = findLetterKeyColor(root);
            refDiameter = findNativeMicCircleDiameter(root);
            if (keyColor != null) {
                lastKeyColor = keyColor;
                if (refDiameter > 0) {
                    lastRefDiameter = refDiameter;
                }
                shouldApply = true;
            } else if (refDiameter > 0) {
                // Inside a Gboard drawer (menu / emoji / clipboard) where .keytop is hidden
                // but the top toolbar with the mic button is still shown.
                lastRefDiameter = refDiameter;
                keyColor = lastKeyColor != null ? lastKeyColor : findNativeMicCircleColor(root);
                shouldApply = keyColor != null;
            }
            if (refDiameter <= 0) {
                refDiameter = lastRefDiameter;
            }
        }
        traverseHeader(root, false, shouldApply,
                keyColor != null ? keyColor.intValue() : Color.TRANSPARENT, refDiameter);
    }

    private static Integer findNativeMicCircleColor(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (isNativeMicCircleTag(rawTagOf(view))) {
            Integer color = sampleDrawableCenterColor(view, view.getBackground());
            if (color != null && Color.alpha(color) >= 16) {
                return color;
            }
        }
        if (view instanceof ViewGroup group) {
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                Integer found = findNativeMicCircleColor(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Samples the exact rendered RGBA color (including Frosted Glass alpha) of a regular letter key. */
    private static Integer findLetterKeyColor(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return null;
        }
        String rawTag = rawTagOf(view);
        if ((rawTag.equals(".keytop") || rawTag.startsWith(".keytop."))
                && !rawTag.contains("dark")
                && !rawTag.contains("action")
                && !rawTag.contains("candidate")
                && !rawTag.contains("space")) {
            Integer color = sampleDrawableCenterColor(view, view.getBackground());
            if (color != null && Color.alpha(color) >= 16) {
                return color;
            }
        }
        if (view instanceof ViewGroup group) {
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                Integer found = findLetterKeyColor(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Finds the diameter of Gboard's native mic circle so all other toolbar buttons match it. */
    private static int findNativeMicCircleDiameter(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return 0;
        }
        if (isNativeMicCircleTag(rawTagOf(view))) {
            int w = view.getWidth();
            int h = view.getHeight();
            int min = Math.min(w, h);
            if (min > 0) {
                return min;
            }
        }
        if (view instanceof ViewGroup group) {
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                int found = findNativeMicCircleDiameter(group.getChildAt(i));
                if (found > 0) {
                    return found;
                }
            }
        }
        return 0;
    }

    /**
     * Renders a Drawable at its real view dimensions (so InsetDrawable / NinePatchDrawable insets
     * do not clip away the center fill) and reads the center pixel, preserving Frosted Glass alpha.
     */
    private static Integer sampleDrawableCenterColor(View view, Drawable drawable) {
        if (drawable == null) {
            return null;
        }
        Bitmap bitmap = null;
        Rect savedBounds = drawable.copyBounds();
        try {
            int bw = view.getWidth() > 0 ? Math.min(view.getWidth(), 200) : 128;
            int bh = view.getHeight() > 0 ? Math.min(view.getHeight(), 200) : 128;
            if (bw < 64) bw = 64;
            if (bh < 64) bh = 64;
            bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, bw, bh);
            drawable.draw(canvas);
            int pixel = bitmap.getPixel(bw / 2, bh / 2);
            return Color.alpha(pixel) >= 16 ? pixel : null;
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                drawable.setBounds(savedBounds);
            } catch (Throwable ignored) {
            }
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    private static int traverseHeader(View view, boolean insideHeader, boolean enabled,
            int keyColor, int refDiameter) {
        if (view == null || view.getVisibility() != View.VISIBLE) {
            return 0;
        }
        String rawTag = rawTagOf(view);
        if (isKeyboardBodyContainer(view, rawTag)
                || isNativeMicCircleTag(rawTag)) {
            return 0;
        }

        boolean nowInHeader = insideHeader || isTopToolbarContainer(view, rawTag);

        if (nowInHeader) {
            if (view instanceof LinearLayout linearLayout) {
                applyLinearLayoutDividerState(linearLayout, enabled);
            }
            if (isDividerView(view, rawTag)) {
                applyDividerViewState(view, enabled);
                return 0;
            }
            if (isCandidateChipSlot(view, rawTag)) {
                applyBackgroundState(view, enabled, keyColor, refDiameter, true);
                if (view instanceof ViewGroup group) {
                    applyCandidateChildrenCleanup(group, enabled);
                }
                return 1;
            }
            if (isToolbarButtonSlot(view, rawTag)) {
                if (!containsNativeMicCircle(view)) {
                    applyBackgroundState(view, enabled, keyColor, refDiameter, false);
                    return 1;
                }
                // Stop recursing into this button slot so we never double-paint inner ImageViews.
                return 0;
            }
        }

        int total = 0;
        if (view instanceof ViewGroup group) {
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                total += traverseHeader(group.getChildAt(i), nowInHeader, enabled, keyColor, refDiameter);
            }
        }
        return total;
    }

    private static void applyBackgroundState(View view, boolean enabled, int keyColor,
            int refDiameter, boolean asPill) {
        if (enabled) {
            int w = view.getWidth();
            int h = view.getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            float density = view.getResources().getDisplayMetrics().density;
            int maxDiameter = Math.max(1, Math.min(w, h) - Math.round(4f * density));
            int defaultDiameter = Math.round(34f * density);
            int desired = refDiameter > 0 ? refDiameter : defaultDiameter;
            int diameter = Math.min(desired, maxDiameter);

            synchronized (APPLIED_STYLES) {
                AppliedStyle prev = APPLIED_STYLES.get(view);
                if (prev != null && prev.matches(keyColor, w, h, diameter, asPill)) {
                    return;
                }
            }
            synchronized (ORIGINAL_BACKGROUNDS) {
                if (!ORIGINAL_BACKGROUNDS.containsKey(view)) {
                    // Never double-paint buttons that already have a visible native circle
                    // (e.g. the Close X button when the toolbar drawer is open).
                    if (!asPill && sampleDrawableCenterColor(view, view.getBackground()) != null) {
                        return;
                    }
                    ORIGINAL_BACKGROUNDS.put(view, view.getBackground());
                }
            }

            int pL = view.getPaddingLeft();
            int pT = view.getPaddingTop();
            int pR = view.getPaddingRight();
            int pB = view.getPaddingBottom();

            Drawable shapeDrawable;
            int insetX;
            int insetY;
            if (asPill) {
                int pillHeight = Math.min(desired, Math.max(1, h - Math.round(4f * density)));
                insetX = Math.round(3f * density);
                insetY = Math.max(0, (h - pillHeight) / 2);
                GradientDrawable pill = new GradientDrawable();
                pill.setShape(GradientDrawable.RECTANGLE);
                pill.setCornerRadius(8f * density);
                pill.setColor(keyColor);
                shapeDrawable = pill;
            } else {
                insetX = Math.max(0, (w - diameter) / 2);
                insetY = Math.max(0, (h - diameter) / 2);
                GradientDrawable circle = new GradientDrawable();
                circle.setShape(GradientDrawable.OVAL);
                circle.setColor(keyColor);
                shapeDrawable = circle;
            }
            view.setBackground(new NonPaddingInsetDrawable(shapeDrawable, insetX, insetY, insetX, insetY));
            view.setPadding(pL, pT, pR, pB);

            synchronized (APPLIED_STYLES) {
                APPLIED_STYLES.put(view, new AppliedStyle(keyColor, w, h, diameter, asPill));
            }
        } else {
            Drawable original;
            boolean hadOriginal;
            synchronized (ORIGINAL_BACKGROUNDS) {
                hadOriginal = ORIGINAL_BACKGROUNDS.containsKey(view);
                original = ORIGINAL_BACKGROUNDS.remove(view);
            }
            synchronized (APPLIED_STYLES) {
                APPLIED_STYLES.remove(view);
            }
            if (hadOriginal) {
                int pL = view.getPaddingLeft();
                int pT = view.getPaddingTop();
                int pR = view.getPaddingRight();
                int pB = view.getPaddingBottom();
                view.setBackground(original);
                view.setPadding(pL, pT, pR, pB);
            }
        }
    }

    private static void applyCandidateChildrenCleanup(ViewGroup group, boolean enabled) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            String childTag = rawTagOf(child);
            if (isDividerView(child, childTag)) {
                applyDividerViewState(child, enabled);
                continue;
            }
            if (enabled) {
                Drawable bg = child.getBackground();
                if (bg != null) {
                    synchronized (ORIGINAL_BACKGROUNDS) {
                        if (!ORIGINAL_BACKGROUNDS.containsKey(child)) {
                            ORIGINAL_BACKGROUNDS.put(child, bg);
                        }
                    }
                    int pL = child.getPaddingLeft();
                    int pT = child.getPaddingTop();
                    int pR = child.getPaddingRight();
                    int pB = child.getPaddingBottom();
                    child.setBackground(null);
                    child.setPadding(pL, pT, pR, pB);
                }
            } else {
                Drawable orig;
                boolean hadOrig;
                synchronized (ORIGINAL_BACKGROUNDS) {
                    hadOrig = ORIGINAL_BACKGROUNDS.containsKey(child);
                    orig = ORIGINAL_BACKGROUNDS.remove(child);
                }
                if (hadOrig) {
                    int pL = child.getPaddingLeft();
                    int pT = child.getPaddingTop();
                    int pR = child.getPaddingRight();
                    int pB = child.getPaddingBottom();
                    child.setBackground(orig);
                    child.setPadding(pL, pT, pR, pB);
                }
            }
            if (child instanceof ViewGroup nested) {
                applyCandidateChildrenCleanup(nested, enabled);
            }
        }
    }

    private static void applyLinearLayoutDividerState(LinearLayout layout, boolean enabled) {
        if (enabled) {
            int current = layout.getShowDividers();
            if (current != LinearLayout.SHOW_DIVIDER_NONE) {
                synchronized (ORIGINAL_SHOW_DIVIDERS) {
                    if (!ORIGINAL_SHOW_DIVIDERS.containsKey(layout)) {
                        ORIGINAL_SHOW_DIVIDERS.put(layout, current);
                    }
                }
                layout.setShowDividers(LinearLayout.SHOW_DIVIDER_NONE);
            }
        } else {
            Integer orig;
            synchronized (ORIGINAL_SHOW_DIVIDERS) {
                orig = ORIGINAL_SHOW_DIVIDERS.remove(layout);
            }
            if (orig != null) {
                layout.setShowDividers(orig.intValue());
            }
        }
    }

    private static boolean isDividerView(View view, String rawTag) {
        if (view instanceof ViewGroup || view instanceof TextView) {
            return false;
        }
        String idName = resourceEntryNameOf(view);
        if (rawTag.contains("divider") || rawTag.contains("separator")
                || idName.contains("divider") || idName.contains("separator")) {
            return true;
        }
        int w = view.getWidth();
        int h = view.getHeight();
        float density = view.getResources().getDisplayMetrics().density;
        return w > 0 && w <= Math.round(6f * density) && h > w * 2;
    }

    private static void applyDividerViewState(View view, boolean enabled) {
        if (enabled) {
            synchronized (ORIGINAL_DIVIDER_ALPHAS) {
                if (!ORIGINAL_DIVIDER_ALPHAS.containsKey(view)) {
                    ORIGINAL_DIVIDER_ALPHAS.put(view, view.getAlpha());
                }
            }
            if (view.getAlpha() != 0f) {
                view.setAlpha(0f);
            }
        } else {
            Float orig;
            synchronized (ORIGINAL_DIVIDER_ALPHAS) {
                orig = ORIGINAL_DIVIDER_ALPHAS.remove(view);
            }
            if (orig != null) {
                view.setAlpha(orig.floatValue());
            }
        }
    }

    private static boolean isNativeMicCircleTag(String rawTag) {
        return rawTag.equals(".background-icon.for-mic")
                || rawTag.startsWith(".background-icon.for-mic.");
    }

    private static boolean containsNativeMicCircle(View view) {
        if (view == null) {
            return false;
        }
        if (isNativeMicCircleTag(rawTagOf(view))) {
            return true;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsNativeMicCircle(group.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isCandidateChipSlot(View view, String rawTag) {
        int w = view.getWidth();
        int h = view.getHeight();
        if (w <= 0 || h <= 0) {
            return false;
        }
        if (rawTag.contains(".for-candidate-key")) {
            return hasNonEmptyText(view);
        }
        if (view instanceof ViewGroup group) {
            if (countVisibleNonEmptyTextViews(group) == 1 && countVisibleImageViews(group) == 0) {
                // Only match single-suggestion slots, not the whole strip container.
                return w < view.getResources().getDisplayMetrics().widthPixels * 0.65f;
            }
            return false;
        }
        if (view instanceof TextView tv) {
            CharSequence text = tv.getText();
            return text != null && text.length() > 0 && w < view.getResources().getDisplayMetrics().widthPixels * 0.65f;
        }
        return false;
    }

    private static boolean hasNonEmptyText(View view) {
        if (view instanceof TextView tv) {
            CharSequence text = tv.getText();
            return text != null && text.length() > 0;
        }
        if (view instanceof ViewGroup group) {
            return countVisibleNonEmptyTextViews(group) > 0;
        }
        return false;
    }

    private static int countVisibleNonEmptyTextViews(ViewGroup group) {
        int count = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            if (child instanceof TextView tv) {
                CharSequence text = tv.getText();
                if (text != null && text.length() > 0) {
                    count++;
                }
            } else if (child instanceof ViewGroup nested) {
                count += countVisibleNonEmptyTextViews(nested);
            }
            if (count > 1) {
                return count;
            }
        }
        return count;
    }

    private static boolean isTopToolbarContainer(View view, String rawTag) {
        if (rawTag.contains("keyboard-header-area") || rawTag.contains("header")
                || rawTag.startsWith(".candidates-area")) {
            return !rawTag.contains("body");
        }
        String idName = resourceEntryNameOf(view);
        return idName.contains("keyboard_header")
                || idName.contains("access_points_bar")
                || idName.contains("header_area")
                || idName.contains("suggestion_strip");
    }

    private static boolean isKeyboardBodyContainer(View view, String rawTag) {
        if (rawTag.contains("keyboard-body-area")) {
            return true;
        }
        String idName = resourceEntryNameOf(view);
        return idName.contains("keyboard_body");
    }

    /**
     * Matches an individual toolbar button slot (e.g. SoftKeyView wrapping 1 ImageView icon,
     * or a standalone ImageView button) without requiring View.isClickable() since Gboard
     * handles SoftKeyView touches at the parent keyboard view level.
     */
    private static boolean isToolbarButtonSlot(View view, String rawTag) {
        if (rawTag.contains("keyboard-header-area") || rawTag.contains("keyboard-base-area")) {
            return false;
        }
        int w = view.getWidth();
        int h = view.getHeight();
        if (w <= 0 || h <= 0) {
            return false;
        }
        // Reject wide horizontal strips/containers.
        if (w > h * 2.2f) {
            return false;
        }
        if (view instanceof ViewGroup group) {
            return countVisibleImageViews(group) == 1;
        }
        return view instanceof ImageView;
    }

    private static int countVisibleImageViews(ViewGroup group) {
        int count = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            if (child instanceof ImageView) {
                count++;
            } else if (child instanceof ViewGroup nested) {
                count += countVisibleImageViews(nested);
            }
            if (count > 1) {
                return count;
            }
        }
        return count;
    }

    private static String rawTagOf(View view) {
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
