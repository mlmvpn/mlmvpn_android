package org.drnet.gereh;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.mlmchat.core.ChatNode;
import org.mlmvpn.chat.bridge.GerehIdentity;
import org.mlmvpn.chat.ui.GerehAddContact;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.TelegramQRCodeWriter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.util.HashMap;

/**
 * DRNET: «my Gereh card» as Telegram's newest QR page looks (owner, 2026-10-26, from a screenshot
 * of Telegram's own): a full-screen soft backdrop, glass buttons at the top and the foot, and in
 * the middle one white card holding the code - rounded modules, the Gereh mark in a black disc at
 * its heart - and the Gereh name under it. Telegram 13.0.3's own QrActivity is the older page, so
 * this is drawn here from its pieces (TelegramQRCodeWriter, its icons).
 *
 * What it does is GerehCardActivity's, unchanged: the card link from the chat core, copy it, send
 * it, scan another phone's card. Opened in its place (GerehCardActivity.orQr) when
 * {@link DrNet#QR_PAGE} is on.
 */
public final class DrNetQrPage extends BaseFragment {
    private static final String TAG = "MLMCHAT";

    private static final int BUTTON_DP = 48;
    private static final int SIDE_DP = 16;

    private boolean dark;
    private FrameLayout topBar;
    private FrameLayout bottomBar;
    private ImageView qrView;
    private TextView infoView;
    private String link;

    @Override
    public View createView(Context context) {
        dark = Theme.isCurrentThemeDark();
        setHasOwnBackground(true);
        actionBar.setAddToContainer(false);
        actionBar.setBackground(null);

        final int ink = dark ? 0xFFFFFFFF : 0xFF111111;
        final boolean rtl = LocaleController.isRTL;
        final int start = rtl ? Gravity.RIGHT : Gravity.LEFT;
        final int end = rtl ? Gravity.LEFT : Gravity.RIGHT;

        FrameLayout root = new FrameLayout(context);
        root.setBackground(new Backdrop(dark));
        fragmentView = root;

        // The card, in the middle of whatever height is left; it scrolls on a short screen.
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        LinearLayout middle = new LinearLayout(context);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.setGravity(Gravity.CENTER);
        middle.setPadding(0, dp(BUTTON_DP + 40), 0, dp(BUTTON_DP + 40));
        scroll.addView(middle, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.MATCH_PARENT));

        final int cardWidth = Math.min(dp(300), (int) (Math.min(AndroidUtilities.displaySize.x, AndroidUtilities.displaySize.y) * 0.76f));
        final int qrSide = (int) (cardWidth * 0.17f);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable white = new GradientDrawable();
        white.setColor(0xFFFFFFFF);
        white.setCornerRadius(dp(DrNet.CARD_RADIUS_DP + 4));
        card.setBackground(white);
        card.setElevation(dp(dark ? 0 : 6));
        card.setPadding(qrSide, (int) (cardWidth * 0.14f), qrSide, (int) (cardWidth * 0.11f));
        middle.addView(card, new LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT));

        // The code sits on white whatever the theme: a scanner needs dark modules on light.
        qrView = new ImageView(context);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final int qrSize = cardWidth - qrSide * 2;
        card.addView(qrView, new LinearLayout.LayoutParams(qrSize, qrSize));

        LinearLayout nameRow = new LinearLayout(context);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER);
        ImageView mark = new ImageView(context);
        mark.setImageResource(R.drawable.gereh_mark);
        mark.setColorFilter(new PorterDuffColorFilter(0xFF000000, PorterDuff.Mode.SRC_IN));
        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(0xFF000000);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
        name.setText(me != null && me.phone != null ? me.phone : "");
        name.setTextDirection(View.TEXT_DIRECTION_LTR);
        nameRow.addView(mark, LayoutHelper.createLinear(30, 30, Gravity.CENTER_VERTICAL, 0, 0, 4, 0));
        nameRow.addView(name, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        nameRow.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        card.addView(nameRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 18, 0, 0));

        infoView = new TextView(context);
        infoView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        infoView.setTextColor(Theme.multAlpha(ink, 0.6f));
        infoView.setGravity(Gravity.CENTER);
        infoView.setLineSpacing(dp(2), 1.0f);
        infoView.setText(getString(R.string.GerehCardInfo));
        middle.addView(infoView, new LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) infoView.getLayoutParams()).topMargin = dp(20);

        // Top: close at the start, «scan a card» at the end - Telegram's close and «Scan QR».
        topBar = new FrameLayout(context);
        root.addView(topBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, BUTTON_DP, Gravity.TOP, SIDE_DP, 0, SIDE_DP, 0));
        topBar.addView(circle(context, R.drawable.msg_close, getString(R.string.Close), ink, v -> finishFragment()),
                LayoutHelper.createFrame(BUTTON_DP, BUTTON_DP, start | Gravity.CENTER_VERTICAL));
        topBar.addView(scanPill(context, ink), LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, BUTTON_DP, end | Gravity.CENTER_VERTICAL));

        // Foot: send the link at the start, copy it at the end - where Telegram has share and save.
        bottomBar = new FrameLayout(context);
        root.addView(bottomBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, BUTTON_DP, Gravity.BOTTOM, SIDE_DP, 0, SIDE_DP, 0));
        bottomBar.addView(circle(context, R.drawable.msg_share, getString(R.string.GerehCardShare), ink, v -> share()),
                LayoutHelper.createFrame(BUTTON_DP, BUTTON_DP, start | Gravity.CENTER_VERTICAL));
        bottomBar.addView(circle(context, R.drawable.msg_copy, getString(R.string.GerehCardCopy), ink, v -> copy()),
                LayoutHelper.createFrame(BUTTON_DP, BUTTON_DP, end | Gravity.CENTER_VERTICAL));

        onInsets(0, AndroidUtilities.statusBarHeight, 0, AndroidUtilities.navigationBarHeight);
        loadCard(qrSize);
        return fragmentView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (topBar != null) {
            ((FrameLayout.LayoutParams) topBar.getLayoutParams()).topMargin = top + dp(12);
            topBar.requestLayout();
        }
        if (bottomBar != null) {
            ((FrameLayout.LayoutParams) bottomBar.getLayoutParams()).bottomMargin = bottom + dp(16);
            bottomBar.requestLayout();
        }
    }

    @Override
    public boolean isLightStatusBar() {
        return !dark;
    }

    @Override
    public int getNavigationBarColor() {
        return Backdrop.edge(dark);
    }

    private ImageView circle(Context context, int icon, CharSequence description, int ink, View.OnClickListener click) {
        ImageView b = new ImageView(context);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setColorFilter(new PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN));
        b.setContentDescription(description);
        b.setBackground(glass(dark, dp(BUTTON_DP / 2f)));
        b.setOnClickListener(click);
        return b;
    }

    private LinearLayout scanPill(Context context, int ink) {
        LinearLayout pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(16), 0, dp(18), 0);
        pill.setBackground(glass(dark, dp(BUTTON_DP / 2f)));
        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.profile_qr_scan_24);
        icon.setColorFilter(new PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN));
        pill.addView(icon, LayoutHelper.createLinear(24, 24, Gravity.CENTER_VERTICAL));
        TextView text = new TextView(context);
        text.setText(getString(R.string.GerehCardScan));
        text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        text.setTypeface(AndroidUtilities.bold());
        text.setTextColor(ink);
        text.setSingleLine(true);
        pill.addView(text, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
        pill.setOnClickListener(v -> GerehAddContact.scan(this));
        return pill;
    }

    private void loadCard(int size) {
        Utilities.globalQueue.postRunnable(() -> {
            String l = null;
            Bitmap bmp = null;
            try {
                ChatNode node = GerehIdentity.node();
                if (node != null) {
                    l = node.cardLink();
                    bmp = qr(l, Math.max(size, dp(160)));
                }
            } catch (Throwable t) {
                Log.e(TAG, "card failed: " + t.getClass().getSimpleName());
            }
            final String fl = l;
            final Bitmap fb = bmp;
            AndroidUtilities.runOnUIThread(() -> {
                link = fl;
                if (fb != null && qrView != null) {
                    qrView.setImageBitmap(fb);
                } else if (infoView != null) {
                    infoView.setText(getString(R.string.GerehCardNotReady));
                }
            });
        });
    }

    /**
     * Telegram's own code: rounded modules and finder squares, a hole at its heart (which error
     * correction M carries) holding a black disc with the Gereh mark in white.
     */
    private static Bitmap qr(String text, int size) throws Exception {
        HashMap<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 0);
        TelegramQRCodeWriter writer = new TelegramQRCodeWriter();
        Bitmap bmp = writer.encode(text, size, size, hints, null, 0.75f, 0xFFFFFFFF, 0xFF000000);
        final int hole = writer.getImageSize();
        Canvas canvas = new Canvas(bmp);
        final float cx = bmp.getWidth() / 2f;
        final float cy = bmp.getHeight() / 2f;
        Paint disc = new Paint(Paint.ANTI_ALIAS_FLAG);
        disc.setColor(0xFF000000);
        canvas.drawCircle(cx, cy, hole * 0.5f, disc);
        Drawable mark = ApplicationLoader.applicationContext.getResources().getDrawable(R.drawable.gereh_mark, null).mutate();
        final int m = (int) (hole * 0.7f);
        mark.setBounds((int) (cx - m / 2f), (int) (cy - m / 2f), (int) (cx + m / 2f), (int) (cy + m / 2f));
        mark.setColorFilter(new PorterDuffColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN));
        mark.draw(canvas);
        return bmp;
    }

    private void copy() {
        if (link == null) {
            return;
        }
        ClipboardManager cb = (ClipboardManager) ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("Gereh", link));
        BulletinFactory.of(this).createCopyBulletin(getString(R.string.GerehCardCopied)).show();
    }

    private void share() {
        if (link == null || getParentActivity() == null) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, link);
        getParentActivity().startActivity(Intent.createChooser(i, getString(R.string.GerehCardShare)));
    }

    /** A glass button's face: a faint fill, a rim lit from above, and a ripple to its shape. */
    private static Drawable glass(boolean dark, float radius) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x1F000000), new Glass(dark, radius), mask);
    }

    private static final class Glass extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float radius;
        private final boolean dark;

        Glass(boolean dark, float radius) {
            this.dark = dark;
            this.radius = radius;
            fill.setColor(dark ? 0x17FFFFFF : 0x0F000000);
            rim.setStyle(Paint.Style.STROKE);
            rim.setStrokeWidth(dp(1));
        }

        @Override
        protected void onBoundsChange(Rect b) {
            rim.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                    dark ? new int[]{0x40FFFFFF, 0x0DFFFFFF, 0x1FFFFFFF} : new int[]{0xE6FFFFFF, 0x0F000000, 0x14000000},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            rect.set(getBounds());
            final float half = rim.getStrokeWidth() / 2f;
            rect.inset(half, half);
            final float r = Math.min(radius, rect.height() / 2f);
            canvas.drawRoundRect(rect, r, r, fill);
            canvas.drawRoundRect(rect, r, r, rim);
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            fill.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /** The page behind the card: lighter at the middle and falling off to the edges. */
    private static final class Backdrop extends Drawable {
        private final Paint paint = new Paint();
        private final boolean dark;

        Backdrop(boolean dark) {
            this.dark = dark;
        }

        static int edge(boolean dark) {
            return dark ? 0xFF0B0B0C : 0xFFDCDDE2;
        }

        @Override
        protected void onBoundsChange(Rect b) {
            final float r = Math.max(1f, (float) Math.hypot(b.width(), b.height()) * 0.55f);
            paint.setShader(new RadialGradient(b.exactCenterX(), b.exactCenterY(), r,
                    dark ? 0xFF2C2C2E : 0xFFFFFFFF, edge(dark), Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            canvas.drawRect(getBounds(), paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
