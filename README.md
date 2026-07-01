# HomeCamera

HomeCamera 是一个 Android/Kotlin 局域网家庭摄像头应用。同一个安装包可以作为采集端或客户端使用。

## 功能

- 采集端：使用旧 Android 手机采集摄像头画面，前台服务运行，本地 MP4 分段录像，并通过局域网实时发送视频流。
- 客户端：扫描局域网采集端，连接后实时观看 H.264 视频流；如果采集端启用了音频，也会播放 AAC 音频。
- 本地调试：同一台设备内启动采集和观看链路，便于调试。

## 权限

- Camera：采集端和本地调试必需。
- Microphone：可选；未授权时自动降级为纯视频。
- Notification：Android 13+ 前台服务通知权限；未授权不阻断采集启动。
- Wake lock / Wi-Fi state：用于采集端和观看端保持低延迟局域网传输。

## 端口

- TCP 控制端口：`62001`
- UDP 采集端发送端口：`62010`
- 本地调试 TCP 控制端口：`62002`
- 客户端 UDP 接收端口：运行时动态分配，并通过 `ViewStart` 告知采集端。

## 构建与测试

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

请确保本机安装了项目要求的 Android SDK、JDK 和 Gradle wrapper 可用环境。

## 数据与备份

录像文件存放在应用 external files 的 `recordings/` 目录下。家庭摄像头录像属于敏感数据，应用已禁用 Android 自动备份，避免录像被云备份或设备迁移。

## 发布注意事项

不要把真实 keystore、构建缓存、IDE 本地文件或 Kotlin 错误日志提交到仓库。发布签名建议通过本地 properties 或环境变量配置。
