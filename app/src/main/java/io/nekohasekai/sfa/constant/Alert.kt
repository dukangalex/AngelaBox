package io.nekohasekai.sfa.constant

enum class Alert {
    RequestVPNPermission,
    RequestNotificationPermission,
    RequestLocationPermission,
    EmptyConfiguration,
    StartCommandServer,
    CreateService,
    StartService,
    /** Appended. Ordinal is sent across the binder; do not reorder. */
    RestartAsVpn,
}
