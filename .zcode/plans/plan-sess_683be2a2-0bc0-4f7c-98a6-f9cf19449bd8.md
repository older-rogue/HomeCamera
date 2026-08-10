# 存储清理优化：P0 周期性清理 + P1 阈值与混合粒度删除

## 目标
1. **P0**：解决"运行期间不清理、只有服务启动时清理一次"的隐患 -- 每次切片（2 分钟）触发一次异步清理
2. **P1a**：空间阈值 `minimumFreeBytes` 从 512MB 调到 **2GB**
3. **P1b**：空间压力删除（第二级）从"删整个日期目录"改为"按最旧 mp4 文件逐个删"，过期目录仍整天删

## P0：切片触发周期性清理（3 个生产文件）

### 1. `Mp4SegmentRecorder.kt`（新增回调 + 触发点）
- 构造新增参数（第 20 行 `onError` 旁）：
  ```kotlin
  private val onSegmentStarted: () -> Unit = {},
  ```
- 在 `startSegment` 末尾触发（第 172 行 `clock.onSegmentStarted(...)` 之后）：
  ```kotlin
  onSegmentStarted()
  ```
- 线程安全：在 `@Synchronized fun writeSample` 锁内由视频 drain 线程调用，但回调本身只做 `executor.execute {}` 投递，不阻塞录制线程
- 用 `() -> Unit` 而非传 File -- service 只需"切片发生了"的信号

### 2. `CameraH264Streamer.kt`（透传回调）
- 构造新增参数（第 42 行 `onRecordingError` 旁）：
  ```kotlin
  private val onSegmentStarted: () -> Unit = {},
  ```
- 透传给 recorder（第 86-88 行）：
  ```kotlin
  private val recorder = recordingRoot?.let { root ->
      Mp4SegmentRecorder(root, onError = onRecordingError, onSegmentStarted = onSegmentStarted)
  }
  ```

### 3. `CollectorForegroundService.kt`（接线 + 异步触发）
- 构造 streamer 时（第 92-102 行）新增回调：
  ```kotlin
  onSegmentStarted = {
      runCatching { maintenanceExecutor?.executeCatching(::cleanRecordingsOnce) }
  },
  ```
- **关键**：用 `runCatching` 包裹。`executeCatching` 内部 `execute {}` 在 `runCatching` 外（第 353-357 行），`shutdownNow` 后会抛 `RejectedExecutionException` 不被吞；外层 `runCatching` 确保停止过程中触发的回调不崩溃
- `maintenanceExecutor` 在 streamer 之前创建（第 129 行 vs 第 92 行），但首次切片在 2 分钟后，此时 executor 已就绪；`?.` 空检查防御 stop 竞态
- 复用已有 `maintenanceExecutor`（单线程，任务自动串行），复用已有 `cleanRecordingsOnce`（幂等）

## P1a：阈值 2GB（1 个生产文件）

### `RecordingRetentionPolicy.kt:9`
```kotlin
private val minimumFreeBytes: Long = 2L * 1024L * 1024L * 1024L,  // 512MB → 2GB
```

## P1b：混合粒度删除（2 个生产文件 + 测试）

### `RecordingRetentionPolicy.kt`（重构返回类型）
- 删除旧方法 `directoriesToDelete`，新增 `deletionPlan` 返回复合结构：
  ```kotlin
  data class DeletionPlan(
      val directories: List<File>,  // 整目录删除（过期）
      val files: List<File>,        // 单文件删除（空间压力，最旧优先）
  )

  fun deletionPlan(root: File, today: LocalDate, usableBytes: Long): DeletionPlan {
      // 第一级：过期目录仍整天删（逻辑不变）
      val expired = dateDirectories
          .filter { (date, _) -> date.isBefore(oldestKeptDate) }
          .map { (_, file) -> file }

      var projectedUsableBytes = usableBytes + expired.sumOf { it.sizeBytes() }
      if (projectedUsableBytes >= minimumFreeBytes) return DeletionPlan(expired, emptyList())

      // 第二级：从未过期目录里按日期从旧到新，逐个删最旧 mp4 文件
      val expiredSet = expired.toSet()
      val pressureFiles = mutableListOf<File>()
      for ((_, dir) in dateDirectories.filterNot { it.second in expiredSet }) {
          if (projectedUsableBytes >= minimumFreeBytes) break
          // mp4 文件名 HH-mm-ss，字典序即时间序
          val files = dir.listFiles { it.isFile && it.extension.equals("mp4", true) }
              ?.sortedBy { it.nameWithoutExtension }
              .orEmpty()
          for (file in files) {
              pressureFiles.add(file)
              projectedUsableBytes += file.length()
              if (projectedUsableBytes >= minimumFreeBytes) break
          }
      }
      return DeletionPlan(expired, pressureFiles)
  }
  ```
- **语义匹配**：`先 add 再检查 break` = 删到投影达标那一个为止，与原 `takeWhileInclusive` 行为一致
- **安全**：从最旧文件删起，正在录制的当前文件在最新目录末尾，天然不会被删

### `RecordingStorageCleaner.kt`（适配新 API）
```kotlin
fun clean(root: File, today: LocalDate, usableBytes: Long): CleanResult {
    val plan = policy.deletionPlan(root, today, usableBytes)
    val deletedDirs = plan.directories.filter { it.deleteRecursively() }
    val deletedFiles = plan.files.filter { it.delete() }
    return CleanResult(deletedDirectories = deletedDirs, deletedFiles = deletedFiles)
}

data class CleanResult(
    val deletedDirectories: List<File>,
    val deletedFiles: List<File> = emptyList(),  // 新增字段，默认空保持兼容
)
```

## 测试改动（3 个测试文件）

### `RecordingRetentionPolicyTest.kt`（适配新 API + 新增用例）
- 两个现有测试改用 `deletionPlan().directories`：
  - `deletesDateDirectoriesOlderThanRetentionWindow`：空间充足，断言 `directories` = 过期目录，`files` 为空
  - `deletesOldestRemainingDateDirectoriesUntilFreeSpaceIsSafe`：**重写**为文件级删除测试。每个日期目录放多个 mp4，断言空间压力下删了过期目录 + 未过期目录里最旧的若干 mp4 文件，较新文件保留
- 复用现有 `dateDirectory` 助手，但目录内放多个 `HH-mm-ss.mp4` 文件（参考 `RecordingLibraryTest` 命名）
- 新增边界用例：删到"刚好达标那一个文件"被包含（验证 `takeWhileInclusive` 语义）

### `RecordingStorageCleanerTest.kt`（验证文件级删除副作用）
- 现有目录删除测试无需改（`deletedDirectories` 字段保留）
- 新增用例：空间压力下验证 `deletedFiles` 非空、文件确实从磁盘删除（`assertFalse(file.exists())`）、较新文件保留

## 不改动（明确范围）
- `RecordingFilePlanner.kt`（切片时长 2 分钟不变）
- `RecordingLibrary.kt`（只读扫描，不涉及删除）
- `RecordingIntegrityChecker.kt`（损坏检测，不参与清理）
- 编码器/码率配置（P2 范畴，本次不动）
- `keepDays = 7`（保留天数不变，接受"磁盘不够按空间优先删"的现实）

## 验收
- 运行 storage 包下全部单元测试通过
- 确认 `cleanRecordingsOnce` 在切片回调路径上被调用（代码审查接线正确性）
