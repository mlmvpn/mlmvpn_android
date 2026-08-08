package kittoku.mvc.unit.dhcp


internal const val DHCP_HARDWARE_TYPE_ETHERNET: Byte = 1

internal const val DHCP_MAGIC_COOKIE = 0x63825363

internal const val DHCP_OPCODE_BOOT_REQUEST: Byte = 1
internal const val DHCP_OPCODE_BOOT_REPLY: Byte = 2

internal const val DHCP_OPTION_SUBNET_MASK: Byte = 1
internal const val DHCP_OPTION_ROUTER_ADDRESS: Byte = 3
internal const val DHCP_OPTION_DNS_SERVER_ADDRESS: Byte = 6
internal const val DHCP_OPTION_REQUESTED_ADDRESS: Byte = 50
internal const val DHCP_OPTION_LEASE_TIME: Byte = 51
internal const val DHCP_OPTION_MESSAGE_TYPE: Byte = 53
internal const val DHCP_OPTION_DHCP_SERVER_ADDRESS: Byte = 54
internal const val DHCP_OPTION_PARAMETER_LIST: Byte = 55
internal const val DHCP_OPTION_END: Byte = -1

internal val DHCP_BYTE_OPTIONS = arrayOf(
    DHCP_OPTION_MESSAGE_TYPE
)

internal val DHCP_INT_OPTIONS = arrayOf(
    DHCP_OPTION_LEASE_TIME
)

internal val DHCP_ADDRESS_OPTIONS = arrayOf(
    DHCP_OPTION_SUBNET_MASK,
    DHCP_OPTION_REQUESTED_ADDRESS,
    DHCP_OPTION_DHCP_SERVER_ADDRESS,
)

internal val DHCP_VARIABLE_OPTIONS = arrayOf(
    DHCP_OPTION_ROUTER_ADDRESS,
    DHCP_OPTION_DNS_SERVER_ADDRESS,
    DHCP_OPTION_PARAMETER_LIST,
)


internal const val DHCP_MESSAGE_TYPE_DISCOVER: Byte = 1
internal const val DHCP_MESSAGE_TYPE_OFFER: Byte = 2
internal const val DHCP_MESSAGE_TYPE_REQUEST: Byte = 3
internal const val DHCP_MESSAGE_TYPE_DECLINE: Byte = 4
internal const val DHCP_MESSAGE_TYPE_ACK: Byte = 5
internal const val DHCP_MESSAGE_TYPE_NAK: Byte = 6
internal const val DHCP_MESSAGE_TYPE_RELEASE: Byte = 7
