package app.nuvio.extension;

import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

/**
 * Support code for the "Material You theme" patch.
 *
 * The patch adds a new enum value MATERIAL to Nuvio's theme enum and calls into this class from the
 * two places that switch over the theme. Everything fails safe: if anything goes wrong the app falls
 * back to an existing palette instead of crashing.
 */
@SuppressWarnings("unused")
public final class MaterialYouTheme {
    private static final String TAG = "NuvioMaterialYou";
    private static final String MATERIAL = "MATERIAL";
    private static final String CUSTOM = "CUSTOM";
    private static final String LABEL = "Material";

    private static volatile int customOrdinal = -1;

    private MaterialYouTheme() {
    }

    // ---------------------------------------------------------------------------------------------
    // Called from the patched code
    // ---------------------------------------------------------------------------------------------

    /**
     * Replaces theme.ordinal() in the theme picker's label switch. MATERIAL reuses the CUSTOM branch
     * so that switch does not hit its "unknown theme" error. The label is corrected by
     * {@link #materialLabel(Object, String)}.
     */
    public static int switchOrdinal(Enum<?> theme) {
        try {
            if (isMaterial(theme)) {
                int custom = customOrdinal(theme);
                if (custom >= 0) return custom;
            }
        } catch (Throwable t) {
            Log.w(TAG, "switchOrdinal failed", t);
        }
        return theme.ordinal();
    }

    /** Replaces the label text of the MATERIAL theme chip (everything else is returned unchanged). */
    public static String materialLabel(Object theme, String label) {
        return isMaterial(theme) ? LABEL : label;
    }

    /**
     * Builds the Material You palette. {@code template} is any existing palette object of the app; it is
     * used to learn the palette class and to reuse the app's own color wrapper class. If anything fails,
     * the template itself is returned so the app keeps working.
     */
    public static Object materialPalette(Object template) {
        try {
            Object palette = buildPalette(template);
            if (palette != null) return palette;
        } catch (Throwable t) {
            Log.w(TAG, "Could not build Material You palette", t);
        }
        return template;
    }

    // ---------------------------------------------------------------------------------------------
    // Theme enum helpers
    // ---------------------------------------------------------------------------------------------

    private static boolean isMaterial(Object theme) {
        return theme instanceof Enum && MATERIAL.equals(((Enum<?>) theme).name());
    }

    private static int customOrdinal(Enum<?> theme) {
        int cached = customOrdinal;
        if (cached >= 0) return cached;
        Object[] constants = theme.getDeclaringClass().getEnumConstants();
        if (constants != null) {
            for (Object constant : constants) {
                if (CUSTOM.equals(((Enum<?>) constant).name())) {
                    customOrdinal = ((Enum<?>) constant).ordinal();
                    return customOrdinal;
                }
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------------
    // Palette construction
    // ---------------------------------------------------------------------------------------------

    // Indexes into the color array.
    private static final int PRIMARY = 0;
    private static final int SECONDARY = 1;
    private static final int ON_PRIMARY = 2;
    private static final int ON_SECONDARY = 3;
    private static final int PRIMARY_CONTAINER = 4;
    private static final int SURFACE = 5;
    private static final int SURFACE_CONTAINER = 6;
    private static final int SURFACE_CONTAINER_HIGH = 7;

    // Used on Android 11 and older (no system dynamic colors). Same values as the upstream app change.
    private static final int[] FALLBACK = {
            0xFFD0BCFF, 0xFFCCC2DC, 0xFF381E72, 0xFF332D41,
            0xFF4F378B, 0xFF141218, 0xFF211F26, 0xFF2B2930,
    };

    private static Object buildPalette(Object template) throws Exception {
        if (template == null) return null;
        Class<?> paletteClass = template.getClass();

        Constructor<?> constructor = findPaletteConstructor(paletteClass);
        if (constructor == null) {
            Log.w(TAG, "Palette constructor not found");
            return null;
        }

        // The accent gradient is a list of the app's own color wrapper objects. Learn that wrapper
        // class from the template palette's own gradient list.
        Object firstGradientColor = firstGradientColor(paletteClass, template);
        if (firstGradientColor == null) return null;
        Constructor<?> colorBox = firstGradientColor.getClass().getDeclaredConstructor(long.class);
        colorBox.setAccessible(true);

        int[] c = colors();
        Object gradient = Collections.singletonList(colorBox.newInstance(pack(c[PRIMARY])));

        // Same mapping as the upstream Material You theme.
        return constructor.newInstance(
                pack(c[PRIMARY]),                 // secondary
                pack(c[SECONDARY]),               // secondaryVariant
                gradient,                         // accentGradient
                hex(c[PRIMARY]),                  // nativeAccentHex
                pack(c[ON_PRIMARY]),              // onSecondary
                pack(c[ON_SECONDARY]),            // onSecondaryVariant
                pack(c[PRIMARY]),                 // focusRing
                pack(c[PRIMARY_CONTAINER]),       // focusBackground
                pack(c[SURFACE]),                 // background
                pack(c[SURFACE_CONTAINER]),       // backgroundElevated
                pack(c[SURFACE_CONTAINER_HIGH])   // backgroundCard
        );
    }

    /** Finds the constructor (Color, Color, List, String, Color x7) where Color is a packed long. */
    private static Constructor<?> findPaletteConstructor(Class<?> paletteClass) {
        for (Constructor<?> constructor : paletteClass.getDeclaredConstructors()) {
            Class<?>[] t = constructor.getParameterTypes();
            if (t.length == 11
                    && t[0] == long.class && t[1] == long.class
                    && List.class.isAssignableFrom(t[2]) && t[3] == String.class
                    && t[4] == long.class && t[5] == long.class && t[6] == long.class
                    && t[7] == long.class && t[8] == long.class && t[9] == long.class
                    && t[10] == long.class) {
                constructor.setAccessible(true);
                return constructor;
            }
        }
        return null;
    }

    private static Object firstGradientColor(Class<?> paletteClass, Object template) throws Exception {
        for (Field field : paletteClass.getDeclaredFields()) {
            if (List.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                Object value = field.get(template);
                if (value instanceof List && !((List<?>) value).isEmpty()) {
                    return ((List<?>) value).get(0);
                }
            }
        }
        return null;
    }

    /** Compose stores a color as its ARGB value shifted into the upper 32 bits of a long. */
    private static long pack(int argb) {
        return ((long) argb) << 32;
    }

    private static String hex(int argb) {
        return String.format("#%06X", argb & 0xFFFFFF);
    }

    // ---------------------------------------------------------------------------------------------
    // System colors
    // ---------------------------------------------------------------------------------------------

    private static int[] colors() {
        try {
            Context context = applicationContext();
            if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                int[] dynamic = dynamicColors(context);
                if (dynamic != null) return dynamic;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not read system colors", t);
        }
        return FALLBACK;
    }

    /**
     * Dark Material You colors from the system resources. Android 14+ provides the exact Material 3
     * roles; Android 12 and 13 only provide tonal palettes, so the roles are derived from those tones
     * the same way Material does.
     */
    private static int[] dynamicColors(Context context) {
        Resources res = context.getResources();
        Resources.Theme theme = context.getTheme();

        Integer primary = color(res, theme, "system_primary_dark", "system_accent1_200");
        Integer secondary = color(res, theme, "system_secondary_dark", "system_accent2_200");
        Integer onPrimary = color(res, theme, "system_on_primary_dark", "system_accent1_800");
        Integer onSecondary = color(res, theme, "system_on_secondary_dark", "system_accent2_800");
        Integer primaryContainer = color(res, theme, "system_primary_container_dark", "system_accent1_700");
        Integer surface = color(res, theme, "system_surface_dark", "system_neutral1_900");
        if (primary == null || secondary == null || onPrimary == null || onSecondary == null
                || primaryContainer == null || surface == null) {
            return null;
        }

        Integer container = color(res, theme, "system_surface_container_dark", null);
        Integer containerHigh = color(res, theme, "system_surface_container_high_dark", null);
        if (container == null || containerHigh == null) {
            Integer neutral800 = color(res, theme, "system_neutral1_800", null);
            if (neutral800 == null) neutral800 = surface;
            // Tones 12 and 17 sit 20% and 70% of the way from tone 10 (900) to tone 20 (800).
            container = mix(surface, neutral800, 0.2f);
            containerHigh = mix(surface, neutral800, 0.7f);
        }

        return new int[]{primary, secondary, onPrimary, onSecondary, primaryContainer,
                surface, container, containerHigh};
    }

    private static Integer color(Resources res, Resources.Theme theme, String name, String fallbackName) {
        int id = res.getIdentifier(name, "color", "android");
        if (id == 0 && fallbackName != null) id = res.getIdentifier(fallbackName, "color", "android");
        if (id == 0) return null;
        return res.getColor(id, theme) | 0xFF000000;
    }

    private static int mix(int from, int to, float amount) {
        int r = Math.round(((from >> 16) & 0xFF) * (1 - amount) + ((to >> 16) & 0xFF) * amount);
        int g = Math.round(((from >> 8) & 0xFF) * (1 - amount) + ((to >> 8) & 0xFF) * amount);
        int b = Math.round((from & 0xFF) * (1 - amount) + (to & 0xFF) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static Context applicationContext() throws Exception {
        Application application = (Application) Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null);
        return application;
    }
}
