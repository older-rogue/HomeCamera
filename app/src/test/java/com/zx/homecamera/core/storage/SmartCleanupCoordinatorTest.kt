package com.zx.homecamera.core.storage

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SmartCleanupCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val config = ContentAnalysisConfig(
        luminanceThreshold = 12,
        motionThreshold = 2.5,
        sampleWidth = 4,
        sampleHeight = 4,
    )

    private fun frame(value: Int) = IntArray(16) { value }

    /** 静默日志：绕开 JVM 单测里未 mock 的 android.util.Log。 */
    private val noLog: (String, String) -> Unit = { _, _ -> }

    /**
     * 在 root 下创建一个日期目录并写入 mp4 文件，返回该文件与其 startMillis。
     * 文件名用 HH-mm-ss，startMillis 由文件名解析（与 RecordingLibrary 一致）。
     */
    private fun createSegment(
        root: File,
        date: String,
        time: String,
        content: String = "x",
    ): File {
        val dateDir = File(root, date).apply { check(mkdirs() || exists()) }
        return File(dateDir, "$time.mp4").apply { writeText(content) }
    }

    private fun startMillis(date: String, time: String): Long =
        LocalDateTime.parse("${date}T$time", DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"))
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 构造一个按文件内容决定有效性的 analyzer：[ineffectiveFiles] 中的文件判无效。 */
    private fun analyzer(ineffectiveFiles: Set<File>): RecordingContentAnalyzer {
        val sampler = object : FrameSampler {
            override fun sample(file: File, config: ContentAnalysisConfig): List<IntArray>? =
                if (file in ineffectiveFiles) List(24) { frame(5) } else MutableList(24) { frame(100) }.also { it[5] = frame(180) }
        }
        return RecordingContentAnalyzer(sampler, config)
    }

    @Test
    fun deletesIneffectiveFilesOutsideGuardWindow() {
        val root = temporaryFolder.newFolder("recordings")
        // 两个早期文件：一个无效（全黑），一个有效（有运动）
        val dark = createSegment(root, "2026-07-20", "00-00-00")
        val motion = createSegment(root, "2026-07-20", "00-02-00")
        val cutoff = startMillis("2026-07-20", "00-10-00")

        val coordinator = SmartCleanupCoordinator(
            library = RecordingLibrary(root),
            contentAnalyzer = analyzer(setOf(dark)),
            isPlayable = { _, _ -> true },
            logInfo = noLog,
        )
        val deleted = coordinator.clean(root, excludeFileIds = emptySet(), guardWindowCutoffMillis = cutoff)

        assertEquals(listOf(dark), deleted)
        assertFalse(dark.exists())
        assertTrue(motion.exists())
    }

    @Test
    fun skipsFilesInsideGuardWindow() {
        val root = temporaryFolder.newFolder("recordings")
        // 保护窗内的无效文件：不应被删除
        val darkRecent = createSegment(root, "2026-07-20", "00-00-00")
        // 保护窗截止在 00-00-00 之前，意味着该文件在窗内（startMillis >= cutoff）
        val cutoff = startMillis("2026-07-20", "00-00-00")

        val coordinator = SmartCleanupCoordinator(
            library = RecordingLibrary(root),
            contentAnalyzer = analyzer(setOf(darkRecent)),
            isPlayable = { _, _ -> true },
            logInfo = noLog,
        )
        val deleted = coordinator.clean(root, excludeFileIds = emptySet(), guardWindowCutoffMillis = cutoff)

        assertTrue(deleted.isEmpty())
        assertTrue(darkRecent.exists())
    }

    @Test
    fun skipsRecordingFiles() {
        val root = temporaryFolder.newFolder("recordings")
        val recording = createSegment(root, "2026-07-20", "00-00-00")
        val cutoff = startMillis("2026-07-20", "00-10-00")

        val coordinator = SmartCleanupCoordinator(
            library = RecordingLibrary(root),
            contentAnalyzer = analyzer(setOf(recording)),
            isPlayable = { _, _ -> true },
            logInfo = noLog,
        )
        val deleted = coordinator.clean(
            root = root,
            excludeFileIds = setOf("2026-07-20/00-00-00.mp4"),
            guardWindowCutoffMillis = cutoff,
        )

        assertTrue(deleted.isEmpty())
        assertTrue(recording.exists())
    }

    @Test
    fun deletesCorruptedFiles() {
        val root = temporaryFolder.newFolder("recordings")
        val corrupted = createSegment(root, "2026-07-20", "00-00-00")
        val cutoff = startMillis("2026-07-20", "00-10-00")

        val coordinator = SmartCleanupCoordinator(
            library = RecordingLibrary(root),
            contentAnalyzer = analyzer(setOf(corrupted)),
            isPlayable = { _, _ -> false }, // 所有文件都判损坏
            logInfo = noLog,
        )
        val deleted = coordinator.clean(root, excludeFileIds = emptySet(), guardWindowCutoffMillis = cutoff)

        // 损坏文件（缺 moov / 截断）无法播放也无法分析内容，直接删除
        assertEquals(listOf(corrupted), deleted)
        assertFalse(corrupted.exists())
    }

    @Test
    fun skipsAlreadyAnalyzedFiles() {
        val root = temporaryFolder.newFolder("recordings")
        val dark = createSegment(root, "2026-07-20", "00-00-00") // 内容无效
        val cutoff = startMillis("2026-07-20", "00-10-00")
        val analyzedIds = mutableSetOf("2026-07-20/00-00-00.mp4")
        val marked = mutableListOf<String>()

        val coordinator = SmartCleanupCoordinator(
            library = RecordingLibrary(root),
            contentAnalyzer = analyzer(setOf(dark)),
            isPlayable = { _, _ -> true },
            isAnalyzed = { it in analyzedIds },
            markAnalyzed = { marked.add(it) },
            logInfo = noLog,
        )
        val deleted = coordinator.clean(root, excludeFileIds = emptySet(), guardWindowCutoffMillis = cutoff)

        // 已分析过的片段（无论上轮判定结果如何）不再重复抽帧分析：
        // 曾判无效但已分析的文件也不删除，只处理未分析的新片段
        assertTrue(deleted.isEmpty())
        assertTrue(dark.exists())
        assertTrue(marked.isEmpty())
    }

    @Test
    fun marksAnalyzedRegardlessOfEffectiveness() {
        val root = temporaryFolder.newFolder("recordings")
        val dark = createSegment(root, "2026-07-20", "00-00-00") // 无效（全黑）
        val motion = createSegment(root, "2026-07-20", "00-02-00") // 有效（有运动）
        val cutoff = startMillis("2026-07-20", "00-10-00")
        val marked = mutableListOf<String>()

        val coordinator = SmartCleanupCoordinator(
            library = RecordingLibrary(root),
            contentAnalyzer = analyzer(setOf(dark)),
            isPlayable = { _, _ -> true },
            markAnalyzed = { marked.add(it) },
            logInfo = noLog,
        )
        val deleted = coordinator.clean(root, excludeFileIds = emptySet(), guardWindowCutoffMillis = cutoff)

        // 无效的被删除；有效的保留但同样被标记已分析（内容不再变化，下轮不再抽帧）
        assertEquals(listOf(dark), deleted)
        assertEquals(
            listOf("2026-07-20/00-00-00.mp4", "2026-07-20/00-02-00.mp4"),
            marked,
        )
        assertTrue(motion.exists())
    }

    @Test
    fun guardWindowCutoffComputesNowMinusMinutes() {
        // 30 分钟保护窗
        val now = 1_000_000_000L
        val cutoff = SmartCleanupCoordinator.guardWindowCutoff(now, 30)
        assertEquals(now - 30 * 60_000L, cutoff)
    }

    @Test
    fun returnsEmptyWhenRootMissing() {
        val coordinator = SmartCleanupCoordinator(
            library = RecordingLibrary(File(temporaryFolder.root, "no-such-dir")),
            contentAnalyzer = analyzer(emptySet()),
        )
        val deleted = coordinator.clean(
            root = File(temporaryFolder.root, "no-such-dir"),
            excludeFileIds = emptySet(),
            guardWindowCutoffMillis = 0L,
        )
        assertTrue(deleted.isEmpty())
    }
}
