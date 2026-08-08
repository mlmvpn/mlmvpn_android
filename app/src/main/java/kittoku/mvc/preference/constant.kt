package kittoku.mvc.preference


internal enum class MvcPreference {
    HOME_HOSTNAME,
    HOME_HUB,
    HOME_USERNAME,
    HOME_PASSWORD,
    HOME_CONNECTOR,
    HOME_STATUS,
    SSL_PORT,
    SSL_VERSION,
    SSL_DO_SELECT_SUITES,
    SSL_SUITES,
    UDP_ENABLE_ACCELERATION,
    UDP_STATUS, // used as volatile information
    ETHERNET_MTU,
    ETHERNET_MAC_ADDRESS,
    LOG_DO_SAVE_LOG,
    LOG_DIRECTORY,
    ABOUT_PROJECT,
    ABOUT_LINK_MVC,
    ABOUT_LINK_SOFTETHER,
}
