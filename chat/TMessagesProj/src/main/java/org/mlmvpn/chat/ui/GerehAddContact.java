package org.mlmvpn.chat.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.Contact;
import org.mlmvpn.chat.bridge.GerehIdentity;
import org.mlmvpn.chat.bridge.Messenger;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.CameraScanActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineTextContainerView;

/**
 * MLMCHAT: «New Contact», in place of Telegram's phone-number sheet (PLAN 4).
 *
 * A Gereh name alone finds nobody: no server knows where it is (T14). A contact comes from their
 * card: scanned face to face, or its {@code mlmchat://} link pasted from wherever they sent it.
 * Adding sends them our card inside the new session, so they get us as a contact too.
 */
public final class GerehAddContact extends BottomSheet {
    private static final String TAG = "MLMCHAT";
    private static final String SCHEME = "mlmchat://";
    /** The official chats' owner key (keepOwnerKey); a card is never `o/`. */
    private static final String OWNER_SCHEME = SCHEME + "o/";

    private final BaseFragment fragment;
    private final EditTextBoldCursor field;
    private final OutlineTextContainerView outline;
    private final TextView error;
    private boolean adding;

    public static void show(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        new GerehAddContact(fragment, null).show();
    }

    /** Opens the camera; a Gereh card found there is added right away. */
    public static void scan(BaseFragment fragment) {
        Activity a = fragment.getParentActivity();
        if (a == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && a.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{Manifest.permission.CAMERA}, 34);
            return;
        }
        // gallery = true: a QR can also be read from a picture (a screenshot sent by any messenger),
        // for a phone whose camera preview does not start.
        CameraScanActivity.showAsSheet(fragment, true, CameraScanActivity.TYPE_QR, new CameraScanActivity.CameraScanActivityDelegate() {
            @Override
            public void didFindQr(String text) {
                AndroidUtilities.runOnUIThread(() -> new GerehAddContact(fragment, text).show(), 300);
            }
        });
    }

    private GerehAddContact(BaseFragment fragment, String scanned) {
        super(fragment.getParentActivity(), true);
        this.fragment = fragment;
        Context context = fragment.getParentActivity();
        setApplyBottomPadding(true);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, dp(8), 0, dp(8));

        TextView title = new TextView(context);
        title.setText(getString(R.string.GerehAddTitle));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        title.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 10, 22, 0));

        TextView info = new TextView(context);
        info.setText(getString(R.string.GerehAddInfo));
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        info.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        info.setLineSpacing(dp(2), 1.0f);
        info.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        column.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 8, 22, 0));

        field = new EditTextBoldCursor(context);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        field.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated));
        field.setCursorSize(dp(20));
        field.setCursorWidth(1.5f);
        field.setBackground(null);
        field.setPadding(0, 0, 0, 0);
        field.setMaxLines(4);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI);
        field.setTextDirection(android.view.View.TEXT_DIRECTION_LTR);
        field.setHint(SCHEME + "…");

        outline = new OutlineTextContainerView(context);
        outline.setText(getString(R.string.GerehAddField));
        outline.attachEditText(field);
        outline.addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 16, 14, 16, 14));
        field.setOnFocusChangeListener((v, hasFocus) -> outline.animateSelection(hasFocus ? 1f : 0f));
        column.addView(outline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 18, 16, 0));

        error = new TextView(context);
        error.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        error.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        error.setVisibility(android.view.View.GONE);
        column.addView(error, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 22, 6, 22, 0));

        // side by side with no internet and no QR (SPEC 16.4)
        column.addView(button(context, R.string.GerehNearAddButton, false, () -> {
            dismiss();
            fragment.presentFragment(new GerehNearAddActivity());
        }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 16, 16, 0));
        column.addView(button(context, R.string.GerehAddScan, false, () -> {
            dismiss();
            scan(fragment);
        }), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 10, 16, 0));
        column.addView(button(context, R.string.GerehAddButton, true, this::add), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 10, 16, 8));

        setCustomView(column);

        String start = scanned;
        if (start == null) {
            // A card link already on the clipboard (they sent it by another app) is filled in.
            try {
                ClipboardManager cb = (ClipboardManager) ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = cb.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                    String t = clip.getItemAt(0).getText().toString().trim();
                    if (t.startsWith(SCHEME)) {
                        start = t;
                    }
                }
            } catch (Throwable ignore) {
            }
        }
        if (start != null) {
            field.setText(start.trim());
        }
        if (scanned != null) {
            AndroidUtilities.runOnUIThread(this::add, 200);
        }
    }

    private TextView button(Context context, int text, boolean primary, Runnable click) {
        TextView b = new TextView(context);
        b.setText(getString(text));
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        b.setTypeface(AndroidUtilities.bold());
        if (primary) {
            b.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
            b.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(10), Theme.getColor(Theme.key_featuredStickers_addButton), Theme.getColor(Theme.key_featuredStickers_addButtonPressed)));
        } else {
            b.setTextColor(Theme.getColor(Theme.key_featuredStickers_addButton));
            b.setBackground(Theme.createSimpleSelectorRoundRectDrawable(dp(10), Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton), 0.12f), Theme.multAlpha(Theme.getColor(Theme.key_featuredStickers_addButton), 0.24f)));
        }
        b.setOnClickListener(v -> click.run());
        return b;
    }

    private void add() {
        if (adding) {
            return;
        }
        final String link = field.getText().toString().trim();
        if (!link.startsWith(SCHEME)) {
            fail(getString(R.string.GerehAddNotLink));
            return;
        }
        if (link.startsWith(OWNER_SCHEME)) {
            keepOwnerKey(link.substring(OWNER_SCHEME.length()));
            return;
        }
        adding = true;
        final int account = UserConfig.selectedAccount;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                ChatNode node = GerehIdentity.node();
                if (node == null) {
                    throw new IllegalStateException("no identity");
                }
                final Contact c = node.addContact(link);
                final TLRPC.User user = Messenger.userFor(c);
                AndroidUtilities.runOnUIThread(() -> {
                    adding = false;
                    MessagesController.getInstance(account).putUser(user, false);
                    ContactsController.getInstance(account).loadContacts(false, 0);
                    dismiss();
                    Bundle args = new Bundle();
                    args.putLong("user_id", user.id);
                    fragment.presentFragment(new ChatActivity(args));
                });
            } catch (Throwable t) {
                Log.w(TAG, "card not added: " + t.getClass().getSimpleName());
                final String why = failure(t);
                AndroidUtilities.runOnUIThread(() -> {
                    adding = false;
                    fail(why);
                });
            }
        });
    }

    /**
     * Why a card was refused, in the chat's language. The core's reasons are English (its error
     * texts, core/src/card.rs); the few a person can act on have their own line, and the rest show
     * as is in English and as one plain line in Persian, so no English reaches a Persian screen.
     */
    private static String failure(Throwable t) {
        String m = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        if (m.contains("your own card")) {
            return getString(R.string.GerehAddOwnCard);
        }
        if (m.contains("expired")) {
            return getString(R.string.GerehAddExpired);
        }
        if (m.contains("different identity key")) {
            return getString(R.string.GerehAddKeyChanged);
        }
        if (m.contains("sig")) {
            return getString(R.string.GerehAddForged);
        }
        if (m.startsWith("link:")) {
            return getString(R.string.GerehAddNotLink);
        }
        return org.mlmvpn.chat.bridge.GerehLocale.persian() ? getString(R.string.GerehAddFailedPlain) : LocaleController.formatString(R.string.GerehAddFailed, m);
    }

    /**
     * `mlmchat://o/<hex>`: the official chats' owner key, scanned once on the owner's main phone
     * (Ehsan, 27 Oct 2026), so their admins are named from it. Kept in the core's encrypted
     * database; the field is cleared at once.
     */
    private void keepOwnerKey(String hex) {
        adding = true;
        field.setText("");
        Utilities.globalQueue.postRunnable(() -> {
            boolean kept;
            try {
                ChatNode node = GerehIdentity.node();
                if (node == null) {
                    throw new IllegalStateException("no identity");
                }
                node.feedOwnerImport(hex);
                kept = true;
            } catch (Throwable t) {
                Log.w(TAG, "owner key not kept: " + t.getClass().getSimpleName());
                kept = false;
            }
            final boolean ok = kept;
            AndroidUtilities.runOnUIThread(() -> {
                adding = false;
                if (!ok) {
                    fail(getString(R.string.GerehOwnerWrong));
                    return;
                }
                dismiss();
                org.mlmvpn.chat.bridge.Bridge.ownerKeyKept();
                android.widget.Toast.makeText(ApplicationLoader.applicationContext, getString(R.string.GerehOwnerKept), android.widget.Toast.LENGTH_LONG).show();
            });
        });
    }

    private void fail(String text) {
        error.setText(text);
        error.setVisibility(android.view.View.VISIBLE);
        outline.animateError(1f);
        AndroidUtilities.shakeViewSpring(outline, 3.5f);
        AndroidUtilities.runOnUIThread(() -> outline.animateError(0f), 1500);
    }
}
