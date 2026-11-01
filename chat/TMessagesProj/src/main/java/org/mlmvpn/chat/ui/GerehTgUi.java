package org.mlmvpn.chat.ui;

import static org.telegram.messenger.LocaleController.formatPluralString;
import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.mlmvpn.chat.bridge.GerehGas;
import org.mlmvpn.chat.bridge.GerehTg;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LineProgressView;
import org.telegram.ui.Components.NumberPicker;

import java.util.ArrayList;
import java.util.List;

/**
 * MLMCHAT: the screens of «کانال تلگرام محبوب» (T68): the row under «کانال جدید», the add sheet
 * (how many posts, files or not, the size limit, now or later, the timer), the filling line with
 * its steps, the report of what it cost, and in the channel «به‌روزرسانی» in place of «بی‌صدا»
 * with the timer's menu in Telegram's own shape. The work itself is GerehTg's.
 */
public final class GerehTgUi {
    /** The timer's wheel: minutes. */
    private static final int[] WHEEL = {15, 20, 30, 45, 60, 120, 180, 240, 300, 360, 480, 600, 720, 960, 1200, 1440, 2880, 4320, 7200, 10080};

    private GerehTgUi() {
    }

    // ---- the row in «پیام تازه» -------------------------------------------------------------

    /** First the user's own Telegram script (made here, step by step, if it is not yet), then the sheet. */
    public static void startAdd(BaseFragment f) {
        startAdd(f, null);
    }

    /** The same, with a channel's name already written in (a search's Telegram result, q6). */
    public static void startAdd(BaseFragment f, String name) {
        if (!GerehTg.loaded()) {
            BulletinFactory.of(f).createSimpleBulletin(R.raw.chats_infotip, getString(R.string.GerehTgWait)).show();
            return;
        }
        if (!GerehTg.hasReader()) {
            f.presentFragment(new GerehTgSetupActivity(f));
            return;
        }
        showAddSheet(f, name);
    }

    /** The user's Telegram script, made or made again (a search with no script, or one too old to search). */
    public static void openSetup(BaseFragment f) {
        f.presentFragment(new GerehTgSetupActivity(f));
    }

    static void showAddSheet(BaseFragment f) {
        showAddSheet(f, null);
    }

    static void showAddSheet(BaseFragment f, String prefill) {
        Context ctx = f.getParentActivity();
        if (ctx == null) {
            return;
        }
        GerehTg.Options o = new GerehTg.Options();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        EditTextBoldCursor name = new EditTextBoldCursor(ctx);
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        name.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        name.setHintText(getString(R.string.GerehTgAddHint));
        name.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        name.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated), Theme.getColor(Theme.key_text_RedRegular));
        name.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        name.setCursorSize(AndroidUtilities.dp(20));
        name.setCursorWidth(1.5f);
        name.setPadding(0, 0, 0, AndroidUtilities.dp(8));
        name.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        String clip = prefill != null ? prefill : GerehGas.clipboardText(ctx);
        if (GerehTg.nameFrom(clip) != null) {
            name.setText(clip.trim());
        }
        box.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 24, 6, 24, 8));

        TextSettingsCell count = new TextSettingsCell(ctx);
        TextCheckCell files = new TextCheckCell(ctx);
        TextSettingsCell cap = new TextSettingsCell(ctx);
        TextSettingsCell when = new TextSettingsCell(ctx);
        TextSettingsCell every = new TextSettingsCell(ctx);
        Runnable fill = () -> {
            count.setTextAndValue(getString(R.string.GerehTgCount), String.valueOf(o.count), true);
            files.setTextAndCheck(getString(R.string.GerehTgFiles), o.files, true);
            cap.setTextAndValue(getString(R.string.GerehTgCap), formatString(R.string.GerehTgMb, o.capMb), true);
            when.setTextAndValue(getString(R.string.GerehTgDownload), getString(o.later ? R.string.GerehTgDownloadLater : R.string.GerehTgDownloadNow), true);
            every.setTextAndValue(getString(R.string.GerehTgEvery), everyText(o.every), false);
            cap.setEnabled(o.files, null);
            when.setEnabled(o.files, null);
        };
        fill.run();
        for (View v : new View[]{count, files, cap, when, every}) {
            v.setBackground(Theme.getSelectorDrawable(false));
            box.addView(v, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        count.setOnClickListener(v -> choose(ctx, getString(R.string.GerehTgCount), labels(GerehTg.COUNTS, null), i -> {
            o.count = GerehTg.COUNTS[i];
            fill.run();
        }));
        files.setOnClickListener(v -> {
            o.files = !o.files;
            fill.run();
        });
        cap.setOnClickListener(v -> {
            if (o.files) {
                choose(ctx, getString(R.string.GerehTgCap), labels(GerehTg.CAPS_MB, R.string.GerehTgMb), i -> {
                    o.capMb = GerehTg.CAPS_MB[i];
                    fill.run();
                });
            }
        });
        when.setOnClickListener(v -> {
            if (o.files) {
                choose(ctx, getString(R.string.GerehTgDownload), new String[]{getString(R.string.GerehTgDownloadNow), getString(R.string.GerehTgDownloadLater)}, i -> {
                    o.later = i == 1;
                    fill.run();
                });
            }
        });
        every.setOnClickListener(v -> chooseEvery(f, o.every, m -> {
            o.every = m;
            fill.run();
        }));
        TextView note = new TextView(ctx);
        note.setText(getString(R.string.GerehTgLimits));
        note.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        note.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        box.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 4));

        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(getString(R.string.GerehTgNew));
        b.setView(box);
        b.setPositiveButton(getString(R.string.GerehTgAdd), null);
        b.setNegativeButton(getString(R.string.Cancel), null);
        AlertDialog d = b.create();
        f.showDialog(d);
        View add = d.getButton(AlertDialog.BUTTON_POSITIVE);
        if (add != null) {
            add.setOnClickListener(v -> {
                String n = GerehTg.nameFrom(name.getText() != null ? name.getText().toString() : "");
                if (n == null) {
                    AndroidUtilities.shakeViewSpring(name, -6);
                    BulletinFactory.of(f).createSimpleBulletin(R.raw.error, getString(R.string.GerehTgBadName)).show();
                    return;
                }
                long had = GerehTg.chatOfName(n);
                d.dismiss();
                if (had != 0) {
                    open(f, had);
                    return;
                }
                runAdd(f, n, o);
            });
        }
    }

    // ---- the filling line ------------------------------------------------------------------

    private static void runAdd(BaseFragment f, String name, GerehTg.Options o) {
        Context ctx = f.getParentActivity();
        if (ctx == null) {
            return;
        }
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        LineProgressView line = new LineProgressView(ctx);
        line.setProgressColor(Theme.getColor(Theme.key_dialogLineProgress));
        line.setBackColor(Theme.getColor(Theme.key_dialogLineProgressBackground));
        box.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 4, 24, 12, 24, 12));
        TextView now = new TextView(ctx);
        now.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        now.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        box.addView(now, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));
        TextView past = new TextView(ctx);
        past.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        past.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        box.addView(past, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 8));

        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(formatString(R.string.GerehTgAdding, "@" + name));
        b.setView(box);
        GerehTg.Job[] job = new GerehTg.Job[1];
        b.setNegativeButton(getString(R.string.Cancel), (di, w) -> {
            if (job[0] != null) {
                job[0].cancel();
            }
        });
        AlertDialog d = b.create();
        d.setCanceledOnTouchOutside(false);
        d.setCancelable(false);
        f.showDialog(d);
        List<String> done = new ArrayList<>();
        int[] last = {-1};
        String[] lastText = {""};
        job[0] = GerehTg.add(name, o, (step, n, total, bytes, bytesTotal) -> {
            if (step != last[0] && last[0] >= 0) {
                done.add("✓ " + lastText[0]);
                past.setText(android.text.TextUtils.join("\n", done));
            }
            last[0] = step;
            lastText[0] = stepText(step, n, total, bytes, bytesTotal);
            now.setText(lastText[0]);
            float part = total > 0 ? Math.min(1f, n / (float) total) : 0f;
            line.setProgress(Math.min(1f, (step + part) / 6f), true);
        }, r -> {
            try {
                d.dismiss();
            } catch (Throwable ignored) {
            }
            if (r.ok) {
                report(f, r);
            } else if ("exists".equals(r.error) && r.chatId != 0) {
                open(f, r.chatId);
            } else if (!"cancelled".equals(r.error)) {
                failed(f, r, () -> runAdd(f, name, o));
            }
        });
    }

    private static String stepText(int step, int n, int total, long bytes, long bytesTotal) {
        switch (step) {
            case GerehTg.STEP_CONNECT: return getString(R.string.GerehTgStepConnect);
            case GerehTg.STEP_POSTS: return formatString(R.string.GerehTgStepPosts, n, total);
            case GerehTg.STEP_PHOTO: return getString(R.string.GerehTgStepPhoto);
            case GerehTg.STEP_SIZES: return getString(R.string.GerehTgStepSizes);
            case GerehTg.STEP_FILES: return formatString(R.string.GerehTgStepFiles, n, total, AndroidUtilities.formatFileSize(bytes));
            default: return getString(R.string.GerehTgStepBuild);
        }
    }

    /** What adding cost, in plain words (Ehsan's 30 Oct list, item 2). */
    private static void report(BaseFragment f, GerehTg.Result r) {
        Context ctx = f.getParentActivity();
        if (ctx == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(r.title);
        b.setMessage(formatString(R.string.GerehTgAdded, r.posts, r.files, AndroidUtilities.formatFileSize(r.up + r.down), r.requests));
        b.setPositiveButton(getString(R.string.GerehTgOpen), (di, w) -> open(f, r.chatId));
        b.setNegativeButton(getString(R.string.Close), null);
        f.showDialog(b.create());
    }

    private static void failed(BaseFragment f, GerehTg.Result r, Runnable retry) {
        Context ctx = f.getParentActivity();
        if (ctx == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(getString(R.string.GerehTgNew));
        b.setMessage(errorText(r.error));
        b.setPositiveButton(getString(R.string.GerehGasRetry), (di, w) -> retry.run());
        b.setNegativeButton(getString(R.string.Close), null);
        f.showDialog(b.create());
    }

    static String errorText(String code) {
        switch (code == null ? "" : code) {
            case "not_public": return getString(R.string.GerehTgErrNotPublic);
            case "telegram": return getString(R.string.GerehTgErrTelegram);
            case "not_reader":
            case "bad_response": return getString(R.string.GerehTgErrReader);
            case "auth": return getString(R.string.GerehTgHintAuth);
            case "busy": return getString(R.string.GerehTgUpdating);
            case "no_reader": return getString(R.string.GerehTgErrReader);
            default: return GerehGas.hintText(code);
        }
    }

    private static void open(BaseFragment f, long chatId) {
        org.telegram.tgnet.TLRPC.Chat chat = GerehTg.chat(chatId);
        if (chat != null) {
            MessagesController.getInstance(UserConfig.selectedAccount).putChat(chat, false);
        }
        Bundle args = new Bundle();
        args.putLong("chat_id", chatId);
        f.presentFragment(new ChatActivity(args));
    }

    // ---- in the channel ---------------------------------------------------------------------

    public static boolean isTg(long dialogId) {
        return GerehTg.isTg(dialogId);
    }

    /** The bottom button's word: «به‌روزرسانی», or «در حال به‌روزرسانی…» while it works. */
    public static String buttonText(long dialogId) {
        return getString(GerehTg.isBusy(dialogId) ? R.string.GerehTgUpdating : R.string.GerehTgUpdate);
    }

    /** A tap on «به‌روزرسانی»: new posts now, and what it cost. */
    public static void onUpdateTap(BaseFragment f, long dialogId, Runnable refresh) {
        if (GerehTg.isBusy(dialogId)) {
            return;
        }
        GerehTg.update(dialogId, r -> {
            refresh.run();
            if (f.getParentActivity() == null) {
                return;
            }
            String cost = AndroidUtilities.formatFileSize(r.up + r.down);
            String text = !r.ok ? errorText(r.error)
                    : r.fresh > 0 ? formatString(R.string.GerehTgFresh, r.fresh, cost, r.requests)
                    : formatString(R.string.GerehTgNoFresh, cost, r.requests);
            BulletinFactory.of(f).createSimpleBulletin(r.ok ? R.raw.chats_infotip : R.raw.error, text).show();
        });
        AndroidUtilities.runOnUIThread(refresh, 50);
    }

    /** Holding «به‌روزرسانی»: the timer and the files, in the shape of Telegram's «بی‌صدا برای…» menu. */
    public static boolean onUpdateHold(BaseFragment f, long dialogId, View anchor, Runnable refresh) {
        Context ctx = f.getParentActivity();
        if (ctx == null || f.getFragmentView() == null) {
            return false;
        }
        GerehTg.Options o = GerehTg.options(dialogId);
        ActionBarPopupWindow.ActionBarPopupWindowLayout layout = new ActionBarPopupWindow.ActionBarPopupWindowLayout(ctx, R.drawable.popup_fixed_alert, f.getResourceProvider());
        layout.setFitItems(true);
        ActionBarPopupWindow[] win = new ActionBarPopupWindow[1];
        int[] choices = {GerehTg.EVERY[0], GerehTg.EVERY[1], GerehTg.EVERY[2], GerehTg.EVERY[3]};
        int[] icons = {R.drawable.msg_mute_period, R.drawable.msg_mute_1h, R.drawable.msg_mute_period, R.drawable.msg_mute_period};
        for (int i = 0; i < choices.length; i++) {
            int m = choices[i];
            ActionBarMenuSubItem it = ActionBarMenuItem.addItem(layout, icons[i], mark(everyText(m), o.every == m), false, f.getResourceProvider());
            it.setOnClickListener(v -> {
                win[0].dismiss();
                o.every = m;
                GerehTg.setOptions(dialogId, o);
                refresh.run();
            });
        }
        boolean custom = o.every > 0 && !in(GerehTg.EVERY, o.every);
        ActionBarMenuSubItem pick = ActionBarMenuItem.addItem(layout, R.drawable.msg_mute_period, mark(custom ? everyText(o.every) : getString(R.string.GerehTgEveryCustom), custom), false, f.getResourceProvider());
        pick.setOnClickListener(v -> {
            win[0].dismiss();
            wheel(f, o.every, m -> {
                o.every = m;
                GerehTg.setOptions(dialogId, o);
                refresh.run();
            });
        });
        ActionBarMenuSubItem off = ActionBarMenuItem.addItem(layout, R.drawable.msg_mute, mark(getString(R.string.GerehTgEveryOff), o.every == 0), false, f.getResourceProvider());
        off.setOnClickListener(v -> {
            win[0].dismiss();
            o.every = 0;
            GerehTg.setOptions(dialogId, o);
            refresh.run();
        });
        ActionBarMenuSubItem fs = ActionBarMenuItem.addItem(layout, R.drawable.msg_download_settings, getString(R.string.GerehTgFileSettings), false, f.getResourceProvider());
        fs.setOnClickListener(v -> {
            win[0].dismiss();
            fileSettings(f, dialogId);
        });
        long[] u = GerehTg.usage(dialogId);
        TextView usage = new TextView(ctx);
        usage.setText(formatString(R.string.GerehTgUsage, AndroidUtilities.formatFileSize(u[1] + u[2]), (int) u[0]));
        usage.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        usage.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, f.getResourceProvider()));
        usage.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        layout.addView(usage, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        win[0] = new ActionBarPopupWindow(layout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        win[0].setPauseNotifications(true);
        win[0].setDismissAnimationDuration(220);
        win[0].setOutsideTouchable(true);
        win[0].setClippingEnabled(true);
        win[0].setAnimationStyle(R.style.PopupContextAnimation);
        win[0].setFocusable(true);
        layout.measure(View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(1000), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(1000), View.MeasureSpec.AT_MOST));
        win[0].setInputMethodMode(ActionBarPopupWindow.INPUT_METHOD_NOT_NEEDED);
        int[] loc = new int[2];
        anchor.getLocationInWindow(loc);
        int x = loc[0] + anchor.getWidth() / 2 - layout.getMeasuredWidth() / 2;
        int y = loc[1] - layout.getMeasuredHeight() - AndroidUtilities.dp(8);
        win[0].showAtLocation(f.getFragmentView(), Gravity.LEFT | Gravity.TOP, Math.max(AndroidUtilities.dp(8), x), Math.max(AndroidUtilities.dp(8), y));
        win[0].dimBehind();
        return true;
    }

    private static void fileSettings(BaseFragment f, long dialogId) {
        Context ctx = f.getParentActivity();
        if (ctx == null) {
            return;
        }
        GerehTg.Options o = GerehTg.options(dialogId);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        TextCheckCell files = new TextCheckCell(ctx);
        TextSettingsCell cap = new TextSettingsCell(ctx);
        TextSettingsCell when = new TextSettingsCell(ctx);
        Runnable fill = () -> {
            files.setTextAndCheck(getString(R.string.GerehTgFiles), o.files, true);
            cap.setTextAndValue(getString(R.string.GerehTgCap), formatString(R.string.GerehTgMb, o.capMb), true);
            when.setTextAndValue(getString(R.string.GerehTgDownload), getString(o.later ? R.string.GerehTgDownloadLater : R.string.GerehTgDownloadNow), false);
            cap.setEnabled(o.files, null);
            when.setEnabled(o.files, null);
        };
        fill.run();
        for (View v : new View[]{files, cap, when}) {
            v.setBackground(Theme.getSelectorDrawable(false));
            box.addView(v, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        files.setOnClickListener(v -> {
            o.files = !o.files;
            fill.run();
        });
        cap.setOnClickListener(v -> {
            if (o.files) {
                choose(ctx, getString(R.string.GerehTgCap), labels(GerehTg.CAPS_MB, R.string.GerehTgMb), i -> {
                    o.capMb = GerehTg.CAPS_MB[i];
                    fill.run();
                });
            }
        });
        when.setOnClickListener(v -> {
            if (o.files) {
                choose(ctx, getString(R.string.GerehTgDownload), new String[]{getString(R.string.GerehTgDownloadNow), getString(R.string.GerehTgDownloadLater)}, i -> {
                    o.later = i == 1;
                    fill.run();
                });
            }
        });
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(getString(R.string.GerehTgFileSettings));
        b.setView(box);
        b.setPositiveButton(getString(R.string.OK), (di, w) -> GerehTg.setOptions(dialogId, o));
        b.setNegativeButton(getString(R.string.Cancel), null);
        f.showDialog(b.create());
    }

    // ---- choosing ---------------------------------------------------------------------------

    private interface Pick {
        void on(int value);
    }

    private static void choose(Context ctx, String title, String[] items, Pick pick) {
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(title);
        b.setItems(items, (di, w) -> pick.on(w));
        b.show();
    }

    private static void chooseEvery(BaseFragment f, int current, Pick pick) {
        String[] items = {everyText(GerehTg.EVERY[0]), everyText(GerehTg.EVERY[1]), everyText(GerehTg.EVERY[2]), everyText(GerehTg.EVERY[3]),
                getString(R.string.GerehTgEveryCustom), getString(R.string.GerehTgEveryOff)};
        choose(f.getParentActivity(), getString(R.string.GerehTgEvery), items, i -> {
            if (i < 4) {
                pick.on(GerehTg.EVERY[i]);
            } else if (i == 4) {
                wheel(f, current, pick);
            } else {
                pick.on(0);
            }
        });
    }

    /** «دلخواه…»: Telegram's own wheel, from 15 minutes to a week. */
    private static void wheel(BaseFragment f, int current, Pick pick) {
        Context ctx = f.getParentActivity();
        if (ctx == null) {
            return;
        }
        NumberPicker np = new NumberPicker(ctx, f.getResourceProvider());
        np.setMinValue(0);
        np.setMaxValue(WHEEL.length - 1);
        np.setWrapSelectorWheel(false);
        np.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        np.setFormatter(GerehTgUi::wheelText);
        int at = 4;
        for (int i = 0; i < WHEEL.length; i++) {
            if (WHEEL[i] == current) {
                at = i;
            }
        }
        np.setValue(at);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(np, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 220, 24, 0, 24, 0));
        AlertDialog.Builder b = new AlertDialog.Builder(ctx);
        b.setTitle(getString(R.string.GerehTgEvery));
        b.setView(box);
        b.setPositiveButton(getString(R.string.OK), (di, w) -> pick.on(WHEEL[np.getValue()]));
        b.setNegativeButton(getString(R.string.Cancel), null);
        f.showDialog(b.create());
    }

    private static String wheelText(int i) {
        int m = WHEEL[i];
        if (m < 60) {
            return formatPluralString("Minutes", m);
        }
        if (m < 1440 || m % 1440 != 0) {
            return formatPluralString("Hours", m / 60);
        }
        return formatPluralString("Days", m / 1440);
    }

    /** «هر ۳۰ دقیقه»، «هر ۱ ساعت»، «روزی یک بار»، «خاموش». */
    public static String everyText(int minutes) {
        if (minutes <= 0) {
            return getString(R.string.GerehTgEveryOff);
        }
        if (minutes == 1440) {
            return getString(R.string.GerehTgEveryDay);
        }
        String span = minutes < 60 ? formatPluralString("Minutes", minutes)
                : minutes % 1440 == 0 ? formatPluralString("Days", minutes / 1440)
                : formatPluralString("Hours", minutes / 60);
        return formatString(R.string.GerehTgEveryN, span);
    }

    private static String[] labels(int[] values, Integer format) {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = format != null ? formatString(format, values[i]) : String.valueOf(values[i]);
        }
        return out;
    }

    private static boolean in(int[] values, int v) {
        for (int x : values) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }

    private static String mark(String s, boolean on) {
        return on ? s + "  ✓" : s;
    }
}
