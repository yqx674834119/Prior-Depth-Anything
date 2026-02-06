# Android 移植项目指南

本项目提供了一个完整的 Android 应用源码框架，用于运行 Prior-Depth-Anything 模型。由于您的开发环境限制，已为您准备好了源码和模型导出脚本。

## 目录结构
*   `android_project/`: Android 项目根目录
    *   `app/`: 主 App 模块
        *   `src/main/java/com/example/priordepth/`: 源代码
        *   `src/main/assets/`: **在此处放置 ONNX 模型**
*   `mobile_export.py`: 用于生成 ONNX 模型的脚本

## 快速开始

### 1. 准备模型
我已经为您运行了 `mobile_export.py`。如果执行成功，您会在当前目录下看到 `prior_depth_anything_vits.onnx`。
**请手动将该文件复制到 assets 目录（如果您在服务器上操作，我稍后会自动尝试复制）:**
`cp prior_depth_anything_vits.onnx android_project/app/src/main/assets/`

### 2. 导入 Android Studio
1.  启动 Android Studio。
2.  选择 **File > Open**。
3.  选择 `android_project` 文件夹。
4.  等待 Gradle Sync 完成。

### 3. 连接设备并运行
1.  开启 Android 手机的开发者模式和 USB 调试。
2.  连接电脑。
3.  点击 Android Studio 的 **Run** 按钮 (绿色三角形)。
4.  App 将会自动安装并在手机上启动。

### 4. 使用说明
1.  点击 **Select RGB** 选择一张彩色照片。
2.  点击 **Select Prior** 选择对应的深度先验图（或任意作为先验的图像）。
3.  点击 **Run Inference**。
4.  稍等片刻（ViT-Small 模型在手机上可能需要几秒钟），结果将显示在下方。

## 技术细节
*   **模型**: Prior-Depth-Anything (ViT-Small, Double Global mode)
*   **运行库**: ONNX Runtime (Mobile Optimized)
*   **图像尺寸**: 默认压缩至 518x518 进行推理。
*   **依赖**: `torch` 和 `torchvision` 版本已在服务器环境修正，以支持模型导出。

## iOS 发布提示
由于采用了 ONNX Runtime，未来的 iOS 移植非常平滑：
1.  核心模型 (`.onnx`) 可以直接在 iOS 项目复用。
2.  只需使用 Swift 编写 UI 和调用 ONNX Runtime C/C++ API 或 Swift 封装库即可。
