package com.example.webdavsync.domain.model

/**
 * 单次同步的实时进度,由 SyncEngine 通过回调向上汇报。
 */
data class SyncProgress(
    val phase: Phase = Phase.IDLE,
    val taskName: String = "",
    val totalFiles: Int = 0,
    val doneFiles: Int = 0,
    val totalBytes: Long = 0L,
    val doneBytes: Long = 0L,
    val currentFile: String = "",
    val downloaded: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val remoteChanged: Int = 0,
    val message: String = "",
    val errors: List<String> = emptyList()
) {
    enum class Phase { IDLE, LISTING, COMPARING, DOWNLOADING, FINISHED, CANCELLED, FAILED, SKIPPED }

    val percent: Int
        get() = if (totalFiles <= 0) 0 else ((doneFiles.toFloat() / totalFiles) * 100).toInt().coerceIn(0, 100)
}

/** 单个文件同步结果。 */
enum class FileResult { DOWNLOADED, SKIPPED, UPDATED, FAILED, REMOTE_CHANGED }
