package com.indirici.app.domain.model

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELED;

    val isActiveOrQueued: Boolean
        get() = this == QUEUED || this == DOWNLOADING
}
