package dev.jason.gboardpatches.extension.accesspoint;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import dev.jason.gboardpatches.extension.settings.GboardPatchesSettingsActivity;

/**
 * Adds a draggable "Quét QR" access-point tile to Gboard's 4-squares drawer (next to Cut / Incognito)
 * that opens the built-in Camera2 QR scanner to permanently pair with AirBridge macOS.
 */
public final class GboardQrPairAccessPointContribution {
    public static final GboardQrPairAccessPointContribution INSTANCE =
            new GboardQrPairAccessPointContribution();
    public static final String TOKEN = "gboard_airbridge_qr_pair";
    public static final String LABEL = "Quét QR";
    public static final String EXTRA_QR_SCANNER = "gboard_qr_scanner";
    private static final String TAG = "GboardQrAccessPoint";

    private static volatile Handles handles;

    private GboardQrPairAccessPointContribution() {
    }

    public Object extendOrderCatalog(Context context, Object original) {
        try {
            if (!(original instanceof Collection<?> stock)) {
                return original;
            }
            List<String> result = new ArrayList<>();
            for (Object value : stock) {
                if (value instanceof String token && !result.contains(token)) {
                    result.add(token);
                }
            }
            if (!result.contains(TOKEN)) {
                result.add(TOKEN);
            }
            Class<?> catalog = Class.forName("wpa", false, original.getClass().getClassLoader());
            Method copy = catalog.getDeclaredMethod("n", Collection.class);
            copy.setAccessible(true);
            return copy.invoke(null, result);
        } catch (Throwable ignored) {
            return original;
        }
    }

    public void register(Object controller, Context context) {
        if (controller == null || context == null) {
            return;
        }
        try {
            Context appContext = context.getApplicationContext();
            Context safeContext = appContext != null ? appContext : context;
            Handles h = prepare(controller.getClass().getClassLoader());
            Object builder = h.factory.invoke(null);
            h.token.invoke(builder, TOKEN);
            Icon icon = createQrIcon();
            if (icon != null) {
                h.iconField.set(builder, icon);
            }
            h.titleField.set(builder, LABEL);
            h.runnable.invoke(builder, (Runnable) () -> launchQrScanner(safeContext));
            Object descriptor = h.build.invoke(builder);
            h.register.invoke(controller, descriptor, false);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register Quét QR access point", t);
        }
    }

    private static void launchQrScanner(Context context) {
        try {
            Intent intent = new Intent(context, GboardPatchesSettingsActivity.class);
            intent.putExtra(EXTRA_QR_SCANNER, true);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to launch QR scanner", t);
        }
    }

    /** Draws a crisp monochrome QR code icon matching Gboard's access-point icon aesthetic. */
    private static Icon createQrIcon() {
        try {
            int size = 96;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            stroke.setColor(Color.BLACK);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(7f);

            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(Color.BLACK);
            fill.setStyle(Paint.Style.FILL);

            // Top-left finder square
            c.drawRect(14, 14, 42, 42, stroke);
            c.drawRect(23, 23, 33, 33, fill);
            // Top-right finder square
            c.drawRect(54, 14, 82, 42, stroke);
            c.drawRect(63, 23, 73, 33, fill);
            // Bottom-left finder square
            c.drawRect(14, 54, 42, 82, stroke);
            c.drawRect(23, 63, 33, 73, fill);
            // Bottom-right data modules
            c.drawRect(54, 54, 65, 65, fill);
            c.drawRect(71, 54, 82, 65, fill);
            c.drawRect(54, 71, 65, 82, fill);
            c.drawRect(71, 71, 82, 82, fill);

            return Icon.createWithBitmap(bmp);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static synchronized Handles prepare(ClassLoader loader) throws ReflectiveOperationException {
        if (handles == null || handles.loader != loader) {
            handles = new Handles(loader);
        }
        return handles;
    }

    private static final class Handles {
        final ClassLoader loader;
        final Method factory, token, runnable, build, register;
        final Field iconField, titleField;

        Handles(ClassLoader loader) throws ReflectiveOperationException {
            this.loader = loader;
            Class<?> descriptor = Class.forName("mtr", false, loader);
            Class<?> builder = Class.forName("mtm", false, loader);
            Class<?> controller = Class.forName("mwv", false, loader);
            factory = method(descriptor, "c", builder, true);
            token = method(builder, "l", void.class, false, String.class);
            runnable = method(builder, "q", void.class, false, Runnable.class);
            build = method(builder, "a", descriptor, false);
            register = method(controller, "g", void.class, false, descriptor, boolean.class);
            iconField = builder.getDeclaredField("c");
            iconField.setAccessible(true);
            titleField = builder.getDeclaredField("d");
            titleField.setAccessible(true);
        }

        private static Method method(Class<?> owner, String name, Class<?> result,
                boolean isStatic, Class<?>... args) throws ReflectiveOperationException {
            Method m = owner.getDeclaredMethod(name, args);
            if (m.getReturnType() != result || Modifier.isStatic(m.getModifiers()) != isStatic) {
                throw new NoSuchMethodException(name);
            }
            m.setAccessible(true);
            return m;
        }
    }
}
