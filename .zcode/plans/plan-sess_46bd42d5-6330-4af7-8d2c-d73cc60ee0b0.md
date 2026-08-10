# 优化客户端 UDP 接收与解码链路

## 问题根因

A手机（realme RMX3995，弱CPU）作为客户端时，`receiveFrames` 线程在 `socket.receive()` 之后还做 `MediaUdpPacket.decode` + `MediaFrameReassembler.accept`（HashMap+数组拷贝）+ `videoQueue.offer`（synchronized锁竞争），导致 receive 被阻塞 50-100ms，内核 socket buffer 溢出丢包约1/3。关键帧分片凑不齐 → `RealtimeVideoFrameQueue` 丢弃所有P帧 → `input=0` → 长时间黑屏。

## 改动方案（3项，逐步验证）

### 改动1：receive 线程与重组线程分离（P0 - 核心改动）

**文件**：`H264UdpViewer.kt`

**当前**：`receiveFrames` 单线程做 receive + decode + reassemble + offer，4个任务共享 `newFixedThreadPool(4)`

**改为**：5个线程，receive 线程极致轻量
- `receiveFrames`：只做 `socket.receive()` → 拷贝到独立 `ByteArray` → 放入无锁队列 `ConcurrentLinkedQueue<ByteArray>`。不做 decode/reassemble/offer，不持任何锁
- 新增 `reassembleFrames`：从 `ConcurrentLinkedQueue` 取原始字节 → decode + reassemble → offer 到 videoQueue/audioQueue
- 线程池从 `newFixedThreadPool(4)` 改为 `newFixedThreadPool(5)`，新增1个线程给 reassemble
- receive 线程设为 `THREAD_PRIORITY_URGENT_AUDIO`（-19，比AUDIO更高），确保不被抢占

**预期效果**：receive 线程不再被任何逻辑阻塞，recv gap 大幅减少，丢包率下降

### 改动2：videoQueue 的 offer 改为非阻塞（P0 - 你的方案"收不了就放弃"）

**文件**：`RealtimeVideoFrameQueue.kt`

**当前**：`offer` 用 `@Synchronized`，与 `poll` 竞争锁。如果 decode 线程正在 poll，receive 线程（改动1后是 reassemble 线程）的 offer 会阻塞。

**改为**：offer 内部保持 synchronized（队列内部一致性需要），但去掉 `notifyAll`（改用 ReentrantLock + Condition，或保持 synchronized 但确保锁内操作极简）。关键是锁内只做队列增删，不做任何 IO/日志。

**实际上**：分析后认为当前 offer 锁内操作已经很简（只有队列增删），锁竞争不是主要瓶颈。改动1分离线程后，reassemble 线程和 decode 线程的锁竞争会自然减轻。**此项改动较小，与改动1合并实施**。

### 改动3：关键帧走 TCP 补发（P1 - 根治关键帧丢包）

**文件**：`ControlProtocol.kt`、`CollectorForegroundService.kt`、`CameraH264Streamer.kt`、`H264UdpViewer.kt`

**当前**：`addClient` 时通过 UDP 补发 codec config + 请求新关键帧，新关键帧也走 UDP，弱链路下同样会丢

**改为**：
1. 新增控制消息 `ControlMessage.KeyFramePayload(flags, base64Data)`，通过 TCP 控制信道传输完整关键帧
2. 采集端 `CameraH264Streamer`：drainEncoder 产出关键帧时保存 `latestKeyFrame`；新增方法 `latestKeyFrameData()` 返回 (codecConfig + keyFrame) 字节
3. 采集端 `handleViewStart`：`addClient` 后，如果有 latestKeyFrame，通过 TCP writer 发送 `KeyFramePayload`（codec config 和关键帧各一条），TCP保证可靠到达
4. 客户端 `H264UdpViewer.receiveFrames`（或 reassemble 线程）：收到 TCP 的 KeyFramePayload 后，直接 offer 到 videoQueue（标记为 codec config + key frame），解码器立即可用

**预期效果**：客户端连接后首帧秒出（TCP保证关键帧到达），之后P帧走UDP丢了最多花一下，下个关键帧（I帧间隔2秒，或TCP补发）刷新

## 实施顺序与验证策略

1. **先做改动1+2**（receive线程分离 + 非阻塞），编译装到A手机实测
   - 看 recv gap 是否从 50-100ms 降到 <30ms
   - 看 incomplete 是否下降
   - 看首帧时间是否缩短
2. **如果改动1+2效果不够**，再做改动3（关键帧走TCP）
   - 预期首帧 <2秒
3. 每步实测验证，避免一次性改太多无法定位效果

## 不改动的部分

- 采集端发送逻辑（`RealtimeUdpSender`）：日志显示发送正常，不动
- `H264StreamConfig` 参数：保持 HEAD 的 720P/2400kbps，不降分辨率/码率
- `MediaUdpPacket` 协议：分片格式不动（改动3只用TCP传完整帧，不改UDP分片协议）
- UI 层：不动