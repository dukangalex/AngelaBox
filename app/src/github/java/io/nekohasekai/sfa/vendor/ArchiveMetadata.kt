package io.nekohasekai.sfa.vendor

/** An unreadable archive cannot be accepted as a trusted update. */
internal fun <T : Any> requireArchiveMetadata(info: T?, onReject: () -> Unit): T {
    if (info == null) {
        onReject()
        throw IllegalArgumentException("无法读取安装包信息")
    }
    return info
}
