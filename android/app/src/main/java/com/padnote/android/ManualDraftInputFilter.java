package com.padnote.android;

import android.content.Context;
import android.text.InputFilter;
import android.text.Spanned;
import android.widget.Toast;

/** Rejects an over-limit edit atomically so restored source is never silently truncated. */
final class ManualDraftInputFilter implements InputFilter {
    private final Context context;

    ManualDraftInputFilter(Context context) {
        this.context = context.getApplicationContext() == null
                ? context : context.getApplicationContext();
    }

    @Override public CharSequence filter(CharSequence source, int start, int end,
                                         Spanned dest, int dstart, int dend) {
        long resultingLength = (long) dest.length() - (dend - dstart) + (end - start);
        if (resultingLength <= ManualTextDraftStore.MAX_SOURCE_UTF16_UNITS) return null;
        Toast.makeText(context, R.string.core_manual_draft_input_limit,
                Toast.LENGTH_LONG).show();
        // Returning the replaced destination span makes the rejected edit a no-op,
        // including replacement of a selection. Never accept a partial surrogate pair.
        return dest.subSequence(dstart, dend);
    }
}
