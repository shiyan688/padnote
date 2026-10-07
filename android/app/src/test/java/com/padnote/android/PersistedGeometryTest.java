package com.padnote.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class PersistedGeometryTest {
    @Test public void retainsLargeIntegralTimestampWithoutDoubleRoundTrip() {
        Long original = 9007199254740993L;
        Number persisted = PersistedGeometry.persistedNumber(original, "timestamp");
        assertSame(original, persisted);
        assertEquals(9007199254740993L, persisted.longValue());
    }

    @Test public void keepsFractionalMillisecondsAsBinary64() {
        Number persisted = PersistedGeometry.persistedNumber(123.25d, "timestamp");
        assertEquals(123.25d, persisted.doubleValue(), 0d);
        assertEquals(456.125d,
                PersistedGeometry.persistedNumber(456.125d, "createdAt").doubleValue(), 0d);
    }

    @Test public void keepsOrdinaryLongTimestampIntegral() {
        Long original = 1725123456789L;
        assertSame(original, PersistedGeometry.persistedNumber(original, "timestamp"));
    }

    @Test public void renderProjectionRejectsNonFiniteOrOutOfFloatRange() {
        assertThrows(IllegalArgumentException.class,
                () -> PersistedGeometry.renderFloat(Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> PersistedGeometry.renderFloat((double) Float.MAX_VALUE * 2d));
    }
}
