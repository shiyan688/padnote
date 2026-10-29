package com.padnote.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/** Small presentation helpers for grouping existing controls without changing their behavior. */
final class PadNoteUi {
    static final int ACCENT = Color.rgb(53, 95, 210);
    static final int ACCENT_TINT = Color.rgb(235, 240, 255);
    private static final int GROUP_FILL = Color.rgb(235, 234, 228);
    private static final int GROUP_STROKE = Color.rgb(221, 220, 214);

    private PadNoteUi() {}

    static LinearLayout toolbarGroup(Context context) {
        LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setGravity(android.view.Gravity.CENTER_VERTICAL);
        group.setPadding(dp(context, 3), dp(context, 2), dp(context, 3), dp(context, 2));
        group.setBackground(groupBackground(context));
        group.setMinimumHeight(dp(context, 52));
        group.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return group;
    }

    static void addToolbarControl(Context context, LinearLayout group, View control) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48));
        params.setMargins(dp(context, 1), 0, dp(context, 1), 0);
        group.addView(control, params);
    }

    static void addToolbarGroup(Context context, LinearLayout row, LinearLayout group) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(context, 4), 0, dp(context, 4), 0);
        row.addView(group, params);
    }

    static GradientDrawable groupBackground(Context context) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(GROUP_FILL);
        background.setCornerRadius(dp(context, 14));
        background.setStroke(dp(context, 1), GROUP_STROKE);
        return background;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
