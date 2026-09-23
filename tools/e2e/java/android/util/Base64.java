package android.util;

/**
 * JVM shim for the single Android API PadNote's AI client touches at runtime.
 * Mirrors android.util.Base64.NO_WRAP semantics closely enough for wire testing
 * (no line breaks); never shipped to a device.
 */
public final class Base64 {
    public static final int NO_WRAP = 2;
    public static final int DEFAULT = 0;

    private Base64() {
    }

    public static String encodeToString(byte[] input, int flags) {
        return java.util.Base64.getEncoder().encodeToString(input);
    }

    public static byte[] decode(String input, int flags) {
        return java.util.Base64.getDecoder().decode(input);
    }
}
