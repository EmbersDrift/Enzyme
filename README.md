# Enzyme

在 Minecraft 原版资源包界面中检查版本、预览转换任务并批量转换资源包，支持 Fabric 和 NeoForge。转换核心来自 [2-Pyramid](https://github.com/realLivedInCorner/2-Pyramid)，项目采用与上游一致的 [MIT 许可证](LICENSE)。

## 下载与安装

从 [Releases](https://github.com/EmbersDrift/Enzyme/releases) 下载已发布版本；最新代码的编译产物见 [Actions](https://github.com/EmbersDrift/Enzyme/actions)。当前源码版本为 **0.5.1**。

| Minecraft | Fabric | NeoForge | Java | 资源包目标格式 |
| --- | --- | --- | --- | --- |
| 1.18.1 | 支持 | — | 17 | 8.0 |
| 1.20.1 | 支持 | 47.1.106 | 17 | 15.0 |
| 1.20.6 | 支持 | 20.6.144 | 21 | 32.0 |
| 1.21.8 | 支持 | 21.8.57 | 21 | 64.0 |
| 1.21.10 | 支持 | 21.10.64 | 21 | 69.0 |
| 1.21.11 | 支持 | 21.11.48 | 21 | 75.0 |
| 26.2 | 支持 | 26.2.0.93 | 25 或更新 | 88.0 |

每个实例安装一个与游戏版本和加载器匹配的 JAR：

- Fabric：`enzyme-游戏版本-0.5.1.jar`，另需对应版本的 **Fabric API**。测试使用 Fabric Loader 0.19.5；1.18.1 请安装 [官方完整 API 包](https://github.com/FabricMC/fabric-api/releases/tag/0.46.6%2B1.18)。
- NeoForge：`enzyme-neoforge-游戏版本-0.5.1.jar`，无需 Fabric API。1.20.1 使用 NeoForge 早期 Forge 兼容发行版；1.18.1 没有 NeoForge 版本。

目前内置 **Windows x64** 转换执行器。其他系统和架构需要另行构建执行器。不要安装 sources JAR 或测试探针。

## 使用

1. 打开“选项 → 资源包”，点击“检查资源包版本”。首次打开时也会自动检查。
2. 勾选未启用的资源包，可先预览转换任务，再开始转换。
3. 查看结果报告，手动将转换后的资源包移至“已选”，应用后检查效果。

将不兼容资源包从“可用”移至“已选”时，原版确认框也提供“转换资源包”入口。该入口打开转换页面并勾选当前资源包。

独立的“转换设置”按钮提供默认、保守、完整预设。默认开启缺失材质生成、着色器适配、透明图层修复和覆盖层合成，删除旧资源默认关闭。已保存的设置继续生效；需要新默认值时，点击“恢复默认”并保存。

转换在副本上执行，输出通过 ZIP/CRC 和目标格式检查后安装。无警告时原包归档到 `resourcepacks/Old Pack/`；有警告时保留原包供检查。已应用和待应用的资源包受到保护。转换历史支持报告详情、失败重试、诊断导出及归档恢复。

## 转换兼容性

目标格式从运行中的 Minecraft 读取，并与 JAR 内声明核对。目标不一致时停用转换。输出面向当前游戏的单一目标格式，文件名可能显示共享该格式的版本范围。

MOD 适配层补充了旧版物品谓词到新版物品定义的迁移、着色器接口适配，以及自定义模型纹理的图集注册。修复项目和需要检查的内容均写入报告。不能自动迁移的模型谓词保留原文件并提示检查。

OptiFine/CIT 规则会保留，但相应外观需要兼容的客户端扩展。资源包可以成功加载，也可能仍有依赖特定 MOD 的效果；报告会明确提示。模型、着色器及第三方 MOD 的最终视觉效果仍需实机确认。MCPACK 转换为实验性功能。

输入上限为 100,000 个文件、500 MiB，单图上限为 16,777,216 像素。默认使用 2 个工作线程和 256 MiB 图像预算；预算用于图像缓存和任务准入估算，并非进程总内存硬限制。

## 构建与测试

需要 Python 3.12+、JDK 17/21/26，以及首次构建时的网络连接。

```powershell
# JAVA_HOME 指向 JDK 26；另设置 PACKMOD_JAVA17_HOME、PACKMOD_JAVA21_HOME
.\gradlew.bat build --console=plain
python -X utf8 compat/build_all.py --production-audit
python -X utf8 neoforge/build_all.py --production-audit
```

主构建生成 Fabric 26.2；其余 Fabric 和 NeoForge 产物分别收集到 `build/compat-delivery/` 和 `build/neoforge-delivery/`。客户端测试需要桌面及显卡，会启动隔离的游戏实例。

使用自己的 ZIP 资源包运行全部版本的三种预设检查，并在客户端启用转换结果：

```powershell
python -X utf8 tools/regressions/test_real_pack.py 'D:\测试资源包.zip' --clients
```

测试检查目标格式、源包保持不变、ZIP CRC、PNG 解码、音频保留、物品定义迁移，并验证客户端资源重载和界面操作。更多说明见 [TESTING.md](TESTING.md)、[Fabric 适配说明](compat/README.md) 和 [NeoForge 说明](neoforge/README.md)。

## 来源与维护

转换核心 **0.3.0** 基于 2-Pyramid **2.200.0**，固定提交 `812a7358536c28772cfda3541a83e83f9a2d8825`。183 个核心文件通过文件集合及 SHA-256 校验冻结；本次资源包兼容修复位于 Java MOD 层。上游差异记录见 [LOCAL-PATCHES.md](tools/upstream/LOCAL-PATCHES.md)，维护接口见 [HANDOFF.md](HANDOFF.md)。

MOD 图标取自用户指定的 KyanitePackTool 桌面应用，来源记录见 [desktop-icon.json](tools/upstream/desktop-icon.json)。上游及第三方版权与许可见 [NOTICE.md](NOTICE.md)。

遇到问题请在 [Issues](https://github.com/EmbersDrift/Enzyme/issues) 提供游戏版本、加载器、复现步骤及转换报告。
