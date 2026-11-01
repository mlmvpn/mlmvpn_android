package org.mlmvpn.chat.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.mlmchat.core.ChatNode;
import org.mlmvpn.chat.bridge.GerehIdentity;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.QrActivity;

import java.util.HashMap;

/**
 * MLMCHAT: «My Gereh card», in place of Telegram's QR page (PLAN 4 and 15).
 *
 * The code is a face-to-face card from the core (SPEC 3): our keys, a fresh one-time key and the
 * addresses this phone can be reached at. The other phone scans it to add us; its first message
 * carries its own card back, so it appears here as a contact by itself. A new card each time
 * the page opens. The link can also go by any other way (SMS, another messenger).
 */
public class GerehCardActivity extends BaseFragment {
    private static final String TAG = "MLMCHAT";

    private ImageView qrView;
    private TextView infoView;
    private String link;

    /** Telegram's QR page, or this one when the bridge is on. */
    public static BaseFragment orQr(Bundle args) {
        if (org.mlmvpn.chat.bridge.Bridge.ENABLED && org.drnet.gereh.DrNet.QR_PAGE) {
            return new org.drnet.gereh.DrNetQrPage(); // DRNET: the same card, in Telegram's newest QR look
        }
        return org.mlmvpn.chat.bridge.Bridge.ENABLED ? new GerehCardActivity() : new QrActivity(args);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.GerehCardTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;
        ScrollView scroll = new ScrollView(context);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(column, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // The code sits on white whatever the theme: a scanner needs dark modules on light.
        FrameLayout card = new FrameLayout(context);
        card.setBackground(Theme.createRoundRectDrawable(dp(20), Color.WHITE));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        qrView = new ImageView(context);
        qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.addView(qrView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        column.addView(card, LayoutHelper.createLinear(300, 300, Gravity.CENTER_HORIZONTAL, 0, 28, 0, 0));

        TLRPC.User me = UserConfig.getInstance(currentAccount).getCurrentUser();
        TextView name = new TextView(context);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setGravity(Gravity.CENTER);
        name.setText(me != null && me.phone != null ? me.phone : "");
        column.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 18, 24, 0));

        infoView = new TextView(context);
        infoView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        infoView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        infoView.setGravity(Gravity.CENTER);
        infoView.setLineSpacing(dp(2), 1.0f);
        infoView.setText(getString(R.string.GerehCardInfo));
        column.addView(infoView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 10, 32, 20));

        column.addView(button(context, R.string.GerehCardCopy, v -> copy()), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 24, 0, 24, 10));
        column.addView(button(context, R.string.GerehCardShare, v -> share()), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 24, 0, 24, 10));
        column.addView(button(context, R.string.GerehCardScan, v -> GerehAddContact.scan(this)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 24, 0, 24, 28));

        loadCard();
        return fragmentView;
    }

    private TextView button(Context context, int text, View.OnClickListener click) {
        TextView b = new TextView(context);
        b.setText(getString(text));
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        b.setTypeface(AndroidUtilities.bold());
        b.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        b.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(10), Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        b.setOnClickListener(click);
        return b;
    }

    private void loadCard() {
        Utilities.globalQueue.postRunnable(() -> {
            String l = null;
            Bitmap bmp = null;
            try {
                ChatNode node = GerehIdentity.node();
                if (node != null) {
                    l = node.cardLink();
                    bmp = qr(l, 720);
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

    /** Plain black-on-white modules, quiet zone included, low error correction to keep it small. */
    static Bitmap qr(String text, int size) throws Exception {
        HashMap<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
        hints.put(EncodeHintType.MARGIN, 2);
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
        int w = m.getWidth(), h = m.getHeight();
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
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
}
