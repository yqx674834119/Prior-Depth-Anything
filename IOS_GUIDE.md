# iOS 移植项目指南

本项目提供了一个完整的 iOS 应用源码框架 (SwiftUI)，用于运行 Prior-Depth-Anything 模型。模型已转换为 Core ML 格式，针对 Apple Neural Engine 进行了优化。

## 目录结构
*   `ios_project/`: iOS 项目根目录
    *   `PriorDepthApp/`:
        *   `PriorDepthApp.swift`: App 入口
        *   `ContentView.swift`: 主界面 UI (双图选择 + 推理)
        *   `ModelRunner.swift`: Core ML 模型调用逻辑
        *   `Resources/`: **在此处放置 mlpackage 模型**
*   `ios_export.py`: 用于生成 Core ML 模型的脚本

## 快速开始

### 1. 准备模型
我已经为您运行了 `ios_export.py`。请确保当前目录下有生成的 **`PriorDepthAnythingVits.mlpackage`**。

### 2. 创建 Xcode 项目
由于我只能生成源码文件，您需要在 Mac 上使用 Xcode 创建项目并将文件拖入：

1.  打开 **Xcode**，选择 **Create a new Xcode project**。
2.  选择 **iOS > App**。
3.  Product Name 输入 `PriorDepthApp`，Interface 选择 **SwiftUI**，Language 选择 **Swift**。
4.  创建后，找到我生成的 `ios_project/PriorDepthApp` 文件夹中的三个 Swift 文件：
    *   `PriorDepthApp.swift`
    *   `ContentView.swift`
    *   `ModelRunner.swift`
5.  将这三个文件拖入 Xcode 项目导航栏，**替换**默认生成的同名文件（如有）。

### 3. 导入模型
1.  找到生成的 `PriorDepthAnythingVits.mlpackage` 文件。
2.  将其直接**拖入** Xcode 项目导航栏。
3.  Xcode 会自动编译模型并生成对应的 Swift 类接口。
4.  (可选) 点击模型文件，在 Performance 选项卡中确认为 "Neural Engine" 加速。

### 4. 运行
1.  连接 iPhone (iOS 16+) 或选择模拟器。
2.  点击 **Run** (Play 按钮)。
3.  App 启动后：
    *   点击 **Select RGB** 选择彩色图。
    *   点击 **Select Prior** 选择深度先验图。
    *   点击 **Run Inference** 查看结果。

## 技术细节
*   **模型**: Core ML (`.mlpackage`)
*   **优化**: 使用了和 Android 相同的 `double_global=True` 策略，模型输入为 518x518 的 CVPixelBuffer。
*   **推理引擎**: 优先使用 Apple Neural Engine (ANE)，若不可用则回退至 GPU/CPU。

## 注意事项
*   生成的 `ModelRunner.swift` 中使用通用 API 加载模型；如果您拖入模型后 Xcode 生成了强类型类 `PriorDepthAnythingVits`，您可以将代码中的 `MLModel(...)` 替换为 `PriorDepthAnythingVits(...)` 以获得更好的类型安全，但当前代码无需修改也能正常工作（基于文件名加载）。
