package kittoku.mvc.unit.property


internal const val COMPATIBLE_VERSION = 444
internal const val COMPATIBLE_BUILD = 9807

internal const val SEP_INT_TYPE = 0
internal const val SEP_BYTES_TYPE = 1
internal const val SEP_ASCII_TYPE = 2
internal const val SEP_UTF8_TYPE = 3
internal const val SEP_LONG_TYPE = 4


internal const val SEP_BUILD = "build"
internal const val SEP_VERSION = "version"
internal const val SEP_UNIQUE_ID_CAMEL = "UniqueId"
internal const val SEP_UNIQUE_ID_SNAKE = "unique_id"
internal const val SEP_CLIENT_BUILD = "client_build"
internal const val SEP_CLIENT_ID = "client_id"
internal const val SEP_CLIENT_STR = "client_str"
internal const val SEP_CLIENT_VER = "client_ver"
internal const val SEP_CLIENT_HOSTNAME = "ClientHostname"
internal const val SEP_CLIENT_IP_ADDRESS = "ClientIpAddress"
internal const val SEP_CLIENT_OS_NAME = "ClientOsName"
internal const val SEP_CLIENT_OS_VER = "ClientOsVer"
internal const val SEP_CLIENT_PORT = "ClientPort"
internal const val SEP_CLIENT_PRODUCT_BUILD = "ClientProductBuild"
internal const val SEP_CLIENT_PRODUCT_NAME = "ClientProductName"
internal const val SEP_CLIENT_PRODUCT_VER = "ClientProductVer"
internal const val SEP_HELLO = "hello"

internal const val SEP_PROXY_PORT = "ProxyPort"
internal const val SEP_PROXY_IP_ADDRESS = "ProxyIpAddress"

internal const val SEP_SERVER_HOSTNAME = "ServerHostname"
internal const val SEP_SERVER_IP_ADDRESS = "ServerIpAddress"
internal const val SEP_SERVER_PORT2 = "ServerPort2"
internal const val SEP_SERVER_PRODUCT_BUILD = "ServerProductBuild"
internal const val SEP_SERVER_PRODUCT_NAME = "ServerProductName"
internal const val SEP_SERVER_PRODUCT_VER = "ServerProductVer"

internal const val SEP_METHOD = "method"
internal const val SEP_AUTH_TYPE = "authtype"
internal const val SEP_USERNAME = "username"
internal const val SEP_SECURE_PASSWORD = "secure_password"
internal const val SEP_PROTOCOL = "protocol"
internal const val SEP_HUB_NAME = "hubname"
internal const val SEP_USE_ENCRYPT = "use_encrypt"
internal const val SEP_USE_COMPRESS = "use_compress"
internal const val SEP_MAX_CONNECTION = "max_connection"
internal const val SEP_HALF_CONNECTION = "half_connection"

internal const val SEP_RANDOM = "random"
internal const val SEP_PEN_CORE = "pencore"
internal const val SEP_ERROR = "error"

// UDP Acceleration property
internal const val SEP_USE_UDP_ACCELERATION = "use_udp_acceleration"
internal const val SEP_UDP_USE_ENCRYPTION = "udp_acceleration_use_encryption"
internal const val SEP_UDP_VERSION = "udp_acceleration_version"
internal const val SEP_UDP_MAX_VERSION = "udp_acceleration_max_version"
internal const val SEP_UDP_CLIENT_IP = "udp_acceleration_client_ip"
internal const val SEP_UDP_CLIENT_PORT = "udp_acceleration_client_port"
internal const val SEP_UDP_CLIENT_KEY_V2 = "udp_acceleration_client_key_v2"
internal const val SEP_UDP_CLIENT_COOKIE = "udp_acceleration_client_cookie"
internal const val SEP_UDP_SERVER_IP = "udp_acceleration_server_ip"
internal const val SEP_UDP_SERVER_PORT = "udp_acceleration_server_port"
internal const val SEP_UDP_SERVER_KEY_V2 = "udp_acceleration_server_key_v2"
internal const val SEP_UDP_SERVER_COOKIE = "udp_acceleration_server_cookie"
internal const val SEP_UDP_ENABLE_FAST_DISCONNECT_DETECT = "udp_accel_fast_disconnect_detect"
internal const val SEP_UDP_SUPPORT_FAST_DISCONNECT_DETECT = "support_udp_accel_fast_disconnect_detect"


internal val SOFTETHER_BOOLEAN_PROPERTIES = arrayOf(
    SEP_USE_ENCRYPT,
    SEP_USE_COMPRESS,
    SEP_USE_UDP_ACCELERATION,
    SEP_UDP_USE_ENCRYPTION,
    SEP_UDP_SUPPORT_FAST_DISCONNECT_DETECT,
    SEP_UDP_ENABLE_FAST_DISCONNECT_DETECT,
)

internal val SOFTETHER_BE_INT_PROPERTIES = arrayOf(
    SEP_CLIENT_ID,
    SEP_VERSION,
    SEP_BUILD,
    SEP_CLIENT_VER,
    SEP_CLIENT_BUILD,
    SEP_AUTH_TYPE,
    SEP_PROTOCOL,
    SEP_MAX_CONNECTION,
    SEP_HALF_CONNECTION,
    SEP_ERROR,
    SEP_UDP_VERSION,
    SEP_UDP_MAX_VERSION,
    SEP_UDP_CLIENT_PORT,
    SEP_UDP_SERVER_PORT,
    SEP_UDP_CLIENT_COOKIE,
    SEP_UDP_SERVER_COOKIE,
)

internal val SOFTETHER_LE_INT_PROPERTIES = arrayOf(
    SEP_CLIENT_PORT,
    SEP_CLIENT_PRODUCT_BUILD,
    SEP_CLIENT_PRODUCT_VER,
    SEP_PROXY_PORT,
    SEP_SERVER_PORT2,
    SEP_SERVER_PRODUCT_BUILD,
    SEP_SERVER_PRODUCT_VER,
)

internal val SOFTETHER_LONG_PROPERTIES = arrayOf<String>()

internal val SOFTETHER_ADDRESS_PROPERTIES = arrayOf(
    SEP_CLIENT_IP_ADDRESS,
    SEP_PROXY_IP_ADDRESS,
    SEP_SERVER_IP_ADDRESS,
    SEP_UDP_CLIENT_IP,
    SEP_UDP_SERVER_IP,
)

internal val SOFTETHER_BYTES_PROPERTIES = arrayOf(
    SEP_UNIQUE_ID_CAMEL,
    SEP_UNIQUE_ID_SNAKE,
    SEP_PEN_CORE,
    SEP_UDP_CLIENT_KEY_V2,
    SEP_UDP_SERVER_KEY_V2,
)

internal val SOFTETHER_160BITS_PROPERTIES = arrayOf(
    SEP_SECURE_PASSWORD,
    SEP_RANDOM,
)

internal val SOFTETHER_ASCII_PROPERTIES = arrayOf(
    SEP_CLIENT_STR,
    SEP_CLIENT_HOSTNAME,
    SEP_CLIENT_OS_NAME,
    SEP_CLIENT_OS_VER,
    SEP_CLIENT_PRODUCT_NAME,
    SEP_HELLO,
    SEP_SERVER_HOSTNAME,
    SEP_SERVER_PRODUCT_NAME,
    SEP_METHOD,
    SEP_USERNAME,
    SEP_HUB_NAME,
)

internal val SOFTETHER_UTF8_PROPERTIES = arrayOf<String>()
