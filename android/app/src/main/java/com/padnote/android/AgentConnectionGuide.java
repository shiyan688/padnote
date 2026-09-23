package com.padnote.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Offline, non-interactive instructions for connecting a computer running Hermes. */
final class AgentConnectionGuide {
    private static final String ASSET_NAME = "agent-connection-guide.json";
    private static final int INK = Color.rgb(23, 33, 43);
    private static final int SECONDARY = Color.rgb(107, 118, 132);
    private static final int ACCENT = Color.rgb(40, 94, 168);

    private AgentConnectionGuide() {
    }

    static void show(Activity activity) {
        final JSONObject guide;
        try {
            guide = read(activity);
        } catch (Exception error) {
            Toast.makeText(activity, "暂时无法打开连接教程", Toast.LENGTH_LONG).show();
            return;
        }

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int edge = dp(activity, 20);
        content.setPadding(edge, dp(activity, 8), edge, dp(activity, 24));

        TextView summary = text(activity, guide.optString("summary"), 15, INK);
        summary.setLineSpacing(0, 1.18f);
        content.addView(summary, matchWrap());

        JSONArray sections = guide.optJSONArray("sections");
        if (sections != null) {
            for (int index = 0; index < sections.length(); index++) {
                JSONObject section = sections.optJSONObject(index);
                if (section == null) continue;
                TextView title = text(activity, section.optString("title"), 17, INK);
                title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                LinearLayout.LayoutParams titleParams = matchWrap();
                titleParams.topMargin = dp(activity, 24);
                titleParams.bottomMargin = dp(activity, 7);
                content.addView(title, titleParams);

                TextView body = text(activity, section.optString("body"), 14, INK);
                body.setLineSpacing(0, 1.2f);
                body.setTextIsSelectable(true);
                content.addView(body, matchWrap());

                String code = section.optString("code");
                if (!code.isEmpty()) {
                    TextView codeHint = text(activity,
                            "命令或配置（请按本节说明在对应终端执行；可长按选择并复制）",
                            12, SECONDARY);
                    LinearLayout.LayoutParams hintParams = matchWrap();
                    hintParams.topMargin = dp(activity, 10);
                    content.addView(codeHint, hintParams);

                    TextView codeView = text(activity, code, 13, INK);
                    codeView.setTypeface(Typeface.MONOSPACE);
                    codeView.setTextIsSelectable(true);
                    codeView.setPadding(dp(activity, 12), dp(activity, 10),
                            dp(activity, 12), dp(activity, 10));
                    codeView.setBackgroundColor(Color.rgb(241, 243, 245));
                    LinearLayout.LayoutParams codeParams = matchWrap();
                    codeParams.topMargin = dp(activity, 5);
                    content.addView(codeView, codeParams);
                }
            }
        }

        JSONArray links = guide.optJSONArray("links");
        if (links != null && links.length() > 0) {
            TextView linksTitle = text(activity, "官方文档", 17, INK);
            linksTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            LinearLayout.LayoutParams titleParams = matchWrap();
            titleParams.topMargin = dp(activity, 26);
            content.addView(linksTitle, titleParams);
            for (int index = 0; index < links.length(); index++) {
                JSONObject link = links.optJSONObject(index);
                if (link == null) continue;
                String url = link.optString("url");
                if (!url.startsWith("https://")) continue;
                Button button = new Button(activity);
                button.setAllCaps(false);
                button.setText(link.optString("title") + " ↗");
                button.setTextColor(ACCENT);
                button.setTextSize(14);
                button.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
                button.setBackgroundColor(Color.TRANSPARENT);
                button.setPadding(0, 0, 0, 0);
                button.setOnClickListener(view -> openLink(activity, url));
                content.addView(button, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 44)));
            }
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(guide.optString("title", "连接教程"))
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .setNeutralButton("分享教程", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener(view -> share(activity, guide)));
        dialog.show();
    }

    private static JSONObject read(Activity activity) throws Exception {
        try (InputStream stream = activity.getAssets().open(ASSET_NAME)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) >= 0) output.write(buffer, 0, count);
            return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    private static void openLink(Activity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(activity, "未找到可打开网页的浏览器", Toast.LENGTH_LONG).show();
        }
    }

    private static void share(Activity activity, JSONObject guide) {
        StringBuilder output = new StringBuilder();
        output.append(guide.optString("title")).append("\n\n")
                .append(guide.optString("summary")).append("\n");
        JSONArray sections = guide.optJSONArray("sections");
        if (sections != null) {
            for (int index = 0; index < sections.length(); index++) {
                JSONObject section = sections.optJSONObject(index);
                if (section == null) continue;
                output.append("\n").append(section.optString("title")).append("\n")
                        .append(section.optString("body")).append("\n");
                String code = section.optString("code");
                if (!code.isEmpty()) output.append("\n").append(code).append("\n");
            }
        }
        JSONArray links = guide.optJSONArray("links");
        if (links != null && links.length() > 0) {
            output.append("\n官方文档\n");
            for (int index = 0; index < links.length(); index++) {
                JSONObject link = links.optJSONObject(index);
                if (link != null) output.append(link.optString("title")).append(": ")
                        .append(link.optString("url")).append("\n");
            }
        }
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, guide.optString("title"))
                .putExtra(Intent.EXTRA_TEXT, output.toString());
        try {
            activity.startActivity(Intent.createChooser(intent, "分享连接教程"));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(activity, "未找到可分享文本的应用", Toast.LENGTH_LONG).show();
        }
    }

    private static TextView text(Activity activity, String value, float size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
