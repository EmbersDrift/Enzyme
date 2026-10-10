# 五版本 Fabric 兼容构建

兼容层共用主目录的 Java MOD 业务代码，只适配 Minecraft / Fabric API，不改冻结的 0.2.2 Rust 核心、UImage、依赖或原生程序。每次生成检查 207 个核心文件的集合与 SHA256。版本、Java、目标格式和 Fabric API 列表在 `versions.json`；26.2 继续使用主目录构建。

## 构建环境

Windows x64、Python 3.12 或更新版本、供 Gradle 使用的 JDK 25 或更新版本，以及 JDK 17 x64 和 JDK 21 x64。当前原生包内置 Windows x64 执行器。首次构建和生产审计需要联网下载官方 Minecraft、Fabric 依赖和游戏资源。

```powershell
$env:PACKMOD_JAVA17_HOME = '你的 JDK 17 目录'
$env:PACKMOD_JAVA21_HOME = '你的 JDK 21 目录'
python -X utf8 compat/build_all.py --audit --production-audit
```

`toolchains.py` 也能识别 `JAVA_HOME_17_X64` / `JAVA_HOME_21_X64`，以及本工作区 `build/compat-jdks` 中的已准备 JDK；验证实际版本与 x64 架构后才使用。1.18.1、1.20.1 使用 Java 17；其他三个使用 Java 21。

可用 `python -X utf8 compat/prepare_toolchains.py` 下载并核对 Azul 官方 SHA256，将 Java 17/21 x64 缓存到工作区以外的本机应用缓存；`toolchains.py` 会自动识别。不要将手动安装的 JDK 放在根 build 内。26.2 的 `clean` 会清理共享 build，完整验证时先完成主构建和审计，再跑五个兼容版本。

仅编译和执行器检查：`python -X utf8 compat/build_all.py`。只构建一个版本：`python -X utf8 compat/build_all.py --versions 1.20.1`。交付文件与报告在 `build/compat-delivery`，每次命令的报告只记录这次指定的版本。

五个版本的开发与生产审计全部通过后，运行 `python -X utf8 compat/package_delivery.py` 汇总为五版本 ZIP，附安装说明、实测清单和哈希记录；缺失版本或生产 JAR 哈希不一致时拒绝打包。

## 验证范围

- `build`：真实执行器从格式 1 升级、从格式 88 降级到各版本；核对输出格式、原包字节、自定义文件、ZIP/CRC、历史、归档、诊断；校验最终 JAR 的游戏依赖、Java 字节码、原生哈希、语言键与 Mixin 级别，拒绝审计代码泄漏。
- `--audit`：逐版本使用独立开发客户端和真实鼠标输入，验证界面、保护、转换与全部操作流程；原版 Minecraft 确认输出包兼容。记录 `build/compat-runs/<版本>/last-audit.txt`。
- `--production-audit`：用已编译的交付 JAR、官方 Fabric Loader profile、对应 Fabric API 和目标 Java，直接启动生产 Knot 客户端。独立探针重复同一界面审计，校验正式映射、Mixin 和运行时加载。记录 `build/compat-production-runs/<版本>/verification.json`，含实际测试 JAR 的 SHA256。
- `--reuse-audit --reuse-production-audit`：汇总已经通过的本地记录；生产记录的 JAR 哈希必须与本次交付一致，否则失败并要求重跑生产审计。开发记录复用仅适用于源代码未变化的整理步骤。

完整审计需要可用的桌面和显卡，会依次打开五个测试游戏窗口。所有配置、资源包副本、报告和截图都写入 build 下的独立目录，不写用户的 PCL 实例。`packmod-probe-*.jar` 仅存在于审计目录，不用于安装。审计生成器结束后恢复生产源码；发布脚本始终先 clean，清除编译残留，并在 JAR 任务中显式排除探针与审计类。

1.18.1 的旧 Maven Fabric API 文件只是模块声明；生产审计自动改用 [Fabric 官方完整发布包](https://github.com/FabricMC/fabric-api/releases/tag/0.46.6%2B1.18)，实机安装也须使用完整 Fabric API。启动 classpath 按 Fabric profile 优先合并，避免同时加入 Minecraft 自带的旧 ASM 版本。

`verification.json` 仅在所有指定版本构建/检查成功后写入；标为通过的生产审计须有对应 PASS 文件与相同 JAR 哈希。日志里的网络认证、离线 UUID 或资源包忽略信息不代表转换失败。合成包测试不能覆盖所有真实材质效果与第三方 MOD 组合，实机复测参见主目录 `TESTING.md`。

## 维护

改公共 Java 业务代码或此目录的适配器，再运行全部版本；生成的 `build/compat-generated/<版本>` 不是维护源。1.18.1 用 PoseStack 和旧文本/Tooltip，1.20.x 用 GuiGraphics，1.21.8 的姿态栈使用矩阵，1.21.11 用新的输入事件。旧版资源包 Entry、屏幕控件添加由独立 Accessor / Invoker Mixin 适配。

各 JAR 只声明一个 Minecraft 版本，内部目标格式分别为 8 / 15 / 32 / 64 / 75，不依靠更改核心能力列表提供支持。旧版采用兼容背景与圆角绘制；转换、报告与历史流程保持一致。与上游并线时保留此目录和 MOD 层，核心状态见 `tools/upstream/LOCAL-PATCHES.md`。
