# IPCamera — Android H265 RTSP 摄像头服务

一个最小化 Android APK：把手机摄像头画面用 **H265 (HEVC)** 编码，并通过内置 **RTSP 服务**推给局域网内的播放器（VLC / ffplay 等）。

## 功能

- 首页 `TextureView` 实时预览摄像头画面
- **开启服务** / **停止服务** 按钮
- **切换摄像头** 按钮（前/后摄，需设备有多个摄像头），选择会持久保存
- **设置** 页面配置并本地持久化：
  - RTSP 端口、用户名、密码（支持 Basic / Digest 认证）
  - 摄像头编号、分辨率、帧率、码率、关键帧间隔、自动跳过无画面摄像头
- 首页显示当前 RTSP 地址，例如 `rtsp://192.168.1.5:8554/`

## 目录结构

```
app/src/main/java/com/example/ipcamera/
├── MainActivity.kt                  # 首页 UI、权限、服务启停
├── SettingsActivity.kt              # 设置页
├── camera/CameraController.kt       # Camera2 采集 + MediaCodec H265 编码
├── rtsp/RtspServer.kt               # RTSP 服务（认证/SDP/会话/RTP 传输）
├── rtsp/RtpPacketizer.kt            # H265 RTP 打包（RFC 7798, Single NAL / FU）
└── util/Preferences.kt              # SharedPreferences 配置存储
```

## 编译

用 Android Studio 打开项目根目录，同步 Gradle 后运行到真机（需要支持 H265 编码的设备，大部分现代手机都支持）。

命令行（需配置好 JAVA_HOME / ANDROID_HOME）：

```bash
gradle assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 使用

1. 安装 APK 并打开，授予摄像头权限。
2. （可选）进入设置修改端口、用户名、密码及摄像头参数，保存。配置会写入本机 SharedPreferences，下次启动仍然生效；若服务正在运行，保存后会自动重启并应用。
3. 点击「开启服务」，首页显示本机 RTSP 地址。
4. 在同一局域网的电脑上用播放器打开该地址：

   - VLC：媒体 → 打开网络流 → 输入 `rtsp://手机IP:端口/`，输入设置的用户名/密码。
   - ffplay：`ffplay -rtsp_transport tcp rtsp://admin:123456@手机IP:8554/`

> 注意：H265 解码需要播放器支持；VLC 3.x+ / ffplay 均可。

## 实现说明

- **编码**：`MediaCodec` + `MIMETYPE_VIDEO_HEVC`，Camera2 同时输出到预览 `Surface` 和编码器 `inputSurface`。
- **参数集**：从 `MediaFormat` 的 csd-0/1/2 中提取 VPS/SPS/PPS，Base64 后写入 SDP 的 `sprop-vps/sprop-sps/sprop-pps`。
- **RTP**：时间戳按 90kHz 时钟（微秒 × 9 / 100），动态负载类型 96，支持单 NAL 与 FU 分片。
- **传输**：优先 TCP interleaved（`RTP/AVP/TCP;interleaved=0-1`），也兼容 UDP（`client_port`）。
- **认证**：DESCRIBE/SETUP/PLAY 要求 Basic 或 Digest 认证，默认 `admin / 123456`，端口默认 `8554`。
- **配置持久化**：`Preferences.kt` 使用 `SharedPreferences` 保存 RTSP 与摄像头配置；首页「切换摄像头」也会保存当前选择。

## 已知限制

- 服务运行在 Activity 内，切到后台或锁屏时系统可能回收摄像头；本示例未使用前台 Service 保活。
- 未实现 RTCP 发送与拥塞控制，局域网演示足够。
- 若设备不支持 H265 硬件编码（少数旧设备/模拟器），点击开启服务会提示失败。
