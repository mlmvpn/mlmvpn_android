package org.mlmvpn.chat.bridge;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.widget.FrameLayout;

import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.ScaleStateListAnimator;

/** MLMCHAT: what Telegram's screens need from the host app's process to look as they do in Telegram. */
public final class GerehUi {
    private static final String GREETING = "gereh_greeting";

    private GerehUi() {
    }

    /**
     * The greeting in an empty one-to-one chat. Telegram fills that box with a random animated
     * sticker from its servers; Gereh has none, so the box stood empty. One animation only, one
     * Telegram already ships (raw/raised_hand, the waving hand), so the app grows by nothing
     * (Ehsan, 22 Oct 2026: «یک استیکر متحرک برای چت های خالی کافیه»). A tap sends «👋» as text,
     * as Telegram's tap sends its sticker; stickers themselves come with media (phase 4).
     */
    public static void greeting(FrameLayout box, int account, long dialogId) {
        if (box.findViewWithTag(GREETING) != null) {
            return;
        }
        RLottieImageView hand = new RLottieImageView(box.getContext());
        hand.setTag(GREETING);
        hand.setAnimation(R.raw.raised_hand, 112, 112);
        hand.setAutoRepeat(true);
        hand.playAnimation();
        ScaleStateListAnimator.apply(hand);
        hand.setOnClickListener(v -> SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of("👋", dialogId)));
        box.addView(hand, LayoutHelper.createFrame(112, 112));
    }

    /**
     * A join by link that is still on its way to the inviter's phone (GerehInvites answers
     * «GEREH_JOIN_QUEUED» when Telegram's join dialog has waited long enough): an honest notice
     * instead of Telegram's «this group does not seem to exist». True if it was that.
     */
    public static boolean joinQueued(org.telegram.ui.ActionBar.BaseFragment fragment, org.telegram.tgnet.TLRPC.TL_error error) {
        if (error == null || !"GEREH_JOIN_QUEUED".equals(error.text)) {
            return false;
        }
        if (fragment != null) {
            org.telegram.ui.Components.BulletinFactory.of(fragment)
                    .createSimpleBulletin(R.raw.timer_3, org.telegram.messenger.LocaleController.getString(R.string.GerehJoinQueued))
                    .show();
        }
        return true;
    }

    /**
     * Telegram's own app declares {@code supportsRtl="false"} and lays right-to-left languages out
     * itself (LocaleController.isRTL). Inside MLMVPN the application element is MLMVPN's, which
     * supports RTL, so on a Persian phone Android mirrored Telegram's screens a second time and
     * moved their icons (the selection bar, Ehsan's report of 22 Oct 2026). Views ask their
     * context's ApplicationInfo whether RTL is supported; this context answers «no», putting the
     * chat's activities, and the dialogs and popups made from them, back in Telegram's own mode.
     * Nothing else of MLMVPN is touched.
     */
    public static Context telegramLayout(Context base) {
        if (!Bridge.ENABLED || base == null) {
            return base;
        }
        return new ContextWrapper(base) {
            @Override
            public ApplicationInfo getApplicationInfo() {
                ApplicationInfo ai = new ApplicationInfo(super.getApplicationInfo());
                ai.flags &= ~ApplicationInfo.FLAG_SUPPORTS_RTL;
                return ai;
            }
        };
    }

    /**
     * The blue tick by a verified sender's name in a message bubble: the official chats' admins
     * carry it in every chat, groups too (Ehsan, 28 Oct 2026). Telegram draws only an emoji status
     * or the Premium star there, tinted with the name's colour; this one keeps its own two colours,
     * the chat list's tick, whatever tint the bubble gives it.
     */
    public static android.graphics.drawable.Drawable verifiedBadge() {
        return new android.graphics.drawable.Drawable() {
            @Override
            public void draw(android.graphics.Canvas canvas) {
                android.graphics.Rect b = getBounds();
                // smaller than the 20dp box Telegram gives a name's emoji status, which has air
                // around it and this tick has none: at 20dp it stood taller than the name (Ehsan,
                // 28 Oct 2026: «تیک آبی ... خیلی بزرگه»)
                int size = Math.min(Math.min(b.width(), b.height()), org.telegram.messenger.AndroidUtilities.dp(15));
                int left = b.centerX() - size / 2;
                int top = b.centerY() - size / 2;
                for (android.graphics.drawable.Drawable d : new android.graphics.drawable.Drawable[]{
                        org.telegram.ui.ActionBar.Theme.dialogs_verifiedDrawable, org.telegram.ui.ActionBar.Theme.dialogs_verifiedCheckDrawable}) {
                    if (d == null) {
                        continue;
                    }
                    android.graphics.Rect was = d.copyBounds();
                    d.setBounds(left, top, left + size, top + size);
                    d.draw(canvas);
                    d.setBounds(was);
                }
            }

            @Override
            public void setAlpha(int alpha) {
            }

            @Override
            public void setColorFilter(android.graphics.ColorFilter filter) {
            }

            @Override
            public int getOpacity() {
                return android.graphics.PixelFormat.TRANSLUCENT;
            }
        };
    }
}
