package com.mlmvpn.scanner.backup

/**
 * Every part of the app a backup holds -- the one list to add to (see [BackupContributor]).
 *
 * The order matters in one way only: items that edit parts of one file are planned in this order,
 * each on top of the one before (an account item before its tile's settings).
 */
object BackupHooks {

    val contributors: List<BackupContributor> by lazy {
        val all = buildList {
            addAll(MainItems.all)
            addAll(AccountItems.all)
            addAll(MethodItems.all)
            // A feature that keeps data of its own adds its item here, one line:
            // add(com.mlmvpn.scanner.data.studio.StudioBackup)
            add(com.mlmvpn.scanner.widget.studio.StudioBackup)
            add(com.mlmvpn.scanner.data.studio.central.CentralBackup)
        }
        val ids = all.map { it.id }
        check(ids.size == ids.toSet().size) { "two backup items share an id" }
        check(ids.all { ID.matches(it) }) { "a backup item id is not [a-z0-9_]+" }
        all
    }

    private val ID = Regex("[a-z0-9_]{1,64}")

    fun byId(id: String): BackupContributor? = contributors.firstOrNull { it.id == id }
}
