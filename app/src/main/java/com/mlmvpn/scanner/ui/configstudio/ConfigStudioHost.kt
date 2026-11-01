package com.mlmvpn.scanner.ui.configstudio

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.mlmvpn.scanner.R
import com.mlmvpn.scanner.data.studio.StudioStore
import com.mlmvpn.scanner.data.studio.UserRef
import com.mlmvpn.scanner.data.studio.config.ConfigDraft
import com.mlmvpn.scanner.data.studio.domain.ConfigTemplate
import com.mlmvpn.scanner.data.studio.domain.NodeGroup
import com.mlmvpn.scanner.data.studio.domain.Plan
import com.mlmvpn.scanner.data.studio.domain.StudioNode
import com.mlmvpn.scanner.data.studio.index.UserFilter
import com.mlmvpn.scanner.ui.LocalSystemBottomPadding
import com.mlmvpn.scanner.ui.configstudio.design.StudioTab
import com.mlmvpn.scanner.ui.configstudio.design.StudioTabBar
import com.mlmvpn.scanner.ui.configstudio.design.StudioTabBarHeight
import com.mlmvpn.scanner.ui.configstudio.wizard.StudioSetupWizard
import com.mlmvpn.scanner.utils.S

/**
 * Where a pushed page goes. The four tab roots are not routes: an empty stack IS the root.
 *
 * Every route travels by value -- ids, and the small objects a list already holds -- so a page can
 * draw itself from what it was handed and re-read only what it must.
 */
sealed class StudioRoute {
    data class UserDetail(val installationId: String, val userId: String) : StudioRoute()
    object NewUser : StudioRoute()

    /** Many people at once, from a prefix and a count. */
    object BulkUsers : StudioRoute()

    /** One action, applied to a selection. The refs travel by value: after a delete the index is empty. */
    data class BulkAction(val targets: List<UserRef>) : StudioRoute()

    object Settings : StudioRoute()
    object Plans : StudioRoute()
    data class PlanEdit(val plan: Plan?) : StudioRoute()
    object Templates : StudioRoute()
    data class TemplateEdit(val template: ConfigTemplate?, val fromDraft: ConfigDraft? = null) : StudioRoute()

    /** The configs one person holds — the unit that can be revoked on its own. */
    data class Configs(val installationId: String, val userId: String, val username: String) : StudioRoute()
    data class ConfigBuilder(
        val installationId: String,
        val userId: String,
        val username: String,
        val cloneOf: ConfigDraft?,
    ) : StudioRoute()

    /** What people did, on one installation. */
    object Audit : StudioRoute()

    /** The user list as "who is this for": a new config, or the combine assistant. */
    object PickUserForConfig : StudioRoute()
    object PickUserForCombine : StudioRoute()

    /** «نقطه‌ها»: the addresses subscriptions are served on, and their groups. */
    object Nodes : StudioRoute()
    data class NodeEdit(val node: StudioNode?) : StudioRoute()
    object NodeGroups : StudioRoute()
    data class NodeGroupEdit(val group: NodeGroup?) : StudioRoute()

    /** «لوکیشن‌ها»: one page for where a country's traffic leaves from -- public servers, verified. */
    object Locations : StudioRoute()
    /** The operator's own exit servers, behind «سرورهای خودم». */
    object Exits : StudioRoute()
    data class EditLocations(val installationId: String, val userId: String, val username: String) : StudioRoute()

    /** «ترکیب با آی‌پی تمیز» for one person. */
    data class Combine(val installationId: String, val userId: String, val username: String) : StudioRoute()

    // ---- the fleet ----
    object Accounts : StudioRoute()
    data class AccountDetail(val installationId: String) : StudioRoute()
    object AddAccount : StudioRoute()
    object BulkAdd : StudioRoute()
}

/**
 * Where Config Studio should land the next time it is shown, when another part of the app sends the
 * operator here: the Cloud tab's MLM row opens the user list of an account Config Studio manages.
 * Consumed once.
 */
object StudioLaunch {
    var openUsers by mutableStateOf(false)
}

/**
 * Whether Config Studio is the page on screen. A page with a back handler of its own (the bulk-add
 * queue) reads it, so a parked host never takes a back press meant for another part of the app.
 */
val LocalStudioVisible = androidx.compose.runtime.compositionLocalOf { true }

/**
 * The one entry point for Config Studio, and the only place its navigation lives (build 18 layout).
 *
 * **Four tabs** -- Home, Users, Stats, More -- each with a back stack of its own, the way an iOS app
 * with a tab bar works: switching tabs keeps where you were in each, tapping the tab you are on
 * returns it to its root. Before this every page hung off one dashboard of twelve tiles and rows,
 * and several pages had three ways in and a back label naming only one of them.
 *
 * **One `BackHandler`, and only while Config Studio is on screen.** The host stays composed when the
 * operator leaves for another part of the app, and an always-on handler registered after the app's
 * own took the back press away from whichever page was actually showing.
 */
@Composable
fun ConfigStudioHost(
    onExit: () -> Unit,
    /** Leave for the scanner: «ترکیب» arms a handoff and uses the app's own scanner. */
    onOpenScanner: () -> Unit = {},
    /** Whether this host is the page on screen. False while it is parked off to the side. */
    visible: Boolean = true,
) {
    val context = LocalContext.current
    val store = remember { StudioStore.get(context) }
    val state by store.state.collectAsState()

    var installed by remember { mutableStateOf(store.installedAccounts().isNotEmpty()) }
    var tabName by rememberSaveable { mutableStateOf(StudioTab.HOME.name) }
    val tab = StudioTab.valueOf(tabName)
    val stacks = remember { StudioTab.entries.associateWith { mutableStateListOf<StudioRoute>() } }
    val stack = stacks.getValue(tab)
    /** A filter the Users tab should open with, set by a Home tile. Consumed once. */
    var usersPreset by remember { mutableStateOf<UserFilter?>(null) }

    fun push(route: StudioRoute) = stack.add(route)
    fun pop() { stack.removeLastOrNull() }
    fun selectTab(t: StudioTab) {
        // Tapping the tab you are on takes it back to its root, as iOS does.
        if (t == tab) stacks.getValue(t).clear() else tabName = t.name
    }

    BackHandler(enabled = visible && installed) {
        when {
            stack.isNotEmpty() -> pop()
            tab != StudioTab.HOME -> tabName = StudioTab.HOME.name
            else -> onExit()
        }
    }

    // The fleet can shrink as well as grow: disconnecting the last account puts the operator back in
    // front of the wizard rather than on a home screen with nothing behind it.
    LaunchedEffect(state.installations) {
        installed = store.installedAccounts().isNotEmpty()
    }

    suspend fun refreshAll() {
        // Health first: it tells every screen what each installation can actually do.
        store.refreshInstallations()
        store.syncAll()
        store.refreshDashboard()
        store.refreshNodes()
        store.refreshNodeGroups()
        store.refreshTemplates()
        store.refreshActivity()
    }

    LaunchedEffect(installed) {
        if (!installed) return@LaunchedEffect
        refreshAll()
        // Probes run from THIS phone, on THIS network: the question is whether an address answers
        // from where the operator is. Throttled in the store.
        store.autoProbeNodes()
    }

    if (!installed) {
        StudioSetupWizard(
            onExit = onExit,
            onFinished = {
                installed = store.installedAccounts().isNotEmpty()
                stacks.values.forEach { it.clear() }
            },
            backEnabled = visible,
        )
        return
    }

    // Sent here from outside (the Cloud tab's MLM row): the user list, from its root.
    LaunchedEffect(StudioLaunch.openUsers, visible) {
        if (visible && StudioLaunch.openUsers) {
            StudioLaunch.openUsers = false
            stacks.getValue(StudioTab.USERS).clear()
            tabName = StudioTab.USERS.name
        }
    }

    val atRoot = stack.isEmpty()
    val basePadding = LocalSystemBottomPadding.current

    // The home tiles count people. Back on them after a change of who exists or who is on (made,
    // deleted, switched on or off), they are read again at once -- they used to keep the old numbers
    // until a pull, so a person just made was not there. Only on a change: an engine's count is a
    // database read, and coming back to the home screen is not a reason to spend one.
    var countedAt by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(atRoot, tab, state.indexedUsers, state.indexedActive) {
        val counts = state.indexedUsers to state.indexedActive
        val before = countedAt
        if (before == null) { countedAt = counts; return@LaunchedEffect }
        if (atRoot && tab == StudioTab.HOME && counts != before) {
            countedAt = counts
            store.refreshDashboard()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // A tab root scrolls under the tab bar, so it leaves room for it at its foot.
        CompositionLocalProvider(
            LocalSystemBottomPadding provides if (atRoot) basePadding + StudioTabBarHeight else basePadding,
            LocalStudioVisible provides visible,
        ) {
            when (val route = stack.lastOrNull()) {
                null -> when (tab) {
                    StudioTab.HOME -> StudioDashboardScreen(
                        store = store,
                        state = state,
                        onRefresh = { refreshAll() },
                        onActivityFilter = { errorsOnly -> store.refreshActivity(errorsOnly = errorsOnly) },
                        onOpenUsers = { filter -> usersPreset = filter; tabName = StudioTab.USERS.name },
                        onOpenUser = { i, u -> push(StudioRoute.UserDetail(i, u)) },
                        onNewUser = { push(StudioRoute.NewUser) },
                        onBulkUsers = { push(StudioRoute.BulkUsers) },
                        onNewConfig = { push(StudioRoute.PickUserForConfig) },
                        onCombine = { push(StudioRoute.PickUserForCombine) },
                        onOpenActivity = { push(StudioRoute.Audit) },
                        onOpenSettings = { push(StudioRoute.Settings) },
                        onOpenAccounts = { push(StudioRoute.Accounts) },
                        onExit = onExit,
                    )
                    StudioTab.USERS -> StudioUsersScreen(
                        store = store,
                        state = state,
                        onOpenUser = { i, u -> push(StudioRoute.UserDetail(i, u)) },
                        onNewUser = { push(StudioRoute.NewUser) },
                        onBack = null,
                        onBulk = { push(StudioRoute.BulkAction(it)) },
                        onRefresh = {
                            store.syncAll()
                            store.refreshDashboard()
                        },
                        preset = usersPreset,
                        onPresetConsumed = { usersPreset = null },
                    )
                    StudioTab.STATS -> StudioAnalyticsScreen(store = store, state = state, onBack = null)
                    StudioTab.MORE -> StudioMoreScreen(
                        state = state,
                        onOpenTemplates = { push(StudioRoute.Templates) },
                        onOpenPlans = { push(StudioRoute.Plans) },
                        onOpenLocations = { push(StudioRoute.Locations) },
                        onOpenNodes = { push(StudioRoute.Nodes) },
                        onNewConfig = { push(StudioRoute.PickUserForConfig) },
                        onCombine = { push(StudioRoute.PickUserForCombine) },
                        onBulkUsers = { push(StudioRoute.BulkUsers) },
                        onOpenAccounts = { push(StudioRoute.Accounts) },
                        onOpenSettings = { push(StudioRoute.Settings) },
                        onOpenAudit = { push(StudioRoute.Audit) },
                    )
                }

                is StudioRoute.PickUserForConfig -> StudioUsersScreen(
                    store = store,
                    state = state,
                    onOpenUser = { installationId, userId ->
                        val user = store.cached(installationId, userId)
                        // Replaced rather than pushed past, so backing out of the configs returns to
                        // where the question was asked instead of re-asking it.
                        pop()
                        // A central-Worker person's configs are on their own page.
                        if (store.isCentral(installationId)) push(StudioRoute.UserDetail(installationId, userId))
                        else push(StudioRoute.Configs(installationId, userId, user?.username.orEmpty()))
                    },
                    onNewUser = { push(StudioRoute.NewUser) },
                    onBack = ::pop,
                    pickTitle = S(R.string.studio_config_for),
                    pickSubtitle = S(R.string.studio_config_for_sub),
                )

                is StudioRoute.PickUserForCombine -> StudioUsersScreen(
                    store = store,
                    state = state,
                    onOpenUser = { installationId, userId ->
                        val user = store.cached(installationId, userId)
                        pop()
                        // A central-Worker person's addresses are the central Worker's own settings.
                        if (store.isCentral(installationId)) push(StudioRoute.UserDetail(installationId, userId))
                        else push(StudioRoute.Combine(installationId, userId, user?.username.orEmpty()))
                    },
                    onNewUser = { push(StudioRoute.NewUser) },
                    onBack = ::pop,
                    pickTitle = S(R.string.studio_combine_for),
                    pickSubtitle = S(R.string.studio_combine_for_sub),
                )

                is StudioRoute.Nodes -> StudioNodesScreen(
                    store = store,
                    state = state,
                    onNewNode = { push(StudioRoute.NodeEdit(null)) },
                    onOpenNode = { push(StudioRoute.NodeEdit(it)) },
                    onOpenGroups = { push(StudioRoute.NodeGroups) },
                    onBack = ::pop,
                )

                is StudioRoute.Locations -> StudioMultiLocationScreen(
                    store = store,
                    state = state,
                    onBack = ::pop,
                    onOpenExits = { push(StudioRoute.Exits) },
                    onNewUser = { push(StudioRoute.NewUser) },
                )

                is StudioRoute.Exits -> StudioExitsScreen(store = store, state = state, onBack = ::pop)

                is StudioRoute.NodeGroups -> StudioNodeGroupsScreen(
                    store = store,
                    state = state,
                    onNewGroup = { push(StudioRoute.NodeGroupEdit(null)) },
                    onEditGroup = { push(StudioRoute.NodeGroupEdit(it)) },
                    onBack = ::pop,
                )

                is StudioRoute.NodeGroupEdit -> StudioNodeGroupEditScreen(
                    store = store, state = state, existing = route.group, onBack = ::pop, onSaved = ::pop,
                )

                is StudioRoute.Combine -> StudioCombineHandover(
                    store = store,
                    installationId = route.installationId,
                    userId = route.userId,
                    username = route.username,
                    onBack = ::pop,
                    onReady = {
                        pop()
                        onOpenScanner()
                    },
                )

                is StudioRoute.NodeEdit -> StudioNodeEditScreen(
                    store = store, state = state, existing = route.node, onBack = ::pop, onSaved = ::pop,
                )

                is StudioRoute.Settings -> StudioSettingsScreen(
                    store = store,
                    state = state,
                    onOpenAccounts = { push(StudioRoute.Accounts) },
                    onOpenNodes = { push(StudioRoute.Nodes) },
                    onOpenLocations = { push(StudioRoute.Locations) },
                    onOpenAudit = { push(StudioRoute.Audit) },
                    onBulkAdd = { push(StudioRoute.BulkAdd) },
                    onBack = ::pop,
                )

                is StudioRoute.Audit -> StudioAuditScreen(store = store, state = state, onBack = ::pop)

                is StudioRoute.Accounts -> StudioAccountsScreen(
                    store = store,
                    state = state,
                    onAddAccount = { push(StudioRoute.AddAccount) },
                    onBulkAdd = { push(StudioRoute.BulkAdd) },
                    onOpenAccount = { push(StudioRoute.AccountDetail(it)) },
                    onBack = ::pop,
                )

                is StudioRoute.AccountDetail -> if (store.isCentral(route.installationId)) StudioCentralAccountScreen(
                    store = store, installationId = route.installationId, onBack = ::pop, onRemoved = ::pop,
                ) else StudioAccountDetailScreen(
                    store = store, installationId = route.installationId, onBack = ::pop, onRemoved = ::pop,
                )

                is StudioRoute.AddAccount -> StudioSetupWizard(
                    onExit = ::pop,
                    onFinished = ::pop,
                    excludeAccountIds = store.installedAccounts().map { it.id }.toSet(),
                    backEnabled = visible,
                )

                is StudioRoute.BulkAdd -> StudioBulkAddScreen(store = store, onBack = ::pop, onFinished = ::pop)

                is StudioRoute.Plans -> StudioPlansScreen(
                    store = store,
                    state = state,
                    onNewPlan = { push(StudioRoute.PlanEdit(null)) },
                    onEditPlan = { push(StudioRoute.PlanEdit(it)) },
                    onOpenTemplates = { push(StudioRoute.Templates) },
                    onBack = ::pop,
                )

                is StudioRoute.Templates -> StudioTemplatesScreen(
                    store = store,
                    state = state,
                    onNewTemplate = { push(StudioRoute.TemplateEdit(null)) },
                    onEditTemplate = { push(StudioRoute.TemplateEdit(it)) },
                    onBack = ::pop,
                )

                is StudioRoute.TemplateEdit -> StudioTemplateEditScreen(
                    store = store, state = state, existing = route.template, fromDraft = route.fromDraft,
                    onBack = ::pop, onSaved = ::pop,
                )

                is StudioRoute.PlanEdit -> StudioPlanEditScreen(
                    store = store, state = state, existing = route.plan, onBack = ::pop, onSaved = ::pop,
                )

                // New people are made on an account's central Worker (the operator's choice,
                // 2026-10-18); the engines keep the people they already have.
                is StudioRoute.NewUser -> StudioCentralNewUserScreen(
                    store = store,
                    state = state,
                    onBack = ::pop,
                    onOpenUser = { installationId, userId ->
                        // To the new person's page, in place of the form.
                        pop()
                        push(StudioRoute.UserDetail(installationId, userId))
                    },
                    onAddAccount = { push(StudioRoute.AddAccount) },
                )

                is StudioRoute.BulkAction -> StudioBulkActionScreen(
                    store = store, state = state, targets = route.targets, onBack = ::pop, onDeleted = ::pop,
                )

                is StudioRoute.BulkUsers -> StudioCentralBulkUsersScreen(
                    store = store, state = state, onBack = ::pop, onDone = ::pop, onAddAccount = { push(StudioRoute.AddAccount) },
                )

                is StudioRoute.UserDetail -> if (store.isCentral(route.installationId)) StudioCentralUserScreen(
                    store = store,
                    installationId = route.installationId,
                    userId = route.userId,
                    onBack = ::pop,
                ) else StudioUserDetailScreen(
                    store = store,
                    installationId = route.installationId,
                    userId = route.userId,
                    onOpenConfigs = { username -> push(StudioRoute.Configs(route.installationId, route.userId, username)) },
                    onCombine = { username -> push(StudioRoute.Combine(route.installationId, route.userId, username)) },
                    onEditLocations = { username -> push(StudioRoute.EditLocations(route.installationId, route.userId, username)) },
                    onBack = ::pop,
                    onMoved = { installationId, userId ->
                        // To the person's new page on the central Worker, in place of the old one.
                        pop()
                        push(StudioRoute.UserDetail(installationId, userId))
                    },
                )

                is StudioRoute.EditLocations -> StudioLocationsEditScreen(
                    store = store,
                    installationId = route.installationId,
                    userId = route.userId,
                    username = route.username,
                    onDone = ::pop,
                    onBack = ::pop,
                )

                is StudioRoute.Configs -> StudioConfigsScreen(
                    store = store,
                    installationId = route.installationId,
                    userId = route.userId,
                    username = route.username,
                    onBack = ::pop,
                    onAddConfig = { clone ->
                        push(StudioRoute.ConfigBuilder(route.installationId, route.userId, route.username, clone))
                    },
                )

                is StudioRoute.ConfigBuilder -> StudioConfigBuilderScreen(
                    store = store,
                    state = state,
                    installationId = route.installationId,
                    userId = route.userId,
                    username = route.username,
                    cloneOf = route.cloneOf,
                    onSaveAsTemplate = { push(StudioRoute.TemplateEdit(null, it)) },
                    onBack = ::pop,
                    onCreated = ::pop,
                )
            }
        }

        if (atRoot) {
            StudioTabBar(
                selected = tab,
                onSelect = ::selectTab,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
