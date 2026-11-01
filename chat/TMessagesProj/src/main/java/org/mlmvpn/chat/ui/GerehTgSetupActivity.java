package org.mlmvpn.chat.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.mlmvpn.chat.bridge.GerehGas;
import org.mlmvpn.chat.bridge.GerehTg;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/**
 * MLMCHAT: the user's own Telegram reader script (T68), made step by step the way a Google relay is
 * (GerehGasSetupActivity), but apart from it (Ehsan, 30 Oct 2026: «اسکریپت برای این سیستم رو جدا
 * کن»): copy the script with a key made on this phone, make a web app of it at script.google.com in
 * a new project, paste its deployment back; the phone asks it once, keeps it, and goes on to the
 * add sheet. The key of a script copied but not finished is kept, so leaving and coming back does
 * not break the script already pasted at Google.
 */
public final class GerehTgSetupActivity extends BaseFragment {
    private static final int COPY = 1, OPEN = 2, PASTE = 3, TYPE = 4, RETRY = 5, NEXT = 6;
    private static final String CREATE_URL = "https://script.google.com/home/projects/create";

    private final BaseFragment opener;
    private final String key;
    private String deployment;
    private boolean busy;
    /** 0 = the steps, 1 = it works, 2 = it did not answer as it should. */
    private int state;
    private String failure = "";
    private UniversalRecyclerView listView;

    public GerehTgSetupActivity(BaseFragment opener) {
        this.opener = opener;
        SharedPreferences p = ApplicationLoader.applicationContext.getSharedPreferences("gereh_tg", Context.MODE_PRIVATE);
        String k = p.getString("draft_key", null);
        if (k == null) {
            k = GerehTg.newKey();
            p.edit().putString("draft_key", k).apply();
        }
        key = k;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.GerehTgSetupTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, resourceProvider));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        return fragmentView = contentView;
    }

    private void update() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (state == 1) {
            // done: the steps go, and adding a channel is the one thing left (Ehsan, 30 Oct 2026:
            // the confirmation sat unseen below the steps)
            items.add(UItem.asHeader(getString(R.string.GerehGasResultHeader)));
            items.add(UItem.asShadow(getString(R.string.GerehTgSetupOk)));
            items.add(UItem.asButton(NEXT, getString(R.string.GerehTgSetupNext)));
            return;
        }
        items.add(UItem.asShadow(getString(R.string.GerehTgSetupIntro)));
        items.add(UItem.asHeader(getString(R.string.GerehGasStep1Header)));
        items.add(UItem.asShadow(getString(R.string.GerehTgSetupStep1Text)));
        items.add(UItem.asButton(COPY, getString(R.string.GerehGasCopyScript)));
        items.add(UItem.asHeader(getString(R.string.GerehGasStep2Header)));
        items.add(UItem.asShadow(getString(R.string.GerehTgSetupStep2Text)));
        items.add(UItem.asButton(OPEN, getString(R.string.GerehGasOpenGoogle)));
        items.add(UItem.asHeader(getString(R.string.GerehGasStep3Header)));
        items.add(UItem.asShadow(getString(R.string.GerehTgSetupStep3Text)));
        items.add(UItem.asButton(PASTE, getString(busy ? R.string.GerehGasChecking : R.string.GerehGasPasteDeployment)));
        items.add(UItem.asButton(TYPE, getString(R.string.GerehGasTypeDeployment)));
        if (state == 2) {
            items.add(UItem.asHeader(getString(R.string.GerehGasResultHeader)));
            items.add(UItem.asShadow(GerehTgUi.errorText(failure)));
            items.add(UItem.asButton(RETRY, getString(R.string.GerehGasRetry)));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        switch (item.id) {
            case COPY:
                try {
                    AndroidUtilities.addToClipboard(GerehTg.readerScript(key));
                    BulletinFactory.of(this).createCopyBulletin(getString(R.string.GerehGasScriptCopied)).show();
                } catch (Throwable e) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.GerehGasFailed)).show();
                }
                break;
            case OPEN:
                try {
                    if (getParentActivity() != null) {
                        getParentActivity().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(CREATE_URL)));
                    }
                } catch (Throwable e) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.GerehGasNoBrowser)).show();
                }
                break;
            case PASTE:
                check(GerehGas.clipboardText(getParentActivity()));
                break;
            case TYPE:
                GerehGas.ask(this, getString(R.string.GerehGasTypeDeployment), getString(R.string.GerehGasTypeAsk), "", getString(R.string.GerehGasDeploymentHint),
                        getString(R.string.GerehGasNext), this::check);
                break;
            case RETRY:
                check(deployment);
                break;
            case NEXT:
                goAdd();
                break;
            default:
                break;
        }
    }

    /** Step 3: the deployment pasted back is asked once; when it answers as a Telegram reader, it is kept. */
    private void check(String text) {
        if (busy) {
            return;
        }
        String d = GerehTg.deploymentFrom(text);
        if (d == null) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.GerehTgSetupBadId)).show();
            return;
        }
        deployment = d;
        busy = true;
        state = 0;
        update();
        GerehTg.testReader(d, key, r -> {
            busy = false;
            state = r.ok ? 1 : 2;
            failure = r.error;
            if (r.ok) {
                ApplicationLoader.applicationContext.getSharedPreferences("gereh_tg", Context.MODE_PRIVATE).edit().remove("draft_key").apply();
            }
            update();
            result(r.ok);
        });
    }

    /** The outcome in a popup, where it is seen: works, «افزودن کانال»; or what to fix, «آزمایش دوباره». */
    private void result(boolean ok) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), resourceProvider);
        b.setTitle(getString(R.string.GerehTgSetupTitle));
        if (ok) {
            b.setMessage(getString(R.string.GerehTgSetupOk));
            b.setPositiveButton(getString(R.string.GerehTgSetupNext), (di, w) -> goAdd());
            b.setNegativeButton(getString(R.string.GerehGasLater), null);
        } else {
            b.setMessage(GerehTgUi.errorText(failure));
            b.setPositiveButton(getString(R.string.GerehGasRetry), (di, w) -> check(deployment));
            b.setNegativeButton(getString(R.string.Close), null);
        }
        showDialog(b.create());
    }

    /** The script works: this screen goes and the add sheet opens where the user came from. */
    private void goAdd() {
        finishFragment();
        if (opener != null) {
            AndroidUtilities.runOnUIThread(() -> GerehTgUi.showAddSheet(opener), 300);
        }
    }
}
