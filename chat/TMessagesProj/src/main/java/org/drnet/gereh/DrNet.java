package org.drnet.gereh;

/**
 * DRNET: the looks the Dr.Net team gave Gereh's screens, each behind its own switch here, so an
 * app wears it or keeps Telegram's own (`grep DrNet.` finds every hook). The measures are those of
 * Dr.Net's masters (the card corner 26dp, the card margin 10dp); the colours are always the
 * theme's own. Turning a switch off brings back Telegram's look for that piece alone.
 */
public final class DrNet {
    /**
     * The host app chooses light or dark for Gereh, so none of Telegram's own ways to change the
     * theme are offered: the colour themes and night mode rows of Chat Settings and its theme
     * menu, the day/night item of the chat list's menu, and a chat's theme sheet - its menu item
     * opens the chat background page instead. Dr.Net sets Telegram's «Day» or «Night» from its own
     * settings before the chat process starts, and has this on. Off here, so an app that does not
     * choose the theme itself keeps Telegram's pickers.
     */
    public static final boolean HOST_THEME = false;

    /** The profile's QR page in Telegram's newest look ({@link DrNetQrPage}), in place of Gereh's card page. */
    public static final boolean QR_PAGE = true;

    /**
     * Popups as one family, as Dr.Net draws its own (NetDoctorDialog): a dialog is a solid card at
     * the card corner, the card margin from both edges up to 420dp wide and low down on a phone,
     * its answers equal halves across a hairline (AlertDialog); a bottom sheet floats as a card
     * round on all four corners ({@link DrNetFrames.Sheet}); every menu takes the card corner
     * ({@link #menuRadius}); the add-contact sheet is laid out like the dialogs ({@link DrNetForm}).
     */
    public static final boolean POPUPS = true;

    /**
     * Rounded cards: the sections of the settings lists at the card corner and margin (Telegram's
     * are 16 and 12), Gereh's own pages in such cards too ({@link #cardsFor}), and a hairline
     * between two rows of one card in the main settings.
     */
    public static final boolean CARDS = true;

    /** The on/off switch in Dr.Net's measure and motion ({@link DrNetSwitch}), in the switch's own colours. */
    public static final boolean SWITCH = true;

    /**
     * The Google relays page worn as a settings page ({@link DrNetSettings}): small headings over
     * its cards, rows with a second line and a chevron where a row opens a page, notes under them.
     */
    public static final boolean RELAY_PAGE = true;

    /**
     * A mention in one's own bubble underlined: the theme gives links there the text's own colour,
     * so without it a mention reads as plain text.
     */
    public static final boolean MENTION_UNDERLINE = true;

    /**
     * The card corner (Dimens.radiusLg in Dr.Net, One UI's largest block radius) and the card
     * margin from the screen's edge (Dimens.screenMargin).
     */
    public static final int CARD_RADIUS_DP = 26;
    public static final int CARD_MARGIN_DP = 10;

    /**
     * The corner of every popup menu, in px: the card corner with {@link #POPUPS}, else Telegram's
     * 12dp - for the glass menus' background, the swipe-back menus' clip and the message menu's
     * outline.
     */
    public static int menuRadius() {
        return org.telegram.messenger.AndroidUtilities.dp(POPUPS ? CARD_RADIUS_DP : 12);
    }

    /**
     * Whether a page's list is drawn in rounded cards although the page does not ask for them:
     * Gereh's own pages (org.mlmvpn.chat.ui), which use Telegram's older full-width blocks.
     * Telegram's pages ask for cards themselves where they want them.
     */
    public static boolean cardsFor(Object page) {
        return CARDS && page != null && page.getClass().getName().startsWith("org.mlmvpn.chat.ui.");
    }

    private DrNet() {
    }
}
