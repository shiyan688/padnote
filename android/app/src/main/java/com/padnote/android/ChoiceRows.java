package com.padnote.android;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

/** Shared paper-style rows and popup styling for Android option selectors. */
final class ChoiceRows {
    private static final int INK = Color.rgb(31, 41, 51);
    private static final int ACCENT = Color.rgb(40, 94, 168);
    private static final int SELECTED = Color.rgb(232, 240, 250);
    private ChoiceRows() {}

    static ArrayAdapter<String> adapter(Context context, String[] values) {
        return new Rows(context, values);
    }

    static ArrayAdapter<String> spinnerAdapter(Spinner owner, String[] values) {
        return new Rows(owner.getContext(), values, owner);
    }

    static AlertDialog.Builder builder(Context context) {
        return new AlertDialog.Builder(context, R.style.PadNoteSelectionDialog);
    }

    static void styleSpinner(Spinner spinner) {
        Context context = spinner.getContext();
        spinner.setBackgroundResource(R.drawable.padnote_selection_spinner_background);
        spinner.setMinimumHeight(dp(context, 48));
        spinner.setPadding(dp(context, 12), dp(context, 4), dp(context, 40), dp(context, 4));
        spinner.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        spinner.setDropDownVerticalOffset(dp(context, 4));
        if (Build.VERSION.SDK_INT >= 21) {
            spinner.setPopupBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        }
    }

    private static final class Rows extends ArrayAdapter<String> {
        private final Spinner owner;
        Rows(Context context, String[] values) {
            super(context, android.R.layout.simple_list_item_1, values); this.owner = null;
        }
        Rows(Context context, String[] values, Spinner owner) {
            super(context, android.R.layout.simple_list_item_1, values); this.owner = owner;
        }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            return row(position, convertView, parent, false);
        }
        @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return row(position, convertView, parent, true);
        }
        private View row(int position, View convertView, ViewGroup parent, boolean dropdown) {
            TextView text = convertView instanceof TextView ? (TextView) convertView : new TextView(getContext());
            Context context = getContext();
            text.setText(getItem(position));
            text.setTextColor(INK);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            text.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            text.setSingleLine(false);
            text.setMaxLines(Integer.MAX_VALUE);
            text.setEllipsize(null);
            text.setIncludeFontPadding(true);
            int vertical = owner != null && !dropdown ? 8 : 12;
            text.setPadding(dp(context, 16), dp(context, vertical), dp(context, 16), dp(context, vertical));
            text.setMinHeight(dp(context, 48));
            if (dropdown) {
                boolean selected = owner != null && position == owner.getSelectedItemPosition();
                android.graphics.drawable.StateListDrawable states =
                        new android.graphics.drawable.StateListDrawable();
                states.addState(new int[] { android.R.attr.state_activated },
                        new android.graphics.drawable.ColorDrawable(SELECTED));
                states.addState(new int[] {}, new android.graphics.drawable.ColorDrawable(Color.WHITE));
                text.setActivated(selected);
                if (selected && Build.VERSION.SDK_INT >= 21) {
                    text.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                            context.getDrawable(R.drawable.padnote_selection_check), null);
                    text.setCompoundDrawablePadding(dp(context, 8));
                } else {
                    text.setCompoundDrawables(null, null, null, null);
                }
                ColorStateList rippleColor = ColorStateList.valueOf(Color.argb(42, 40, 94, 168));
                android.graphics.drawable.Drawable mask = new android.graphics.drawable.ColorDrawable(Color.WHITE);
                if (Build.VERSION.SDK_INT >= 21) {
                    text.setBackground(new android.graphics.drawable.RippleDrawable(rippleColor, states, mask));
                } else {
                    text.setBackground(states);
                }
                text.setTextColor(new ColorStateList(
                        new int[][] { new int[] { android.R.attr.state_activated }, new int[] {} },
                        new int[] { ACCENT, INK }));
            } else {
                Drawable ripple = selectable(context);
                if (ripple != null) text.setBackground(ripple);
            }
            text.setFocusable(false);
            if (owner != null && !dropdown) text.setMaxLines(2);
            return text;
        }
    }

    private static Drawable selectable(Context context) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true)) return null;
        if (Build.VERSION.SDK_INT >= 21 && value.resourceId != 0) return context.getDrawable(value.resourceId);
        return value.resourceId == 0 ? null : context.getResources().getDrawable(value.resourceId);
    }
    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
