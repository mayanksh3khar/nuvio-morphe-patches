package app.nuvio.extension;

import android.util.Log;

import java.lang.reflect.Method;

/**
 * Helper called from the patched Nuvio app.
 *
 * Most of Nuvio's code is obfuscated, but the P2P engine classes keep their real names, so they are
 * reached through reflection. Everything here fails safe: on any problem the original URL is returned
 * unchanged, so a Nuvio update can never break playback because of this patch.
 */
@SuppressWarnings("unused")
public final class P2pExternalPlayer {
    private static final String TAG = "NuvioP2pExternal";

    private static final String ENGINE_CLASS = "com.nuvio.app.features.p2p.P2pStreamingEngine";
    private static final String STATE_PACKAGE_PREFIX = "com.nuvio.app.features.p2p.P2pStreamingState";
    private static final String STREAMING_CLASS = "com.nuvio.app.features.p2p.P2pStreamingState$Streaming";
    private static final String PLACEHOLDER_SCHEME = "torrent://";

    private P2pExternalPlayer() {
    }

    /**
     * Called with the source URL of an external player request.
     *
     * @return the local stream URL of the running P2P engine when {@code sourceUrl} is a
     * {@code torrent://} placeholder and the engine is streaming; otherwise {@code sourceUrl} unchanged.
     */
    public static String resolveSourceUrl(String sourceUrl) {
        try {
            if (sourceUrl == null
                    || !sourceUrl.regionMatches(true, 0, PLACEHOLDER_SCHEME, 0, PLACEHOLDER_SCHEME.length())) {
                return sourceUrl;
            }

            String localUrl = currentLocalStreamUrl();
            if (localUrl != null) {
                Log.i(TAG, "Using resolved P2P stream URL for the external player");
                return localUrl;
            }
            Log.w(TAG, "P2P stream is not ready, external player gets the original URL");
        } catch (Throwable t) {
            Log.w(TAG, "Could not resolve P2P stream URL", t);
        }
        return sourceUrl;
    }

    private static String currentLocalStreamUrl() throws Exception {
        Class<?> engineClass = Class.forName(ENGINE_CLASS);
        Object engine = engineClass.getField("INSTANCE").get(null);
        if (engine == null) return null;

        Object stateFlow = engineClass.getMethod("getState").invoke(engine);
        if (stateFlow == null) return null;

        Class<?> streamingClass = Class.forName(STREAMING_CLASS);
        Object state = readCurrentState(stateFlow);
        if (state == null || !streamingClass.isInstance(state)) return null;

        return readLocalUrl(streamingClass, state);
    }

    /** Reads the current value of the engine's state holder (a Kotlin StateFlow whose class is obfuscated). */
    private static Object readCurrentState(Object stateFlow) throws Exception {
        Class<?> holderClass = stateFlow.getClass();

        // Normal case: the interface method getValue() keeps its name.
        for (Method method : holderClass.getMethods()) {
            if (method.getParameterTypes().length == 0 && "getValue".equals(method.getName())) {
                method.setAccessible(true);
                return method.invoke(stateFlow);
            }
        }

        // Fallback if getValue was renamed: try each no-argument getter and keep the one that
        // returns an engine state object.
        for (Method method : holderClass.getMethods()) {
            if (method.getParameterTypes().length != 0
                    || method.getDeclaringClass() == Object.class
                    || method.getReturnType() != Object.class) {
                continue;
            }
            try {
                method.setAccessible(true);
                Object value = method.invoke(stateFlow);
                if (value != null && value.getClass().getName().startsWith(STATE_PACKAGE_PREFIX)) {
                    return value;
                }
            } catch (Throwable ignored) {
                // Try the next candidate.
            }
        }
        return null;
    }

    private static String readLocalUrl(Class<?> streamingClass, Object state) throws Exception {
        try {
            Object url = streamingClass.getMethod("getLocalUrl").invoke(state);
            if (url instanceof String && isHttpUrl((String) url)) return (String) url;
        } catch (NoSuchMethodException ignored) {
            // Fall through to the generic search below.
        }

        // Fallback if getLocalUrl was renamed: any no-argument String getter that returns an http URL.
        for (Method method : streamingClass.getMethods()) {
            if (method.getParameterTypes().length == 0 && method.getReturnType() == String.class
                    && method.getDeclaringClass() != Object.class) {
                Object value = method.invoke(state);
                if (value instanceof String && isHttpUrl((String) value)) return (String) value;
            }
        }
        return null;
    }

    private static boolean isHttpUrl(String value) {
        return value.startsWith("http://") || value.startsWith("https://");
    }
}
