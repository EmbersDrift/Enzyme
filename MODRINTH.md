# Enzyme

**在游戏内检查、预览和批量转换 Minecraft 资源包。**

**MOD 制作与维护：EmbersDrift**

Enzyme 将资源包转换功能集成到原版资源包界面，支持 Fabric 和 NeoForge。无论是将旧资源包升级到当前版本，还是将较新资源包转换到支持的旧版本，都可以先查看任务预览，再决定是否转换。

## 功能

- **游戏内转换**：检测资源包格式，选择一个或多个未启用的包进行转换。
- **原版界面入口**：在不兼容资源包的加载确认框中，直接进入转换页面。
- **独立转换设置**：提供默认、保守、完整预设，支持缺失材质填充、着色器适配、透明图层修复与覆盖层合成。
- **模型适配**：迁移支持的旧版物品模型谓词，处理新版盾牌模型及自定义模型纹理图集。
- **转换报告与历史**：查看警告和修复详情，重试失败任务，导出诊断报告。
- **原包保护**：转换在副本上执行；已应用及待应用的包受到保护，支持原包归档与恢复。

转换结果不会自动启用。你可以先查看报告，再手动应用新资源包并检查效果。

## 界面演示

以下为 Minecraft 1.21.11 中的游戏内截图，使用演示资源包展示操作流程。

### 选择待转换的资源包

勾选需要转换的包；已应用或待应用的包受到保护。开始之前，可以打开设置或预览转换任务。

![Enzyme 资源包选择界面：勾选待转换资源包，并保护已选中的资源包](https://raw.githubusercontent.com/EmbersDrift/Enzyme/main/docs/images/pack-selection.png)

### 独立转换设置

按需调整资源补全、着色器、透明图层与覆盖层选项，也可以切换预设。

![Enzyme 转换设置：缺失资源生成、着色器适配、透明图层修复和覆盖层合成](https://raw.githubusercontent.com/EmbersDrift/Enzyme/main/docs/images/conversion-settings.png)

### 不兼容资源包的转换入口

在原版加载确认框中，点击“转换资源包”进入转换页面。

![Enzyme 在原版不兼容资源包确认框中提供转换资源包按钮](https://raw.githubusercontent.com/EmbersDrift/Enzyme/main/docs/images/incompatible-prompt.png)

### 查看转换结果

完成后查看转换状态和原包归档情况，再手动启用输出包。

![Enzyme 转换结果：转换成功、原包已归档，新资源包尚未启用](https://raw.githubusercontent.com/EmbersDrift/Enzyme/main/docs/images/conversion-result.png)

## 支持的 Minecraft 版本

| Minecraft | Fabric | NeoForge | Java |
| --- | --- | --- | --- |
| 1.18.1 | 支持 | — | 17 |
| 1.20.1 | 支持 | 支持 | 17 |
| 1.20.6 | 支持 | 支持 | 21 |
| 1.21.8 | 支持 | 支持 | 21 |
| 1.21.10 | 支持 | 支持 | 21 |
| 1.21.11 | 支持 | 支持 | 21 |
| 26.2 | 支持 | 支持 | 25 或更新 |

这是一个**客户端 MOD**，无需在服务器安装。当前发行文件内置的转换执行器支持 **Windows x64**；Linux、macOS 和 ARM64 用户需要自行构建对应执行器。

## 安装

1. 安装与你的 Minecraft 版本对应的 Fabric 或 NeoForge。
2. Fabric 用户还需安装对应游戏版本的 **Fabric API**；NeoForge 用户无需安装 Fabric API。
3. 下载匹配游戏版本与加载器的 Enzyme JAR，放入游戏实例的 `mods` 文件夹。

每个实例只安装一个 Enzyme JAR。不要混装 Fabric、NeoForge 或不同 Minecraft 版本的文件。Minecraft 1.20.1 的 NeoForge 构建使用早期 Forge 兼容发行版；具体加载器版本见下载文件的版本说明。

## 使用

1. 打开“选项 → 资源包”，点击“检查资源包版本”。
2. 勾选需要转换的未启用资源包，按需打开“转换设置”或预览转换任务。
3. 开始转换，完成后查看报告，再手动启用转换后的资源包。

将不兼容资源包移至“已选”时，也可以点击确认框中的“转换资源包”，进入转换页面。

默认开启缺失材质生成、着色器适配、透明图层修复和覆盖层合成，删除旧资源默认关闭。无警告时原包归档到 `resourcepacks/Old Pack/`；有警告时原包保留，方便检查与对比。

## 兼容性说明

支持 ZIP、文件夹资源包及实验性的 MCPACK 转换。输出针对当前运行的 Minecraft 资源格式，转换目标会与游戏版本核对。

OptiFine/CIT 规则会保留，但其特殊外观需要兼容的客户端扩展。自定义模型、着色器及第三方 MOD 组合仍可能需要手动检查；资源包成功加载并不代表所有扩展效果都能在原版客户端显示。

## 转换核心与致谢

Enzyme MOD 由 **EmbersDrift** 制作与维护。

资源包转换核心及模板来自 [realLivedInCorner / 2-Pyramid](https://github.com/realLivedInCorner/2-Pyramid)，保留 2-Pyramid Studio 的版权与 MIT 许可声明。MOD 图标取自 KyanitePackTool 桌面应用，保留其原有来源与许可说明。详细来源见 [NOTICE](https://github.com/EmbersDrift/Enzyme/blob/main/NOTICE.md)。

Enzyme 项目采用 [MIT 许可证](https://github.com/EmbersDrift/Enzyme/blob/main/LICENSE)。

[源代码](https://github.com/EmbersDrift/Enzyme) · [反馈问题](https://github.com/EmbersDrift/Enzyme/issues)
