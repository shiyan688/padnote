package com.padnote.android;

/** Canonical note geometry is binary64; this helper is only for Android render APIs. */
final class PersistedGeometry {
    private PersistedGeometry() { }

    static float renderFloat(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("document geometry must be finite");
        }
        float projected = (float) value;
        if (!Float.isFinite(projected)) {
            throw new IllegalArgumentException("document geometry is outside Android render range");
        }
        return projected;
    }

    static Number persistedNumber(Object value, String field) {
        if (!(value instanceof Number)) throw new IllegalArgumentException(field + " must be numeric");
        Number number = (Number) value;
        if (number instanceof Double || number instanceof Float) {
            double exact = number.doubleValue();
            if (Double.isNaN(exact) || Double.isInfinite(exact)) {
                throw new IllegalArgumentException(field + " must be finite");
            }
            return Double.valueOf(exact);
        }
        // Keep JSON integer tokens as their original integral Number. In particular,
        // converting Long 9007199254740993 to double would silently change it.
        return number;
    }
}
