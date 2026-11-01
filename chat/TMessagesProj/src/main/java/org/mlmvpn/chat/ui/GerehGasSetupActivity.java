package org.mlmvpn.chat.ui;

import static org.telegram.messenger.LocaleController.formatString;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.mlmchat.core.ChatNode;
import org.mlmchat.core.GasComboDraft;
import org.mlmchat.core.GasDraft;
import org.mlmchat.core.GasRelayView;
import org.mlmchat.core.GasTestReport;
import org.mlmchat.core.GasTestStep;
import org.mlmvpn.chat.bridge.GerehCloud;
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
import java.util.Collections;
import java.util.List;

/**
 * MLMCHAT: making a Google relay, step by step (SPEC 19.6), the way MLMVPN's Google Script is made:
 * <ol>
 * <li>what it is and what the user allows (their Google account's quota, sealed mail only, stoppable
 * any time), then the keys are made on this phone;</li>
 * <li>copy the script, make a web app of it at script.google.com, paste its deployment back;</li>
 * <li>the phone tests it by itself (the way to Google, the script and its key, a message through
 * the mailbox) and says in plain words what to fix if something is wrong.</li>
 * </ol>
 * The draft is kept when the screen is left: coming back from the list continues at step 2.
 * Finding the way to Google (address and name) is the phone's job and is never asked.
 * <p>
 * Below it, «ترکیب رله گوگل و کلادفلر» (SPEC 19.13): the mailbox goes on the user's Cloudflare
 * account (the app's cloud section) as a Worker, made by the app itself; then a separate Google
 * script, only a door to that Worker, is made the same way as a simple relay's, in a new project.
 * Phones that reach Cloudflare use the Worker straight, the others the Google door.
 */
public final class GerehGasSetupActivity extends BaseFragment {
    private static final int CONSENT = 1, COPY = 2, OPEN = 3, PASTE = 4, TYPE = 5, RETRY = 6, DONE = 7, COMBO = 8;
    /** Buttons for "make the Worker on account n" are DEPLOY + n. */
    private static final int DEPLOY = 100;
    /** The combined relay's first step: its Worker on Cloudflare. */
    private static final int STEP_WORKER = 10;
    private static final String CREATE_URL = "https://script.google.com/home/projects/create";

    /** 0 = what it is, 10 = a combined relay's Worker, 1 = script and deployment, 2 = test and result. */
    private int step;
    private String rid;
    /** A combined relay (SPEC 19.13): its Google door is the script of step 1. */
    private boolean combo;
    private List<String> accounts = Collections.emptyList();
    private boolean busy;
    private boolean testing;
    private GasTestReport report;
    private UniversalRecyclerView listView;

    /** [draftRid]: a draft to go on with (step 1), or null to start from the beginning. */
    public GerehGasSetupActivity(String draftRid) {
        rid = draftRid;
        step = draftRid == null ? 0 : 1;
        if (draftRid != null) {
            // a combined draft goes on where it stopped: its Worker, or its Google door
            ChatNode node = GerehIdentity.node();
            try {
                for (GasRelayView r : node == null ? Collections.<GasRelayView>emptyList() : node.gasOverview().getRelays()) {
                    if (draftRid.equals(r.getRid()) && r.getCombined()) {
                        combo = true;
                        step = r.getWorker().isEmpty() ? STEP_WORKER : 1;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.GerehGasSetupTitle));
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
        GerehGas.async(() -> {
            List<String> a = GerehCloud.accounts();
            AndroidUtilities.runOnUIThread(() -> {
                accounts = a;
                update();
            });
        });
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        return fragmentView = contentView;
    }

    private void update() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (step == 0) {
            items.add(UItem.asHeader(getString(R.string.GerehGasConsentHeader)));
            items.add(UItem.asShadow(getString(R.string.GerehGasConsentBody)));
            items.add(UItem.asButton(CONSENT, getString(busy && !combo ? R.string.GerehGasMaking : R.string.GerehGasConsentDo)));
            items.add(UItem.asShadow(getString(R.string.GerehGasConsentFoot)));
            if (GerehCloud.available()) {
                items.add(UItem.asHeader(getString(R.string.GerehComboHeader)));
                items.add(UItem.asShadow(getString(R.string.GerehComboBody)));
                items.add(UItem.asButton(COMBO, getString(busy && combo ? R.string.GerehGasMaking : R.string.GerehComboDo)));
                items.add(UItem.asShadow(getString(accounts.isEmpty() ? R.string.GerehComboNoAccount : R.string.GerehComboFoot)));
            }
        } else if (step == STEP_WORKER) {
            items.add(UItem.asHeader(getString(R.string.GerehComboStepWorkerHeader)));
            items.add(UItem.asShadow(getString(accounts.isEmpty() ? R.string.GerehComboNoAccount : R.string.GerehComboStepWorkerText)));
            for (int i = 0; i < accounts.size(); i++) {
                items.add(UItem.asButton(DEPLOY + i, busy ? getString(R.string.GerehComboDeploying) : formatString(R.string.GerehComboDeployOn, accounts.get(i))));
            }
            items.add(UItem.asButton(DONE, getString(R.string.GerehGasLater)));
        } else if (step == 1) {
            items.add(UItem.asHeader(getString(combo ? R.string.GerehComboStepDoorHeader : R.string.GerehGasStep1Header)));
            items.add(UItem.asShadow(getString(combo ? R.string.GerehComboStepDoorText : R.string.GerehGasStep1Text)));
            items.add(UItem.asButton(COPY, getString(R.string.GerehGasCopyScript)));
            items.add(UItem.asHeader(getString(combo ? R.string.GerehComboStepGoogleHeader : R.string.GerehGasStep2Header)));
            items.add(UItem.asShadow(getString(R.string.GerehGasStep2Text)));
            if (combo) {
                items.add(UItem.asShadow(getString(R.string.GerehComboGoogleNote)));
            }
            items.add(UItem.asButton(OPEN, getString(R.string.GerehGasOpenGoogle)));
            items.add(UItem.asHeader(getString(combo ? R.string.GerehComboStepBackHeader : R.string.GerehGasStep3Header)));
            items.add(UItem.asShadow(getString(R.string.GerehGasStep3Text)));
            items.add(UItem.asButton(PASTE, getString(busy ? R.string.GerehGasChecking : R.string.GerehGasPasteDeployment)));
            items.add(UItem.asButton(TYPE, getString(R.string.GerehGasTypeDeployment)));
        } else {
            items.add(UItem.asHeader(getString(R.string.GerehGasResultHeader)));
            if (testing) {
                items.add(UItem.asShadow(getString(R.string.GerehGasTestingSteps)));
            } else if (report == null) {
                items.add(UItem.asShadow(getString(R.string.GerehGasFailed)));
                items.add(UItem.asButton(RETRY, getString(R.string.GerehGasRetry)));
                items.add(UItem.asButton(DONE, getString(R.string.GerehGasLater)));
            } else {
                String failure = null;
                for (GasTestStep s : report.getSteps()) {
                    items.add(UItem.asButton(0, GerehGas.stepText(s.getStep()), s.getOk() ? "✓" : "✗"));
                    if (!s.getOk() && failure == null) {
                        failure = s.getCode();
                    }
                }
                if (!report.getWorker().isEmpty()) {
                    items.add(UItem.asButton(0, getString(R.string.GerehComboWorkerRow), report.getWorker()));
                }
                if (!report.getFront().isEmpty()) {
                    items.add(UItem.asButton(0, getString(R.string.GerehGasFront), formatString(R.string.GerehGasFrontValue, report.getFront(), (int) report.getFrontMs())));
                }
                if (report.getOk() && combo && failure != null) {
                    items.add(UItem.asShadow(getString(report.getWorkerOk() ? R.string.GerehComboOnlyCf : R.string.GerehComboOnlyGoogle) + "\n\n" + GerehGas.hintText(failure)));
                    items.add(UItem.asButton(RETRY, getString(R.string.GerehGasRetry)));
                    items.add(UItem.asButton(DONE, getString(R.string.GerehGasDone)));
                } else if (report.getOk()) {
                    items.add(UItem.asShadow(getString(!combo ? R.string.GerehGasDoneText : report.getWorkerOk() ? R.string.GerehComboDoneText : R.string.GerehComboDoneGoogleOnly)));
                    items.add(UItem.asButton(DONE, getString(R.string.GerehGasDone)));
                } else {
                    items.add(UItem.asShadow(GerehGas.hintText(failure)));
                    items.add(UItem.asButton(RETRY, getString(R.string.GerehGasRetry)));
                    items.add(UItem.asButton(PASTE, getString(R.string.GerehGasPasteOther)));
                    items.add(UItem.asButton(TYPE, getString(R.string.GerehGasTypeDeployment)));
                    items.add(UItem.asButton(DONE, getString(R.string.GerehGasLater)));
                }
            }
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        ChatNode node = GerehIdentity.node();
        if (node == null) {
            return;
        }
        if (item.id >= DEPLOY && item.id < DEPLOY + accounts.size()) {
            deployWorker(node, item.id - DEPLOY);
            return;
        }
        switch (item.id) {
            case CONSENT:
                make(node);
                break;
            case COMBO:
                if (accounts.isEmpty()) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.GerehComboNoAccount)).show();
                } else {
                    makeCombo(node);
                }
                break;
            case COPY:
                try {
                    AndroidUtilities.addToClipboard(node.gasRelayScript(rid));
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
                finish(node, GerehGas.clipboardText(getParentActivity()));
                break;
            case TYPE:
                GerehGas.ask(this, getString(R.string.GerehGasTypeDeployment), getString(R.string.GerehGasTypeAsk), "", getString(R.string.GerehGasDeploymentHint),
                        getString(R.string.GerehGasNext), text -> finish(node, text));
                break;
            case RETRY:
                test(node);
                break;
            case DONE:
                finishFragment();
                break;
            default:
                break;
        }
    }

    /** Step 0: the user allows it; the keys are made on this phone and the script is ready. */
    private void make(ChatNode node) {
        if (busy) {
            return;
        }
        busy = true;
        update();
        GerehGas.async(() -> {
            GasDraft d = null;
            try {
                d = node.gasRelayNew(getString(R.string.GerehGasMyRelay));
            } catch (Throwable ignored) {
            }
            GasDraft draft = d;
            AndroidUtilities.runOnUIThread(() -> {
                busy = false;
                if (draft == null) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.GerehGasFailed)).show();
                } else {
                    rid = draft.getRid();
                    step = 1;
                    AndroidUtilities.addToClipboard(draft.getScript());
                    BulletinFactory.of(this).createCopyBulletin(getString(R.string.GerehGasScriptCopied)).show();
                }
                update();
            });
        });
    }

    /** A combined relay: the keys and its Worker are made on this phone; next, the Worker on Cloudflare. */
    private void makeCombo(ChatNode node) {
        if (busy) {
            return;
        }
        busy = true;
        combo = true;
        update();
        GerehGas.async(() -> {
            GasComboDraft d = null;
            try {
                d = node.gasComboNew(getString(R.string.GerehComboMyRelay));
            } catch (Throwable ignored) {
            }
            GasComboDraft draft = d;
            AndroidUtilities.runOnUIThread(() -> {
                busy = false;
                if (draft == null) {
                    combo = false;
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.GerehGasFailed)).show();
                } else {
                    rid = draft.getRid();
                    step = STEP_WORKER;
                }
                update();
            });
        });
    }

    /** The Worker goes on account [index] of the app's cloud section; then the Google door's script is ready and copied. */
    private void deployWorker(ChatNode node, int index) {
        if (busy) {
            return;
        }
        busy = true;
        update();
        GerehGas.async(() -> {
            String door = null;
            String error = null;
            try {
                String host = GerehCloud.deployWorker(index, node.gasComboScript(rid), null);
                door = node.gasComboWorker(rid, host);
            } catch (Throwable e) {
                error = e.getMessage();
            }
            String script = door;
            String why = error;
            AndroidUtilities.runOnUIThread(() -> {
                busy = false;
                if (script == null) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, formatString(R.string.GerehComboDeployFailed, why == null ? "" : why)).show();
                } else {
                    step = 1;
                    AndroidUtilities.addToClipboard(script);
                    BulletinFactory.of(this).createCopyBulletin(getString(R.string.GerehGasScriptCopied)).show();
                }
                update();
            });
        });
    }

    /** Step 1: the deployment came back (its id, or the whole web-app address). */
    private void finish(ChatNode node, String text) {
        if (busy) {
            return;
        }
        if (text == null || text.trim().isEmpty()) {
            BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.GerehGasPasteNone)).show();
            return;
        }
        busy = true;
        update();
        GerehGas.async(() -> {
            String newRid = null;
            try {
                newRid = node.gasRelayFinish(rid, text);
            } catch (Throwable ignored) {
            }
            String result = newRid;
            AndroidUtilities.runOnUIThread(() -> {
                busy = false;
                if (result == null) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.GerehGasDeploymentBad)).show();
                    update();
                } else {
                    rid = result;
                    step = 2;
                    report = null;
                    update();
                    test(node);
                }
            });
        });
    }

    /** Step 2: the phone tests it by itself. */
    private void test(ChatNode node) {
        if (testing) {
            return;
        }
        testing = true;
        report = null;
        update();
        // the test takes up to half a minute: a spinner, so the screen does not look idle
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
                report = result;
                if (result == null) {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.error, getString(R.string.GerehGasFailed)).show();
                }
                update();
            });
        });
    }
}
