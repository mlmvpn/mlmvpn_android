package org.mlmvpn.chat.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spanned;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.PendingIdentity;
import org.mlmvpn.chat.bridge.GerehIdentity;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.OutlineTextContainerView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.TransformableLoginButtonView;
import org.telegram.ui.Components.VerticalPositionAutoAnimator;
import org.telegram.ui.MainTabsActivity;

import java.nio.charset.StandardCharsets;

/**
 * MLMCHAT: the first screen of Gereh, in place of Telegram's phone-number login (PLAN 15).
 *
 * Built from the parts of Telegram's own phone screen (LoginActivity.PhoneView): the same title,
 * subtitle, outlined field and floating arrow. Where Telegram puts the country code beside the
 * number, this screen puts the four digits that follow every Gereh name (T14). The keys are made
 * the moment the screen opens, on this phone, so those four digits are already real while the
 * user types; the arrow names the identity and saves it in the core's encrypted database.
 */
public class GerehIdentityActivity extends BaseFragment {
    private static final String TAG = "MLMCHAT";
    /** The core's limit for a name (T14), in UTF-8 bytes. */
    private static final int NAME_MAX_BYTES = 64;

    private PendingIdentity pending;
    private boolean creating;

    private EditTextBoldCursor nameField;
    private OutlineTextContainerView nameOutline;
    private TextView tagView;
    private TextView infoView;
    private FragmentFloatingButton floatingButton;

    @Override
    public boolean onFragmentCreate() {
        Utilities.globalQueue.postRunnable(() -> {
            PendingIdentity p;
            try {
                p = new PendingIdentity();
            } catch (Throwable t) {
                Log.e(TAG, "keys could not be made: " + t.getClass().getSimpleName());
                AndroidUtilities.runOnUIThread(() -> showFailure(t));
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (isFinished) {
                    p.close();
                    return;
                }
                pending = p;
                updateTag();
            });
        });
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        if (pending != null && !creating) {
            pending.close();
            pending = null;
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);

        SizeNotifierFrameLayout root = new SizeNotifierFrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = root;

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        scroll.addView(column, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        ImageView logo = new ImageView(context);
        logo.setImageResource(R.drawable.gereh_logo);
        column.addView(logo, LayoutHelper.createLinear(96, 96, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 20));

        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        title.setTypeface(AndroidUtilities.bold());
        title.setText(getString(R.string.GerehIdentityTitle));
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setGravity(Gravity.CENTER);
        title.setLineSpacing(dp(2), 1.0f);
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 0, 32, 0));

        TextView subtitle = new TextView(context);
        subtitle.setText(getString(R.string.GerehIdentityText));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(dp(2), 1.0f);
        column.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 8, 32, 0));

        // The field: the name, then the four digits that follow it, the way Telegram's phone field
        // holds the country code and the number.
        LinearLayout fieldRow = new LinearLayout(context);
        fieldRow.setOrientation(LinearLayout.HORIZONTAL);
        // Telegram's own language decides the side, not the phone's: in an English Gereh on a Persian
        // phone the digits still follow the name on the right.
        fieldRow.setLayoutDirection(LocaleController.isRTL ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        fieldRow.setGravity(Gravity.CENTER_VERTICAL);

        nameField = new EditTextBoldCursor(context);
        nameField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        nameField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        nameField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        nameField.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated));
        nameField.setCursorSize(dp(20));
        nameField.setCursorWidth(1.5f);
        nameField.setBackground(null);
        nameField.setPadding(0, 0, 0, 0);
        nameField.setSingleLine(true);
        nameField.setMaxLines(1);
        nameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        nameField.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        nameField.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        nameField.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        nameField.setFilters(new InputFilter[]{new NameFilter()});
        nameField.setContentDescription(getString(R.string.GerehIdentityField));
        nameField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onNext();
                return true;
            }
            return false;
        });
        nameField.setOnFocusChangeListener((v, hasFocus) -> nameOutline.animateSelection(hasFocus ? 1f : 0f));
        fieldRow.addView(nameField, LayoutHelper.createLinear(0, 36, 1f));

        tagView = new TextView(context);
        tagView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        tagView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        tagView.setSingleLine(true);
        tagView.setTextDirection(LocaleController.isRTL ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
        tagView.setText("·····");
        fieldRow.addView(tagView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 8, 0));

        nameOutline = new OutlineTextContainerView(context);
        nameOutline.setText(getString(R.string.GerehIdentityField));
        nameOutline.attachEditText(nameField);
        nameOutline.addView(fieldRow, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 16, 8, 16, 8));
        column.addView(nameOutline, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 58, 16, 24, 16, 8));

        infoView = new TextView(context);
        infoView.setText(getString(R.string.GerehIdentityTagInfo));
        infoView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        infoView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText6));
        infoView.setGravity(Gravity.CENTER);
        infoView.setLineSpacing(dp(2), 1.0f);
        column.addView(infoView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 4, 32, 96));

        floatingButton = new FragmentFloatingButton(context, getResourceProvider());
        VerticalPositionAutoAnimator.attach(floatingButton);
        root.addView(floatingButton, FragmentFloatingButton.createDefaultLayoutParamsBig());
        TransformableLoginButtonView arrow = new TransformableLoginButtonView(context);
        arrow.setTransformType(TransformableLoginButtonView.TRANSFORM_OPEN_ARROW);
        arrow.setProgress(1f);
        arrow.setDrawBackground(false);
        floatingButton.addView(arrow, LayoutHelper.createFrame(56, 56, Gravity.CENTER));
        floatingButton.addAdditionalView(arrow);
        floatingButton.setContentDescription(getString(R.string.Next));
        floatingButton.setOnClickListener(v -> onNext());
        floatingButton.setButtonVisible(true, false);

        updateTag();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getParentActivity() != null) {
            AndroidUtilities.setLightStatusBar(getParentActivity(), AndroidUtilities.computePerceivedBrightness(Theme.getColor(Theme.key_windowBackgroundWhite)) > 0.721f);
        }
        if (nameField != null) {
            AndroidUtilities.runOnUIThread(() -> {
                if (nameField != null && !creating) {
                    nameField.requestFocus();
                    AndroidUtilities.showKeyboard(nameField);
                }
            }, 200);
        }
    }

    private void updateTag() {
        if (tagView != null && pending != null) {
            tagView.setText("·" + pending.getTag());
        }
    }

    private void onNext() {
        if (creating || pending == null) {
            return;
        }
        final String name = nameField.getText().toString().trim();
        if (name.isEmpty()) {
            nameOutline.animateError(1f);
            AndroidUtilities.shakeViewSpring(nameOutline, 3.5f);
            AndroidUtilities.runOnUIThread(() -> nameOutline.animateError(0f), 1500);
            return;
        }
        creating = true;
        floatingButton.setProgressVisible(true, true);
        nameField.setEnabled(false);
        final PendingIdentity p = pending;
        pending = null;
        final Context context = getParentActivity();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                ChatNode node = p.create(context, name);
                GerehIdentity.adopt(node);
                final org.mlmchat.core.Me me = node.getMe();
                AndroidUtilities.runOnUIThread(() -> onCreated(me));
            } catch (Throwable t) {
                Log.e(TAG, "identity could not be made: " + t.getClass().getSimpleName());
                AndroidUtilities.runOnUIThread(() -> showFailure(t));
            }
        });
    }

    private void onCreated(org.mlmchat.core.Me me) {
        GerehIdentity.activate(currentAccount, me);
        Log.i(TAG, "identity created");
        org.mlmvpn.chat.bridge.Messenger.start(currentAccount);
        org.mlmvpn.chat.bridge.GerehService.startIfReady(getParentActivity());
        Bundle args = new Bundle();
        args.putBoolean("afterSignup", true);
        MainTabsActivity main = new MainTabsActivity();
        main.prepareDialogsActivity(args);
        presentFragment(main, true);
        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
    }

    private void showFailure(Throwable t) {
        creating = false;
        if (floatingButton != null) {
            floatingButton.setProgressVisible(false, true);
        }
        if (nameField != null) {
            nameField.setEnabled(true);
        }
        if (infoView != null) {
            // The core says why in English; a Persian screen gets the plain line instead.
            String why = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            infoView.setText(org.mlmvpn.chat.bridge.GerehLocale.persian() ? getString(R.string.GerehIdentityFailedPlain) : LocaleController.formatString(R.string.GerehIdentityFailed, why));
            infoView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        }
    }

    /**
     * A name is one line of at most 64 UTF-8 bytes (T14), without «·»: that dot comes before the
     * four digits, and a name that carried its own could pass for somebody else's.
     */
    private static final class NameFilter implements InputFilter {
        @Override
        public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
            StringBuilder kept = new StringBuilder(end - start);
            for (int i = start; i < end; i++) {
                char c = source.charAt(i);
                if (c != '·' && c != '\n' && c != '\r' && !Character.isISOControl(c)) {
                    kept.append(c);
                }
            }
            String before = dest.subSequence(0, dstart).toString() + dest.subSequence(dend, dest.length());
            int room = NAME_MAX_BYTES - before.getBytes(StandardCharsets.UTF_8).length;
            while (kept.length() > 0 && kept.toString().getBytes(StandardCharsets.UTF_8).length > room) {
                kept.setLength(kept.length() - 1);
                if (kept.length() > 0 && Character.isHighSurrogate(kept.charAt(kept.length() - 1))) {
                    kept.setLength(kept.length() - 1);
                }
            }
            if (kept.length() == end - start) {
                return null;
            }
            return kept;
        }
    }
}
