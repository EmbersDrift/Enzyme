# 开发交接：MOD 0.5.1 / 核心 0.3.0

0.5.1 使用实际 1.20.1 资源包进行全版本回归。`PackCompatibility` 在核心输出安装前补充新版物品定义及纹理图集；`PackShaderCompatibility` 修复新版 GLSL 导入、统一变量块及雾效接口。所有改动位于 MOD 层，183 个冻结核心文件保持不变。新增警告/修复会合入持久结果与界面报告。

本次测试入口为 `tools/regressions/test_real_pack.py`，三预设文件检查加 `--clients` 正式客户端启用检查；26.2 新增独立生产探针 `tools/game/production-probe.init.gradle`，不再只依赖开发客户端证据。测试细节见 [TESTING.md](TESTING.md)。以下为此前版本记录，旧交付包与证据仍保留。

0.5.1 最终本地结果：13 个成品的 39 次真实资源包转换、13 次正式客户端资源重载和 UI 检查通过。独立检查 1,488 个实际模型谓词案例、2,808 次自定义命名空间文件字节保留；报告计数按最终 SHA-256 差异去重。全部源码修复在 Java 层，核心二进制 SHA-256 保持 `d95ec2499a312568ee2b9f3c558f491795ab50d0861f39a631fa41995dce051a`。汇总在 `build/real-pack-audit/summary.json`，最终打包目录为 `build/github-release/0.5.1`。

按用户最新指定，MOD 图标使用 `D:\KyanitePackTool-Stable-Edition1.4.0\KyanitePackTool.exe` 内的蓝色 K 图标，替换先前上游仓库中的线框图标。读取 EXE 的 Windows 图标资源组 1 / 图像资源 7，直接提取原始 256×256 PNG，没有启动该程序，也没有缩放或改色。图标为 `src/client/resources/assets/packmod/icon.png`，来源和哈希在 `tools/upstream/desktop-icon.json`，NOTICE 已同步来源。

蓝色 K 图标版的 13 个 JAR 交付于 `build/kyanite-icon-delivery/0.5.0/enzyme-0.5.0-kyanite-icon-all-versions.zip`，图标和构建核对证据见同目录 `icon-validation.json`。此前线框图标包保留在 `build/icon-delivery/0.5.0`。本次只修改图标和来源文字，不重跑客户端 UI 审计，不覆盖旧生产审计的 JAR 哈希，也不替换 `build/github-release/0.5.0` 的旧交付。后续正式打包须为新 JAR 更新对应审计证据，不能沿用旧哈希。

2026-10-16 新增六版本 NeoForge 适配，配置和构建入口在 `neoforge/`，维护边界见 [NeoForge 说明](neoforge/README.md)。与现有七版本 Fabric 共用 Java 业务层及原生执行器，本次未修改 183 个冻结核心文件。1.20.1 使用官方早期 NeoForge、legacy ModDevGradle、SRG 重映射及 refmap；1.18.1 仅支持 Fabric。不要交付 devlibs 或测试探针。

公共 `ConversionTarget` 从实际运行的 Minecraft 读取资源格式，核对 JAR 内 `assets/packmod/conversion-target.json` 的 Minecraft、major、minor 及实际选择目标；不一致时停止注册转换入口并记录错误。`tools/release/verify_targets.py` 另用官方客户端 SHA1 和内置 `version.json` 核对全部配置。输出文件名可能使用格式族的版本范围，不能据此认定目标串版。

0.5.0 本地打包入口为 `tools/release/prepare_release.py`，汇总 7 个 Fabric 和 6 个 NeoForge 成品及经过路径过滤的验证记录。NeoForge 客户端使用官方安装器建立 build 内的隔离实例，加载成品与独立探针，并检查成品代码实际来源。运行时目标证据为审计目录内的 `conversion-target-check.json`。本次未自动推送或更新 GitHub Release。以下保留此前核心升级和 UI 修复记录。

2026-10-16 按用户明确要求升级上游至 2-Pyramid 2.200.0、提交 `812a7358536c28772cfda3541a83e83f9a2d8825`。使用 A-ROM/natives 新引擎，移除旧 hurray/converters 树；原有 MOD 选项、取消、进度、严格 ZIP、预算、字体/标题/精灵保护和报告已迁移。详见 [LOCAL-PATCHES.md](tools/upstream/LOCAL-PATCHES.md) 及逐文件 [import-manifest.json](tools/upstream/import-manifest.json)。本次有意升级核心并建立 183 文件的新基线，后续仍禁止无审查刷新哈希。

0.4.1 本地验证：175 项 Rust 测试通过、6 项外部样本测试跳过；原客户包七目标保守模式像素/CRC/源保护回归通过；六个兼容版本完成最终 JAR 的正式客户端全流程与清晰度检查，26.2 完成隔离客户端及最终 JAR 检查。新交付目录为 `build/github-release/0.4.1`，旧 0.4.0 JAR 与核心备份保留。透明层修复现会实际进入计划，对照原像素应使用保守预设。

2026-10-16 新增 Fabric 1.21.10 独立构建：Java 21、资源包格式 69.0、Fabric API 0.138.4+1.21.10。共用现有 MOD 业务代码和冻结执行器，仅增加版本配置；保留该版本的原版 Button/Util API，同时启用新的输入事件适配。编译、原生升降级、正式 JAR 的完整客户端操作及六页面清晰度检查通过；细节见 [FABRIC-1.21.10.md](FABRIC-1.21.10.md)。含主构建 26.2 共支持七个 Minecraft 版本，已发布 v0.4.0 的六版本资产尚未更新。

2026-10-15 新增原版不兼容确认框的转换入口，见 [INCOMPATIBLE-PROMPT.md](INCOMPATIBLE-PROMPT.md)。保留原版 Yes/No/Esc，仅选择转换时通过 No 回调返回原资源包页面，再检测并勾选目标；不提前选择或启用原包。

2026-10-14 的原版 UI 与独立转换设置调整见 [VANILLA-UI.md](VANILLA-UI.md)。使用原版按钮及背景、停用 UI 淡入 Mixin；新默认开启生成、shader、透明层修复与覆盖层合成，保留已保存的配置。删除类任务仍需单独启用。

2026-10-11 客户反馈自查的未发布 MOD 变更见 [CUSTOMER-SELF-CHECK.md](CUSTOMER-SELF-CHECK.md)：元数据字符串控制字符仅在暂存副本转义；上游合金资源生成改变已有文件时增加待检查警告。核心保持冻结，本地测试 JAR 与已发布 v0.4.0 不同。

同日旧版 UI 的背景模糊修复见 [UI-BLUR-FIX.md](UI-BLUR-FIX.md)。兼容生成器覆盖各版本的原版背景入口，避免在面板/文字绘制后再次模糊；正式客户端审计增加真实截图的文字清晰度检查。

项目以 Enzyme 名称发布，许可为 MIT；本次 0.4.1 为本地交付，未自动推送或更新 GitHub Release。`tools/upstream/core-baseline.json` 冻结 183 个核心文件；`verifyCoreBaseline` 同时检查文件集合与 SHA256。Git 旧本地基线 `46bcee4` 及 0.2.2 的差异保留为历史记录。与作者并线时先审查当前来源清单和本地适配，再明确决定是否建立新基线。

## 模块职责

| 模块 | 职责 |
| --- | --- |
| `PackInspector` | 只读格式范围、源版本和包结构检测 |
| `PyramidConverter` | 暂存、能力查询、原生进程、任务事件、取消与输出发布 |
| `ConversionOptions` / `ConversionControl` | 持久配置与线程安全取消信号 |
| `ConversionReport` | schema v1 结果校验、警告和文件变化计数 |
| `PackConversionFiles` | SHA256 签名、归档路径保护和冲突避让 |
| `PackJobHistory` | 本地操作日志、安装/移动中断识别和原包恢复 |
| `PackConversionManager` | 单 worker、应用中包保护、后台结果、历史操作 |
| `PackConversionScreen` / `ConversionOptionsScreen` / `ConversionPlanScreen` / `PackHistoryScreen` | 检测、选项、任务预览和历史界面 |
| `NativePlatform` | 操作系统/CPU 到资源路径的映射 |
| `PackReportScreen` / `ReportText` | 滚动详情、警告解释与原始信息、选项摘要 |
| `PackDiagnostics` | 文本允许列表、脱敏、大小限制、诊断 ZIP 本地导出 |
| `package_support.py` / `tools/release` | 二进制架构识别、清单哈希与六平台产物合并验证 |
| `pyramid-bridge/src/lib.rs` | 可复用 `pyramid_core` API：`convert`、`plan`、`capabilities` |
| `pyramid-bridge/src/main.rs` | 薄 CLI，JSON 请求/结果、能力和预览查询 |
| `pyramid-bridge/src/runtime.rs` / `overlays.rs` / `integration.rs` | 请求选项、任务事件、取消、图像预算、源覆盖层合成与 A-ROM 桥接 |
| `tools/core` / `tools/game` | 实际执行器检查与隔离游戏交互审计，均不进入生产 JAR |
| `compat/versions.json` / `compat/generate.py` | 六个旧版 Fabric 的目标格式与 Minecraft API 适配；共用主目录 Java 业务代码，生成文件只写入 build |
| `compat/build_all.py` / `compat/production_audit.py` | 逐版本编译、执行器升降级与 JAR 检查、正式 Fabric 启动审计及交付记录 |

## 六个版本的兼容层

兼容 1.18.1 / 1.20.1（Java 17）和 1.20.6 / 1.21.8 / 1.21.10 / 1.21.11（Java 21），资源包目标格式分别为 8 / 15 / 32 / 64 / 69 / 75，均调用同一份 0.3.0 执行器。26.2 使用格式 88。新增 Minecraft 适配不单独修改核心转换规则。

26.2 的源文件仍在 `src/client/java`。`compat/generate.py` 适配 UI API、输入签名、Toast、文本、资源包标识与 Mixin；每次生成验证核心文件集合及哈希。请修改生成器或公共源文件，不直接修改 `build/compat-generated`。1.18.1 的 Fabric API mod ID 是 `fabric`，其余版本为 `fabric-api`。旧版背景使用兼容绘制，不包含 26.2 的渲染提取与淡入 Mixin。

兼容构建采用 `net.fabricmc.fabric-loom-remap` 和官方映射，交付的是 intermediary JAR。开发环境审计之后，还用最终 JAR 启动生产 Fabric Knot，独立探针仅负责鼠标操作检查；探针和审计类不得进入交付 JAR。`build/compat-delivery/verification.json` 记录最终 SHA256、Java、目标格式、开发和生产审计路径。复用生产验证前必须核对最终 JAR 的 SHA256；路径中有旧 PASS 文件不足以证明改过的 JAR 正常。

完整命令与 JDK 配置见 [compat/README.md](compat/README.md)。本地审计实例均位于 build，不修改 PCL 实例。CI 只跑编译和执行器检查，不把没有运行的游戏审计标为通过。

## 线程与文件约束

原版 PackSelectionModel、selected IDs、options.resourcePacks 与 ResourceManager 都在客户端线程读取。扫描、签名、转换、归档和恢复在一个 worker 上串行执行；历史恢复校验和哈希同样在 worker 进行。Rust `convert` 通过进程内互斥串行化，因为上游统计器和报告仍有全局状态；每个请求单独建立 1–4 线程 Rayon pool，结束后重置上下文。

保护流程：检测前后签名一致 → 转换前未使用且签名相同 → 只转换副本 → ZIP/格式校验 → 先写安装意图和输出签名再发布 → 无警告才再次检查原包未使用、签名相同 → 写归档目标意图 → 无覆盖移动 → 写归档结果。显式归档同样检查原包；恢复只允许 Old Pack 下签名相同的本地原包，重名避让。

历史仅识别中断状态，不在启动时自动归档或删除。安装路径必须与记录的输出签名一致才成为可操作输出；归档前日志记录目标路径，可识别移动已完成但结果未写入的情况。日志不是数据库事务，不保证断电后写入已刷到物理介质。不要据此省略源文件签名或目录验证。

`archive_failed` 表示输出已安装但归档未完成；批次计数分别统计转换和归档，不把有效输出算作转换失败。历史重试只接受无输出的 failed/cancelled/interrupted 记录，使用日志原选项；检查原包根目录、符号链接、签名与应用状态，再通过原有转换流程执行。诊断导出及报告读取也在 worker 上进行，界面与环境版本信息仍在客户端线程取得。

Java 暂存 ZIP 不做压缩。原生预检在拥有的解压目录规整根目录、合成覆盖层并修复伪装 PNG，A-ROM 随后从该目录读取。Tx 层在内存中提交，最终直接序列化 ZIP；输出图像准入读取 Pack 视图的编码头，不再落地整棵图像目录。解码缓存按预算淘汰，编码 blob 使用选定预算数值作为额外容量上限。解压失败和取消仍等待写入线程退出，任务和 ZIP 条目边界检查取消。预算不能宣称为硬 RSS 限制。

0.2.1 修复全包像素累计引发的预算误拒绝：按最大单图 × 每像素16字节 × 并发数，加四分之一预算的缓存预留估算峰值。先降低实际转换线程池大小，再判断单线程是否超预算。输入和输出检查写入 `image_budget_checks`，记录图像数量、最大图路径与尺寸、预算、峰值、请求/有效线程数和是否通过。任务的 `worker_threads` 记录实际 Rayon pool 大小。不要重新把全包所有图片当成同时驻留的工作集。

0.2.2 的图像准入先按文件内容识别编码，不能只信 `.png` 扩展名。BMP/JPEG 伪装成 PNG 时，全部图片通过预算和像素检查后，才在暂存副本中逐张解码并转为 PNG，解码器限制为预算的四分之一。潜在动画或多图格式不自动压成单帧。损坏图片仍失败。`repairs` 记录已修复项目的 code/path/detail，不据此产生待检查；仅在最终元数据写入并确认目标版本后，记录 `format_range_rewritten`。其余结构、覆盖层与混合分辨率警告继续保留。降级到旧格式时也必须清除旧 supported/min/max 范围，避免遗留互相冲突的版本声明。

## 协议和迁移

结果保持 schema v1，状态新增 `cancelled`，任务新增 `skipped`。跳过任务不计作已执行任务。报告包含源/目标路径、迁移路径、任务、警告、文件变化和 `archive_validated`；后者仅代表 ZIP 完整读取/CRC，Java 继续检查游戏目标格式。

`progress.jsonl` 每行一个事件：`stage`、`task`、`completed`、`total`；任务数包括显式跳过的任务，代表调度进度而非耗时比例。Java 每 100 ms 读取事件；取消先创建文件供核心协作退出，3 秒后仍未退出则终止子进程。发布输出是提交点，已发布或已完成的任务不回滚，原包仍受保护。

覆盖层合成根据 `pack_format` 或 `min_format` 选择源版本，支持旧格式范围和现代 major/minor 范围。后面的有效层覆盖前面，合成后移除覆盖层目录与声明，再跑原迁移管线。重复/嵌套覆盖层目录、非法路径与倒序范围拒绝。输出只面向一个版本，多版本语义保留在原包中。

新默认开启 `generate_*`、shader 适配、透明层修复和覆盖层合成；仍跳过 `reverse_generate_*`、大部分 `delete_*`。保守预设另关闭生成、shader 与透明层修复。`delete_font_folder` 实际是本地字体保留/legacy_unicode 兼容任务，不能误跳过。启用删除后仍报告模型删除；保留模型或 shader 时提示检查。生成改变已有合金资源时仍要求检查并保留原包。上游的正常路径改名、GUI 切分和必需格式调整继续执行。

CLI 能力列表只声明已知迁移节点；非零 minor 仅接受固定快照支持的 97.1。26.2 目标为 88.0。新增游戏格式必须更新迁移规则和检查，不能只改能力列表。

## 构建与同步

`package.py` 支持 `CARGO_TARGET_DIR`、`CARGO_HOME`、`--platform`、`--binary`、`--resources-dir`、`--capabilities`。默认检查实际二进制头并执行能力查询；无法在本机运行的二进制必须显式提供能力 JSON。每个平台生成 runner.json，记录上游提交、核心基线、执行器和模板哈希；UImage ZIP 使用固定时间戳。

CI 在 Windows/Linux/macOS 的 x86_64/aarch64 原生运行器构建六个平台，合并前验证执行器与模板哈希及一致基线，最后在 Windows x64 执行 MOD 集成检查。生产检查还验证最终 JAR 中的执行器、模板、清单及翻译。当前 Windows 交付包没有其他平台执行器；CI 配置不代表远端构建已运行。

`pyramid_core` 的 Request 可指定 `templates_dir`，便于朋友的桌面端调用同一核心；当前仓库只完成库边界，没有修改朋友的远端项目，也没有自动同步它。旧桌面配置、更新器和网络下载逻辑不属于本库。

原 Embers Client 未修改；独立项目与集成版需要分别审查、移植。审计完成后必须运行生产构建，并检查 JAR 不含 PackStandaloneAudit 或审计初始化调用。
