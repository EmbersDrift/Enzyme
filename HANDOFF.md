# 开发交接：MOD 0.4.0 / 核心 0.2.2

本版仅调整 Java MOD、打包工具、验证及文档。Git 基线提交为 `46bcee4`，项目以 Enzyme 名称发布到 GitHub，许可为 MIT。上游转换器不自动同步或推送。`tools/upstream/core-baseline.json` 冻结 207 个核心文件；`verifyCoreBaseline` 同时检查文件集合与 SHA256。既有本地核心补丁清单见 [LOCAL-PATCHES.md](tools/upstream/LOCAL-PATCHES.md)。后续与作者并线时先审查该清单，再决定是否调整基线；不能用刷新哈希掩盖意外修改。

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
| `pyramid-bridge/src/runtime.rs` / `overlays.rs` | 请求选项、任务事件、取消、图像预算与源覆盖层合成 |
| `tools/core` / `tools/game` | 实际执行器检查与隔离游戏交互审计，均不进入生产 JAR |
| `compat/versions.json` / `compat/generate.py` | 五个旧版 Fabric 的目标格式与 Minecraft API 适配；共用主目录 Java 业务代码，生成文件只写入 build |
| `compat/build_all.py` / `compat/production_audit.py` | 逐版本编译、执行器升降级与 JAR 检查、正式 Fabric 启动审计及交付记录 |

## 五个版本的兼容层

新增 1.18.1 / 1.20.1（Java 17）和 1.20.6 / 1.21.8 / 1.21.11（Java 21），资源包目标格式分别为 8 / 15 / 32 / 64 / 75，均调用同一份 0.2.2 执行器。这些格式已存在于冻结核心的能力列表，没有新增转换规则。

26.2 的源文件仍在 `src/client/java`。`compat/generate.py` 适配 UI API、输入签名、Toast、文本、资源包标识与 Mixin；每次生成验证核心文件集合及哈希。请修改生成器或公共源文件，不直接修改 `build/compat-generated`。1.18.1 的 Fabric API mod ID 是 `fabric`，其余版本为 `fabric-api`。旧版背景使用兼容绘制，不包含 26.2 的渲染提取与淡入 Mixin。

兼容构建采用 `net.fabricmc.fabric-loom-remap` 和官方映射，交付的是 intermediary JAR。开发环境审计之后，还用最终 JAR 启动生产 Fabric Knot，独立探针仅负责鼠标操作检查；探针和审计类不得进入交付 JAR。`build/compat-delivery/verification.json` 记录最终 SHA256、Java、目标格式、开发和生产审计路径。复用生产验证前必须核对最终 JAR 的 SHA256；路径中有旧 PASS 文件不足以证明改过的 JAR 正常。

完整命令与 JDK 配置见 [compat/README.md](compat/README.md)。本地审计实例均位于 build，不修改 PCL 实例。CI 只跑编译和执行器检查，不把没有运行的游戏审计标为通过。

## 线程与文件约束

原版 PackSelectionModel、selected IDs、options.resourcePacks 与 ResourceManager 都在客户端线程读取。扫描、签名、转换、归档和恢复在一个 worker 上串行执行；历史恢复校验和哈希同样在 worker 进行。Rust `convert` 通过进程内互斥串行化，因为上游统计器和报告仍有全局状态；每个请求单独建立 1–4 线程 Rayon pool，结束后重置上下文。

保护流程：检测前后签名一致 → 转换前未使用且签名相同 → 只转换副本 → ZIP/格式校验 → 先写安装意图和输出签名再发布 → 无警告才再次检查原包未使用、签名相同 → 写归档目标意图 → 无覆盖移动 → 写归档结果。显式归档同样检查原包；恢复只允许 Old Pack 下签名相同的本地原包，重名避让。

历史仅识别中断状态，不在启动时自动归档或删除。安装路径必须与记录的输出签名一致才成为可操作输出；归档前日志记录目标路径，可识别移动已完成但结果未写入的情况。日志不是数据库事务，不保证断电后写入已刷到物理介质。不要据此省略源文件签名或目录验证。

`archive_failed` 表示输出已安装但归档未完成；批次计数分别统计转换和归档，不把有效输出算作转换失败。历史重试只接受无输出的 failed/cancelled/interrupted 记录，使用日志原选项；检查原包根目录、符号链接、签名与应用状态，再通过原有转换流程执行。诊断导出及报告读取也在 worker 上进行，界面与环境版本信息仍在客户端线程取得。

Java 暂存 ZIP 不做压缩，减少原生解压前的重复压缩；最终输出仍由原生打包。Rust 解压失败和取消都会等待写入线程退出。贴图写入队列容量 2，贴图缓存有预算；`commit_all` 传播异步写入和 panic。图像预算仅为准入估算，不能宣称硬 RSS 限制。

0.2.1 修复全包像素累计引发的预算误拒绝：按最大单图 × 每像素16字节 × 并发数，加四分之一预算的缓存预留估算峰值。先降低实际转换线程池大小，再判断单线程是否超预算。输入和输出检查写入 `image_budget_checks`，记录图像数量、最大图路径与尺寸、预算、峰值、请求/有效线程数和是否通过。任务的 `worker_threads` 记录实际 Rayon pool 大小。不要重新把全包所有图片当成同时驻留的工作集。

0.2.2 的图像准入先按文件内容识别编码，不能只信 `.png` 扩展名。BMP/JPEG 伪装成 PNG 时，全部图片通过预算和像素检查后，才在暂存副本中逐张解码并转为 PNG，解码器限制为预算的四分之一。潜在动画或多图格式不自动压成单帧。损坏图片仍失败。`repairs` 记录已修复项目的 code/path/detail，不据此产生待检查；仅在最终元数据写入并确认目标版本后，记录 `format_range_rewritten`。其余结构、覆盖层与混合分辨率警告继续保留。降级到旧格式时也必须清除旧 supported/min/max 范围，避免遗留互相冲突的版本声明。

## 协议和迁移

结果保持 schema v1，状态新增 `cancelled`，任务新增 `skipped`。跳过任务不计作已执行任务。报告包含源/目标路径、迁移路径、任务、警告、文件变化和 `archive_validated`；后者仅代表 ZIP 完整读取/CRC，Java 继续检查游戏目标格式。

`progress.jsonl` 每行一个事件：`stage`、`task`、`completed`、`total`；任务数包括显式跳过的任务，代表调度进度而非耗时比例。Java 每 100 ms 读取事件；取消先创建文件供核心协作退出，3 秒后仍未退出则终止子进程。发布输出是提交点，已发布或已完成的任务不回滚，原包仍受保护。

覆盖层合成根据 `pack_format` 或 `min_format` 选择源版本，支持旧格式范围和现代 major/minor 范围。后面的有效层覆盖前面，合成后移除覆盖层目录与声明，再跑原迁移管线。重复/嵌套覆盖层目录、非法路径与倒序范围拒绝。输出只面向一个版本，多版本语义保留在原包中。

默认保守选项跳过 `generate_*`、`reverse_generate_*`、大部分 `delete_*` 和 shader 适配。`delete_font_folder` 实际是本地字体保留/legacy_unicode 兼容任务，不能误跳过。启用删除后仍报告模型删除；默认保留模型或 shader 时提示检查。上游的正常路径改名、GUI 切分和必需格式调整继续执行。

CLI 能力列表只声明已知迁移节点；非零 minor 仅接受固定快照支持的 97.1。26.2 目标为 88.0。新增游戏格式必须更新迁移规则和检查，不能只改能力列表。

## 构建与同步

`package.py` 支持 `CARGO_TARGET_DIR`、`CARGO_HOME`、`--platform`、`--binary`、`--resources-dir`、`--capabilities`。默认检查实际二进制头并执行能力查询；无法在本机运行的二进制必须显式提供能力 JSON。每个平台生成 runner.json，记录上游提交、核心基线、执行器和模板哈希；UImage ZIP 使用固定时间戳。

CI 在 Windows/Linux/macOS 的 x86_64/aarch64 原生运行器构建六个平台，合并前验证执行器与模板哈希及一致基线，最后在 Windows x64 执行 MOD 集成检查。生产检查还验证最终 JAR 中的执行器、模板、清单及翻译。当前 Windows 交付包没有其他平台执行器；CI 配置不代表远端构建已运行。

`pyramid_core` 的 Request 可指定 `templates_dir`，便于朋友的桌面端调用同一核心；当前仓库只完成库边界，没有修改朋友的远端项目，也没有自动同步它。旧桌面配置、更新器和网络下载逻辑不属于本库。

原 Embers Client 未修改；独立项目与集成版需要分别审查、移植。审计完成后必须运行生产构建，并检查 JAR 不含 PackStandaloneAudit 或审计初始化调用。
