# ESP32 代码整理说明

本目录用于统一收纳 ESP32 相关代码，避免根目录继续堆放多个实验版本。

目录规划：

- `active/`
  - 当前仍有参考价值、并且与 Android 主线接口一致的 server 草图
- `diagnostics/`
  - 硬件检查、摄像头排查、I2C/GPIO 诊断脚本
- `references/`
  - 第三方样例、早期验证代码、TFT 显示类参考代码

当前 Android 主线固定接口为：

- Wi-Fi: `ToFSense_AP`
- 图像: `http://192.168.4.1:81/`
- 深度: `TCP 192.168.4.1:8080`
- 深度格式: `{"d":[64个毫米值]}\n`

更详细的接口记录见：

- `开发日志/2026-03-23_安卓与ESP32通信接口冻结.md`

当前文件概览：

- `active/esp32_tof_server/esp32_tof_server.ino`
  - 修改时间：`2026-03-06 23:56:57`
  - 作用：`HTTP 81` 单帧 JPEG + `TCP 8080` 深度 JSON
  - 特点：更像 `ESP32-S3-EYE` 接线版本，ToF 走 `43/44`
- `active/esp32_s3_eye_tof_server/esp32_s3_eye_tof_server.ino`
  - 修改时间：`2026-03-07 00:27:12`
  - 作用：与上面同协议的板级变体
  - 特点：更像后续稳定化版本，ToF 走 `47/48`
- `references/Esp32Cam/Sketch_08_1_Camera_TFT.ino`
  - 修改时间：`2026-03-06 21:30:12`
  - 作用：本地 TFT 显示相机画面，不参与 Android 通信主线
- `references/Esp32Cam/camera_pins.h`
  - 修改时间：`2026-03-06 21:30:10`
  - 作用：参考引脚定义
