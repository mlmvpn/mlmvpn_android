package org.mlmvpn.chat.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.mlmvpn.chat.bridge.GerehLocale;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

/**
 * MLMCHAT: Settings › Language, in place of Telegram's list of its servers' languages.
 *
 * Three choices (Ehsan, 23 Oct 2026): «Same as MLMVPN», the default, which follows the language
 * set in MLMVPN's own settings; فارسی; English. The choice is kept by {@link GerehLocale} and
 * survives restarts; picking it switches the chat at once (Telegram rebuilds its screens).
 */
public final class GerehLanguageActivity extends BaseFragment {
    private static final int FOLLOW = 1, FA = 2, EN = 3, RU = 4, ZH = 5;

    private UniversalRecyclerView listView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.Language));
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

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        String choice = GerehLocale.choice();
        items.add(UItem.asHeader(getString(R.string.GerehLanguageHeader)));
        items.add(UItem.asRadio(FOLLOW, getString(R.string.GerehLanguageFollow), name(GerehLocale.mlmvpnLanguage()))
                .setChecked(GerehLocale.FOLLOW.equals(choice)));
        items.add(UItem.asRadio(FA, "فارسی", "Persian").setChecked(GerehLocale.FA.equals(choice)));
        items.add(UItem.asRadio(EN, "English", "English").setChecked(GerehLocale.EN.equals(choice)));
        items.add(UItem.asRadio(RU, "Русский", "Russian").setChecked(GerehLocale.RU.equals(choice)));
        items.add(UItem.asRadio(ZH, "简体中文", "Chinese (Simplified)").setChecked(GerehLocale.ZH.equals(choice)));
        items.add(UItem.asShadow(getString(R.string.GerehLanguageInfo)));
    }

    /** A language by its own name, as Telegram's list shows it. */
    private static String name(String code) {
        switch (code) {
            case GerehLocale.FA: return "فارسی";
            case GerehLocale.RU: return "Русский";
            case GerehLocale.ZH: return "简体中文";
            default: return "English";
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.viewType != UniversalAdapter.VIEW_TYPE_RADIO) {
            return;
        }
        String choice = item.id == FA ? GerehLocale.FA : item.id == EN ? GerehLocale.EN : item.id == RU ? GerehLocale.RU
                : item.id == ZH ? GerehLocale.ZH : GerehLocale.FOLLOW;
        GerehLocale.setChoice(choice);
        // A new language rebuilds every screen, this one included; the same one only moves the check.
        if (!GerehLocale.sync(currentAccount) && listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }
}
