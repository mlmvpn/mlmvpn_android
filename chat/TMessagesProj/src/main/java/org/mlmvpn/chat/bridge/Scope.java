package org.mlmvpn.chat.bridge;

import org.telegram.messenger.MessagesController;

/**
 * MLMCHAT: the parts of Telegram that Gereh leaves out (PLAN 8 and 13.4, Ehsan's request of
 * 22 October 2026). One switch per family; every entry point of a family asks its switch, so a
 * family is either wholly in the app or wholly out of it.
 *
 * Most entry points already ask Telegram's own region locks (premium_purchase_blocked,
 * stars_purchase_blocked, stargifts_blocked, stories_posting, wallet_available). {@link #lock}
 * pins those locks shut whatever the stored config says, and the remaining entry points
 * (calls, help pages, a few settings rows) ask the switches directly.
 */
public final class Scope {
    /** Wallet, TON, Stars, paid media, paid reactions and paid messages. */
    public static final boolean MONEY = !Bridge.ENABLED;
    /** Telegram Premium, Business and boosts. */
    public static final boolean PREMIUM = !Bridge.ENABLED;
    /** Gifts of every kind (Premium gifts, star gifts, collectibles). */
    public static final boolean GIFTS = !Bridge.ENABLED;
    /** Posting and viewing stories. */
    public static final boolean STORIES = !Bridge.ENABLED;
    /** Voice and video calls, group calls, video chats and live streams. */
    public static final boolean CALLS = !Bridge.ENABLED;
    /**
     * Not out of scope, only not built yet: photos, videos, files, voice messages and locations
     * come with the core's media part (PLAN 12, phase 4). Until then their buttons say so instead
     * of sending something that can only fail.
     */
    public static final boolean MEDIA = true; // MLMCHAT: photos, videos, voice and files work since 24 Oct 2026 (phase 4)

    /**
     * Telegram's privacy settings that live on its server: who sees my last seen, photo, birthday and
     * bio, who may forward my messages or add me to groups, auto-delete of messages, «delete my account
     * if away», contact suggestions, secret chats, bots and websites. Gereh has no server to hold or
     * honour them, and their rows never loaded («unanswered request», A50 test of 25 Oct 2026), so a
     * row was either dead or a claim nothing kept. They come back one by one with step 7.4 of the
     * work queue, each backed by the core, and this switch goes with the last of them. «Last seen &
     * online» came back first (SPEC 5.5, T57): everybody or nobody, on this phone.
     */
    public static final boolean SERVER_PRIVACY = !Bridge.ENABLED;

    /**
     * Sending a location (m5), not built yet. Telegram's map is Google's, which needs a key this
     * app does not have: opening «Location» in the attach menu closed the chat (mlmvpn/crashes
     * #2791, 1.2.43 and 1.2.44). Its button stays out until m5 sends a location without it.
     */
    public static final boolean LOCATION = !Bridge.ENABLED;

    private Scope() {
    }

    /** Pins Telegram's own locks for the families that are out. Called after every config load. */
    public static void lock(MessagesController mc) {
        if (!PREMIUM) {
            mc.premiumLocked = true;
        }
        if (!MONEY) {
            mc.starsLocked = true;
            mc.channelRevenueWithdrawalEnabled = false;
        }
        if (!GIFTS) {
            mc.stargiftsBlocked = true;
            mc.starsGiftsEnabled = false;
            mc.giftAttachMenuIcon = false;
            mc.giftTextFieldIcon = false;
        }
        if (!STORIES) {
            mc.storiesPosting = "disabled";
        }
        // «Also delete for …» (SPEC 5.2, 25 Oct 2026): any message of a one-to-one chat, at any age,
        // goes from the other phone too; AlertsCreator ticks the box by default in Gereh
        mc.canRevokePmInbox = true;
        mc.revokeTimePmLimit = Integer.MAX_VALUE;
        mc.revokeTimeLimit = Integer.MAX_VALUE;
        // Edit (SPEC 5) for 48 hours, as Telegram's servers allow. The config that says so never
        // reaches this Telegram (only tgnet delivers it, MessagesController.updateConfig), so its
        // stored default, one hour, hid «Edit» on any older message (Ehsan, 27 Oct 2026)
        mc.maxEditTime = 172800;
        // A read receipt carries no time Telegram could ask for (messages.getOutboxReadDate),
        // so the «read at …» row in a message's menu only said «Unknown error»; the two checks stay.
        mc.pmReadDateExpirePeriod = 0;
    }
}
