package org.mlmvpn.chat.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.GasDay;
import org.mlmchat.core.GasOverview;
import org.mlmchat.core.GasRelayView;
import org.mlmchat.core.GasServer;
import org.mlmchat.core.GasTestReport;
import org.mlmchat.core.GasTestStep;
import org.mlmvpn.chat.bridge.GerehGas;
import org.mlmvpn.chat.bridge.GerehIdentity;
import org.telegram.messenger.AndroidUtilities;
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
 * MLMCHAT: one Google relay (SPEC 19): how it is doing, what this phone used it for (requests,
 * messages, traffic, failures), and for a relay made here the script's own count (all requests,
 * how many devices it helped, who had mail waiting) and who may use it: pause it for everyone else,
 * limit the devices a day, share it, remove it. Nothing is hidden: this is the user's own Google
 * account, and everything it does is shown.
 */
public final class GerehGasDetailActivity extends BaseFragment {
    private static final int ON = 1, ANNOUNCE = 2, RENAME = 3, TEST = 4, SHARE = 5, SCRIPT = 6, REMOVE = 7, PAUSE = 8, LIMIT = 9, SERVER = 10, PUBLIC = 11, REDEPLOY = 12;
    private static final long REFRESH_MS = 4000;
    private static final int[] LIMITS = {5, 10, 30, 100, 200, 0};

    private final String rid;
    private UniversalRecyclerView listView;
    private GasRelayView relay;
    private boolean testing, reading;
    /** The first answer came (until then the screen is empty, not «gone»). */
    private boolean loaded;
    private final Runnable refresh = this::refresh;

    public GerehGasDetailActivity(String rid) {
        this.rid = rid;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.GerehGasDetailTitle));
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
        load();
        return fragmentView = contentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        load();
        AndroidUtilities.runOnUIThread(refresh, REFRESH_MS);
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(refresh);
    }

    private void refresh() {
        load();
        AndroidUtilities.runOnUIThread(refresh, REFRESH_MS);
    }

    private void load() {
        GerehGas.async(() -> {
            GasRelayView found = null;
            try {
                ChatNode node = GerehIdentity.node();
                GasOverview o = node == null ? null : node.gasOverview();
                if (o != null) {
                    for (GasRelayView r : o.getRelays()) {
                        if (r.getRid().equals(rid)) {
                            found = r;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            GasRelayView result = found;
            AndroidUtilities.runOnUIThread(() -> {
                boolean first = relay == null && result != null;
                relay = result;
                loaded = true;
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
                // the owner's own count is read once on opening, then when asked
                if (first && result.getMine() && !result.getDraft() && result.getServer() == null) {
                    readServer(false);
                }
            });
        });
    }

    private static String counts(GasDay d) {
        return formatString(R.string.GerehGasCounts, (int) d.getReq(), (int) d.getItemsIn(), (int) d.getItemsOut());
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        GasRelayView r = relay;
        if (r == null) {
            if (loaded) {
                items.add(UItem.asShadow(getString(R.string.GerehGasGone)));
            }
            return;
        }
        items.add(UItem.asHeader(r.getName()));
        if (r.isBorrowed()) {
            // someone's public relay, borrowed because we have none of our own (SPEC 19.12)
            items.add(UItem.asShadow(getString(R.string.GerehGasBorrowedInfo)));
        }
        items.add(UItem.asButton(RENAME, getString(R.string.GerehGasName), r.getName()));
        items.add(UItem.asButton(0, getString(R.string.GerehGasState), GerehGas.stateText(r.getState())));
        if (r.getCombined()) {
            // a combined relay (SPEC 19.13): its Worker, and the door this network uses
            items.add(UItem.asButton(0, getString(R.string.GerehComboWorkerHost), r.getWorker().isEmpty() ? "—" : r.getWorker()));
            if (!r.getDoor().isEmpty()) {
                items.add(UItem.asButton(0, getString(R.string.GerehComboDoorNow), getString("cloudflare".equals(r.getDoor()) ? R.string.GerehComboDoorCf : R.string.GerehComboDoorGoogle)));
            }
        }
        if (r.getLastOkMs() > 0) {
            items.add(UItem.asButton(0, getString(R.string.GerehGasLastOk), GerehGas.ago(r.getLastOkMs())));
        }
        items.add(UItem.asCheck(ON, getString(R.string.GerehGasUse)).setChecked(r.getEnabled()));
        items.add(UItem.asCheck(ANNOUNCE, getString(R.string.GerehGasAnnounce)).setChecked(r.getAnnounce()));
        items.add(UItem.asShadow(getString(R.string.GerehGasAnnounceInfo)));

        items.add(UItem.asHeader(getString(R.string.GerehGasUseHeader)));
        items.add(UItem.asButton(0, getString(R.string.GerehGasToday), counts(r.getToday())));
        items.add(UItem.asButton(0, getString(R.string.GerehGasWeek), counts(r.getWeek())));
        items.add(UItem.asButton(0, getString(R.string.GerehGasAll), counts(r.getTotal())));
        items.add(UItem.asButton(0, getString(R.string.GerehGasTraffic),
                "↑ " + GerehGas.size(r.getToday().getBytesOut()) + "  ↓ " + GerehGas.size(r.getToday().getBytesIn())));
        items.add(UItem.asButton(0, getString(R.string.GerehGasErrors), String.valueOf(r.getToday().getErrors())));
        if (r.getTotal().getMs() > 0) {
            items.add(UItem.asButton(0, getString(R.string.GerehGasSpeed), formatString(R.string.GerehGasMs, (int) r.getTotal().getMs())));
        }
        items.add(UItem.asButton(0, getString(R.string.GerehGasWaitingHere), String.valueOf(r.getWaiting())));
        items.add(UItem.asShadow(getString(R.string.GerehGasUseInfo)));

        if (r.getMine() && !r.getDraft()) {
            GasServer s = r.getServer();
            items.add(UItem.asHeader(getString(R.string.GerehGasServerHeader)));
            if (s == null) {
                items.add(UItem.asButton(0, getString(R.string.GerehGasServerNone)));
            } else {
                items.add(UItem.asButton(0, getString(R.string.GerehGasServerReqs), String.valueOf(s.getRequests())));
                items.add(UItem.asButton(0, getString(R.string.GerehGasServerDevices), String.valueOf(s.getDevices())));
                items.add(UItem.asButton(0, getString(R.string.GerehGasServerPeople), String.valueOf(s.getRecipients())));
                items.add(UItem.asButton(0, getString(R.string.GerehGasServerPuts), formatString(R.string.GerehGasServerPutsValue, (int) s.getItemsOut(), (int) s.getItemsIn())));
                items.add(UItem.asButton(0, getString(R.string.GerehGasServerRead), GerehGas.ago(s.getAtMs())));
            }
            items.add(UItem.asButton(SERVER, getString(reading ? R.string.GerehGasServerReading : R.string.GerehGasServerRefresh)));
            items.add(UItem.asShadow(getString(R.string.GerehGasServerInfo)));

            items.add(UItem.asHeader(getString(R.string.GerehGasOwnerHeader)));
            items.add(UItem.asCheck(PAUSE, getString(R.string.GerehGasPause)).setChecked(s != null && s.getPaused()));
            String limitValue = s == null ? getString(R.string.GerehGasServerNone)
                    : s.getMaxClients() == 0 ? getString(R.string.GerehGasNoLimit) : String.valueOf(s.getMaxClients());
            items.add(UItem.asButton(LIMIT, getString(R.string.GerehGasLimit), limitValue));
            items.add(UItem.asShadow(getString(R.string.GerehGasOwnerInfo)));
            if (!r.getDraft()) {
                items.add(UItem.asCheck(PUBLIC, getString(R.string.GerehGasPublic)).setChecked(r.isPublic()));
                items.add(UItem.asShadow(getString(R.string.GerehGasPublicInfo)));
            }
        }

        if (!r.getDraft()) {
            items.add(UItem.asButton(TEST, getString(testing ? R.string.GerehGasTesting : R.string.GerehGasTest)));
            items.add(UItem.asButton(SHARE, getString(R.string.GerehGasShare)));
        }
        if (r.getMine()) {
            items.add(UItem.asButton(SCRIPT, getString(R.string.GerehGasScriptCopy)));
            // the script again at Google, a new deployment's ID back, and the test (the setup's steps 2 and 3)
            items.add(UItem.asButton(REDEPLOY, getString(R.string.GerehGasRedeploy)));
        }
        items.add(UItem.asButton(REMOVE, getString(R.string.GerehGasRemove)));
        items.add(UItem.asShadow(getString(R.string.GerehGasShareInfo)));
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        GasRelayView r = relay;
        ChatNode node = GerehIdentity.node();
        if (r == null || node == null) {
            return;
        }
        switch (item.id) {
            case ON:
                set(node, !r.getEnabled(), null, null);
                break;
            case ANNOUNCE:
                set(node, null, !r.getAnnounce(), null);
                break;
            case RENAME:
                GerehGas.ask(this, getString(R.string.GerehGasName), null, r.getName(), getString(R.string.GerehGasName), getString(R.string.Save),
                        text -> {
                            if (!text.isEmpty()) {
                                set(node, null, null, text);
                            }
                        });
                break;
            case TEST:
                test(node);
                break;
            case SHARE:
                share(node);
                break;
            case REDEPLOY:
                presentFragment(new GerehGasSetupActivity(rid));
                break;
            case SCRIPT:
                try {
                    AndroidUtilities.addToClipboard(node.gasRelayScript(rid));
                    BulletinFactory.of(this).createCopyBulletin(getString(R.string.GerehGasScriptCopied)).show();
                } catch (Throwable e) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.GerehGasFailed)).show();
                }
                break;
            case REMOVE:
                remove(node);
                break;
            case SERVER:
                readServer(true);
                break;
            case PAUSE:
                // with the script's settings not read yet, a tap used to pause it blindly: read them first
                if (r.getServer() == null) {
                    readServer(true);
                } else {
                    admin(node, !r.getServer().getPaused(), null);
                }
                break;
            case LIMIT:
                limit(node);
                break;
            case PUBLIC:
                if (r.isPublic()) {
                    setPublic(node, false);
                } else if (getParentActivity() != null) {
                    new AlertDialog.Builder(getParentActivity(), resourceProvider)
                            .setTitle(getString(R.string.GerehGasPublicAskTitle))
                            .setMessage(getString(R.string.GerehGasPublicAsk))
                            .setPositiveButton(getString(R.string.GerehGasPublicOpen), (d, w) -> setPublic(node, true))
                            .setNegativeButton(getString(R.string.Cancel), null)
                            .show();
                }
                break;
            default:
                break;
        }
    }

    private void set(ChatNode node, Boolean enabled, Boolean announce, String name) {
        GerehGas.async(() -> {
            try {
                node.gasRelaySet(rid, enabled, announce, name);
            } catch (Throwable ignored) {
            }
            AndroidUtilities.runOnUIThread(this::load);
        });
    }

    private void test(ChatNode node) {
        if (testing) {
            return;
        }
        testing = true;
        listView.adapter.update(true);
        // the test takes up to half a minute: a spinner, so the screen does not look idle (Ehsan, 26 Oct 2026)
        AlertDialog progress = getParentActivity() == null ? null : new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER, resourceProvider);
        if (progress != null) {
            progress.setCanCancel(false);
            progress.show();
        }
        GerehGas.async(() -> {
            GasTestReport rep = null;
            try {
                rep = node.gasRelayTest(rid);
            } catch (Throwable ignored) {
            }
            GasTestReport result = rep;
            AndroidUtilities.runOnUIThread(() -> {
                testing = false;
                if (progress != null) {
                    try {
                        progress.dismiss();
                    } catch (Throwable ignored) {
                    }
                }
                listView.adapter.update(true);
                showTest(result);
                load();
            });
        });
    }

    private void showTest(GasTestReport rep) {
        if (getParentActivity() == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        String failure = null;
        if (rep == null) {
            sb.append(getString(R.string.GerehGasFailed));
        } else {
            for (GasTestStep s : rep.getSteps()) {
                sb.append(s.getOk() ? "✓ " : "✗ ").append(GerehGas.stepText(s.getStep())).append('\n');
                if (!s.getOk() && failure == null) {
                    failure = s.getCode();
                }
            }
            if (!rep.getWorker().isEmpty()) {
                sb.append('\n').append(rep.getWorker());
            }
            if (!rep.getFront().isEmpty()) {
                sb.append('\n').append(formatString(R.string.GerehGasFrontValue, rep.getFront(), (int) rep.getFrontMs()));
            }
            if (rep.getOk() && failure != null) {
                // a combined relay that works through one door only: which one, and how to mend the other
                sb.append("\n\n").append(getString(rep.getWorkerOk() ? R.string.GerehComboOnlyCf : R.string.GerehComboOnlyGoogle));
                sb.append("\n\n").append(GerehGas.hintText(failure));
            } else if (rep.getOk()) {
                sb.append("\n\n").append(getString(R.string.GerehGasTestOk));
            } else if (failure != null) {
                sb.append("\n\n").append(GerehGas.hintText(failure));
            }
        }
        new AlertDialog.Builder(getParentActivity(), resourceProvider)
                .setTitle(getString(R.string.GerehGasTest))
                .setMessage(sb.toString().trim())
                .setPositiveButton(getString(R.string.OK), null)
                .show();
    }

    private void share(ChatNode node) {
        if (getParentActivity() == null) {
            return;
        }
        new AlertDialog.Builder(getParentActivity(), resourceProvider)
                .setTitle(getString(R.string.GerehGasShare))
                .setMessage(getString(R.string.GerehGasShareText))
                .setPositiveButton(getString(R.string.GerehGasShareCopy), (d, w) -> {
                    try {
                        AndroidUtilities.addToClipboard(node.gasRelayShare(rid));
                        BulletinFactory.of(this).createCopyBulletin(getString(R.string.GerehGasLinkCopied)).show();
                    } catch (Throwable e) {
                        BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.GerehGasFailed)).show();
                    }
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private void remove(ChatNode node) {
        if (getParentActivity() == null) {
            return;
        }
        new AlertDialog.Builder(getParentActivity(), resourceProvider)
                .setTitle(getString(R.string.GerehGasRemove))
                .setMessage(getString(R.string.GerehGasRemoveText))
                .setPositiveButton(getString(R.string.GerehGasRemove), (d, w) -> GerehGas.async(() -> {
                    try {
                        node.gasRelayRemove(rid);
                    } catch (Throwable ignored) {
                    }
                    AndroidUtilities.runOnUIThread(this::finishFragment);
                }))
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    /** Reads the script's own count (the owner's view). */
    private void readServer(boolean told) {
        if (reading) {
            return;
        }
        ChatNode node = GerehIdentity.node();
        if (node == null) {
            return;
        }
        reading = true;
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
        GerehGas.async(() -> {
            boolean ok = true;
            try {
                node.gasRelayServerStats(rid);
            } catch (Throwable e) {
                ok = false;
            }
            boolean done = ok;
            AndroidUtilities.runOnUIThread(() -> {
                reading = false;
                if (told && !done) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.GerehGasFailed)).show();
                }
                load();
            });
        });
    }

    private void admin(ChatNode node, Boolean paused, Integer maxClients) {
        GerehGas.async(() -> {
            boolean ok = true;
            try {
                node.gasRelayAdmin(rid, paused, maxClients);
                node.gasRelayServerStats(rid);
            } catch (Throwable e) {
                ok = false;
            }
            boolean done = ok;
            AndroidUtilities.runOnUIThread(() -> {
                BulletinFactory.of(this).createSimpleBulletin(done ? R.raw.contact_check : R.raw.error,
                        getString(done ? R.string.GerehGasSaved : R.string.GerehGasFailed)).show();
                load();
            });
        });
    }

    /** Opens our relay to everyone, or closes it; a script older than version 2 must be put in again. */
    private void setPublic(ChatNode node, boolean on) {
        GerehGas.async(() -> {
            String failure = null;
            try {
                node.gasRelaySetPublic(rid, on);
                node.gasRelayServerStats(rid);
            } catch (Throwable e) {
                failure = e.getMessage() != null ? e.getMessage() : "";
            }
            String why = failure;
            AndroidUtilities.runOnUIThread(() -> {
                if (why == null) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check,
                            getString(on ? R.string.GerehGasPublicOn : R.string.GerehGasPublicOff)).show();
                } else if (why.contains("script_old") && getParentActivity() != null) {
                    new AlertDialog.Builder(getParentActivity(), resourceProvider)
                            .setTitle(getString(R.string.GerehGasPublic))
                            .setMessage(getString(R.string.GerehGasPublicOld))
                            .setPositiveButton(getString(R.string.GerehGasScriptCopy), (d, w) -> {
                                try {
                                    AndroidUtilities.addToClipboard(node.gasRelayScript(rid));
                                    BulletinFactory.of(this).createCopyBulletin(getString(R.string.GerehGasScriptCopied)).show();
                                } catch (Throwable ignored) {
                                }
                            })
                            .setNegativeButton(getString(R.string.Cancel), null)
                            .show();
                } else {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.GerehGasFailed)).show();
                }
                load();
            });
        });
    }

    private void limit(ChatNode node) {
        if (getParentActivity() == null) {
            return;
        }
        CharSequence[] names = new CharSequence[LIMITS.length];
        for (int i = 0; i < LIMITS.length; i++) {
            names[i] = LIMITS[i] == 0 ? getString(R.string.GerehGasNoLimit) : formatString(R.string.GerehGasDevices, LIMITS[i]);
        }
        new AlertDialog.Builder(getParentActivity(), resourceProvider)
                .setTitle(getString(R.string.GerehGasLimit))
                .setItems(names, (d, which) -> admin(node, null, LIMITS[which]))
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }
}
