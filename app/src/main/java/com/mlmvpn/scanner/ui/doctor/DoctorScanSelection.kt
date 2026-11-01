package com.mlmvpn.scanner.ui.doctor

import com.mlmvpn.scanner.data.CloudGroup
import com.mlmvpn.scanner.models.VpnNode

/** One selectable config in the Doctor's scan handoff. */
internal data class DoctorScanChoice(
    val id: String,
    val groupId: String,
    val groupTitle: String,
    val node: VpnNode,
)

/** Flatten only this account's saved Cloud groups while retaining their UI grouping metadata. */
internal fun doctorScanChoices(groups: List<CloudGroup>, accountId: String): List<DoctorScanChoice> =
    groups
        .asSequence()
        .filter { it.accountId == accountId }
        .flatMap { group ->
            group.nodes.asSequence().mapIndexedNotNull { index, node ->
                if (node.uri.isBlank()) null else DoctorScanChoice(
                    id = "${group.id}:${node.id}:$index",
                    groupId = group.id,
                    groupTitle = group.title,
                    node = node,
                )
            }
        }
        .toList()

/**
 * Build the source CloudGroup consumed by the existing ScannerTab/CombineCoach pipeline.
 * Selecting the same URI from two panel groups is kept once; otherwise every clean IP would be
 * combined twice for no user-visible benefit.
 */
internal fun doctorScanGroup(
    accountId: String,
    choices: List<DoctorScanChoice>,
    selectedIds: Set<String>,
    stamp: Long,
    title: String = "دکتر · کانفیگ‌های انتخاب‌شده",
): CloudGroup? {
    if (accountId.isBlank() || selectedIds.isEmpty()) return null
    val unique = LinkedHashMap<String, VpnNode>()
    choices.asSequence()
        .filter { it.id in selectedIds }
        .forEach { choice -> unique.putIfAbsent(choice.node.uri, choice.node) }
    if (unique.isEmpty()) return null
    return CloudGroup(
        id = "doctor_scan_$stamp",
        accountId = accountId,
        date = "doctor-$stamp",
        title = title,
        nodes = unique.values.toList(),
    )
}
