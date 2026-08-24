package com.zx.homecamera.core.storage

import android.util.Log
import java.io.File
import java.time.Instant

/**
 * 智能内容清理编排器：扫描候选录像片段 -> 内容分析 -> 判为无效的删除。
 *
 * 候选过滤规则（保守，避免误删与重复处理）：
 * - 排除正在录制的文件（[excludeFileIds]，未 stop 的 mp4 缺 moov，无法抽帧且不可删）。
 * - 损坏文件（不可播放）直接删除：无法播放也无法分析内容，留着只是浪费空间。
 * - 仅处理保护窗之外的文件（[guardWindowCutoffMillis] 之前开始录制的片段），
 *   近期片段即使判为无效也先保留，防止误删事件边缘。
 * - 已分析过的文件跳过（[isAnalyzed]/[markAnalyzed]）：录像关闭后内容不再变化，
 *   单轮抽帧分析对全部历史片段而言开销巨大（数千片段可达小时级），
 *   持久化分析结果使每轮只处理新关闭的片段。
 *
 * 与 [RecordingStorageCleaner] 分工：本类只做"内容无效"删除；cleaner 负责日期过期与空间压力删除。
 * 二者运行在不同的执行器上：本类单轮耗时长，不得阻塞 cleaner 的空间压力删除。
 *
 * 为便于单测，完整性校验通过 [isPlayable] 函数注入（生产传 [RecordingIntegrityChecker.isPlayable]，
 * 单测可传 always-true 的 lambda）；文件扫描通过 [RecordingLibrary] 完成，其本身为纯逻辑可测。
 * [invalidate] 用于内容清理删除文件后清除完整性缓存，默认空实现（单测可忽略）。
 */
class SmartCleanupCoordinator(
    private val library: RecordingLibrary,
    private val contentAnalyzer: RecordingContentAnalyzer,
    private val isPlayable: (fileId: String, file: File) -> Boolean = { _, _ -> true },
    private val invalidate: (fileId: String) -> Unit = {},
    private val isAnalyzed: (fileId: String) -> Boolean = { false },
    private val markAnalyzed: (fileId: String) -> Unit = {},
    // 日志注入而非直接调 android.util.Log：本类需 JVM 单测，而 android.jar 的 Log
    // 在单测里是未 mock 的桩（抛 RuntimeException）。
    private val logInfo: (String, String) -> Unit = { tag, msg -> Log.i(tag, msg) },
) {
    /**
     * 执行一轮内容清理。
     *
     * @param root 录像根目录。
     * @param excludeFileIds 不参与分析的 fileId 集合（正在录制的文件）。
     * @param guardWindowCutoffMillis 保护窗截止时刻（epoch millis），早于此时刻开始的片段才参与分析。
     * @return 被删除的文件列表。
     */
    fun clean(
        root: File,
        excludeFileIds: Set<String>,
        guardWindowCutoffMillis: Long,
    ): List<File> {
        if (!root.isDirectory) return emptyList()
        val deleted = mutableListOf<File>()
        var analyzed = 0
        var skippedAnalyzed = 0
        var skippedGuard = 0
        var skippedRecording = 0
        var deletedCorrupted = 0
        for (date in library.listDates()) {
            val entries = library.listFiles(date, excludeFileIds)
            for (entry in entries) {
                // 保护窗内的片段跳过：近期事件边缘不删。
                if (entry.startMillis >= guardWindowCutoffMillis) { skippedGuard++; continue }
                // 正在录制的文件不分析（理论上已在 excludeFileIds 内，双重保险）。
                if (entry.recording) { skippedRecording++; continue }
                val file = File(root, entry.fileId)
                if (!file.isFile) continue
                // 已分析过的片段跳过：内容不再变化，重复抽帧只是浪费 CPU。
                if (isAnalyzed(entry.fileId)) { skippedAnalyzed++; continue }
                // 损坏文件（缺 moov / 截断 / 无法播放）直接删除：无法播放也无法分析内容，留着只是浪费空间。
                if (!isPlayable(entry.fileId, file)) {
                    if (file.delete()) {
                        invalidate(entry.fileId)
                        deleted.add(file)
                        deletedCorrupted++
                        logInfo(TAG, "deleted corrupted: ${entry.fileId}")
                    }
                    continue
                }
                analyzed++
                val result = contentAnalyzer.isEffective(file)
                logInfo(TAG, "analyze ${entry.fileId}: effective=${result.effective} reason=${result.reason}")
                // 无论有效与否都标记已分析：有效片段保留后不再重复分析。
                markAnalyzed(entry.fileId)
                if (!result.effective && file.delete()) {
                    invalidate(entry.fileId)
                    deleted.add(file)
                    logInfo(TAG, "deleted ineffective: ${entry.fileId} reason=${result.reason}")
                }
            }
        }
        logInfo(
            TAG,
            "clean done: analyzed=$analyzed skippedAnalyzed=$skippedAnalyzed " +
                "deleted=${deleted.size} deletedCorrupted=$deletedCorrupted " +
                "skippedGuard=$skippedGuard skippedRecording=$skippedRecording cutoff=$guardWindowCutoffMillis",
        )
        return deleted
    }

    companion object {
        private const val TAG = "SmartCleanup"

        /** 计算保护窗截止时刻：now - guardWindowMinutes。 */
        fun guardWindowCutoff(nowMillis: Long, guardWindowMinutes: Int): Long =
            Instant.ofEpochMilli(nowMillis).minusSeconds(guardWindowMinutes * 60L).toEpochMilli()
    }
}
