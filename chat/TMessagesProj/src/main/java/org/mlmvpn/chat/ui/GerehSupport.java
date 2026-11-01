package org.mlmvpn.chat.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.mlmvpn.chat.bridge.GerehHost;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

/**
 * MLMCHAT: the support sheet (Ehsan, 31 Oct 2026), from the heart beside the main screen's menu:
 * the heart, a short warm title, a few words on what support pays for, the app's support page,
 * and «فعلاً نه». Only an app that names a support page has the heart (GerehHost.setSupport).
 */
public final class GerehSupport {
    private static final int WARM = 0xFFFF3B66;

    private GerehSupport() {
    }

    public static void show(BaseFragment fragment) {
        String url = GerehHost.support();
        if (fragment == null || fragment.getParentActivity() == null || url == null) {
            return;
        }
        Context context = fragment.getParentActivity();
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(8), dp(24), dp(12));

        layout.addView(new GerehHeartView(context, true), LayoutHelper.createLinear(150, 150, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 0));

        TextView title = text(context, getString(R.string.GerehSupportTitle), 22, Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setTypeface(AndroidUtilities.bold());
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        TextView sub = text(context, getString(R.string.GerehSupportSubtitle), 15, WARM);
        sub.setTypeface(AndroidUtilities.bold());
        layout.addView(sub, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        TextView body = text(context, getString(R.string.GerehSupportText), 15, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        body.setLineSpacing(dp(3), 1f);
        layout.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));

        TextView give = text(context, getString(R.string.GerehSupportButton), 16, 0xFFFFFFFF);
        give.setTypeface(AndroidUtilities.bold());
        GradientDrawable warm = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFFFF6F92, 0xFFFF2E55});
        warm.setCornerRadius(dp(14));
        give.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), warm, null));
        layout.addView(give, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 52, 0, 22, 0, 0));

        TextView later = text(context, getString(R.string.GerehSupportLater), 14, Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        later.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
        layout.addView(later, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 6, 0, 0));

        BottomSheet.Builder builder = new BottomSheet.Builder(context);
        builder.setApplyTopPadding(false);
        builder.setCustomView(layout);
        BottomSheet sheet = builder.create();
        give.setOnClickListener(v -> {
            sheet.dismiss();
            open(context, url);
        });
        later.setOnClickListener(v -> sheet.dismiss());
        fragment.showDialog(sheet);
    }

    private static TextView text(Context context, String s, int sp, int color) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** The page in the phone's browser; with none, its link is copied. */
    private static void open(Context context, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Throwable t) {
            AndroidUtilities.addToClipboard(url);
            BulletinFactory.global().createCopyLinkBulletin().show();
        }
    }
}
