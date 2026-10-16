# 并线基线与集成差异

## 当前：MOD 0.4.1 / 核心 0.3.0

2026-10-16 按用户明确要求升级至 [2-Pyramid 2.200.0](https://github.com/realLivedInCorner/2-Pyramid/tree/812a7358536c28772cfda3541a83e83f9a2d8825)，固定提交 `812a7358536c28772cfda3541a83e83f9a2d8825`，MIT。协议仍为 schema v1。旧 hurray/converters 被上游 A-ROM、natives、pack、bedrock_convert 替换，未引入桌面 UI、更新器、AI 或网络客户端。

`core-baseline.json` 新冻结 183 个文件；`import-manifest.json` 记录 172 个上游文件的原路径、原 SHA256、集成 SHA256 和修改标记，其中 161 个逐字保留，11 个有集成适配。模板与上游许可逐字匹配当前提交。此次有意替换并重新建立基线，不能描述为“0.2.2 核心未改”。

| 模块 | 保留或迁移的集成差异 |
| --- | --- |
| lib/main/runtime/reporting/integration | JSON 请求、能力/预览、取消、进度、报告、请求级互斥、原包保护和选项 |
| native_run | 接入任务策略、取消与报告，保留上游任务顺序和并行分桶；直接 GUI 步骤也记录为任务 |
| pack/io | 原有严格 ZIP 路径、重复条目、CRC、数量、500 MiB、取消和同步清理 |
| pack/version_converter、overlays | A-ROM 读取经过根目录规整、图像修复和覆盖层合成的暂存树；降级清除旧范围；输出仍由原版确认兼容 |
| pack/analysis | 已解决的版本范围提示记为 repair，其他结构警告保留 |
| arom/view、arom/layer | 解码缓存按预算淘汰；编码 blob 另以同一预算数值限容，仍不承诺进程 RSS 硬限制 |
| arom/serialize | ZIP 条目间检查取消 |
| image_utils | 从请求解析 UImage，生产程序不含开发目录回退，不读取/创建桌面用户配置 |
| natives/eraser/drop_font | 保留各命名空间字体，仅替换 legacy_unicode provider |
| natives/surgeon/gui_surgeon_tx | 自定义/现代标题保护、源包已有 GUI 精灵优先 |
| natives/surgeon/shader_adapt | 使用请求目标格式，避免误读尚未改写的源元数据 |
| alpha_layers | 沿用旧上游像素规则、改为 Tx 读写并接入实际计划；此前只有注册、没有计划边的开关不会执行 |

`invoke_conversion.rs` 仅以 `cfg(test)` 提供上游单元测试所需元数据壳；生产只走 A-ROM。Java 继续管理原版 UI、默认设置、历史、脱敏、选择保护和安装/归档事务。未修改上游远端或 Embers Client，未自动发布 GitHub。

旧核心完整备份位于忽略的 `build/core-upgrade-0.2.2-backup`，旧测试 JAR 保留。后续同步应先审阅此表和 import-manifest，再跑 Rust/真实执行器回归，明确升级后才建立新基线；不能仅刷新哈希掩盖意外修改。验证使用 `gradlew verifyCoreBaseline verifyNativeBundle`，重编使用 `python tools/release/build_native.py`。

## 历史：0.2.2 基线及原有差异

上游提交：`503a0859e3ab6bf09ed03f361b9335e5931a6d6d`，仓库 realLivedInCorner/2-Pyramid。
冻结版本：核心与 CLI 0.2.2，协议 schema v1。MOD 从 0.3.0 起独立编号。
本地 Git 基线 `46bcee4` 包含既有修改，并非未经修改的上游源码；它保留在本地，不属于 GitHub 的干净发布历史。公开冻结依据为 `core-baseline.json`，记录 207 个 Rust 源码、测试、Cargo 配置、模板与上游许可证文件的 SHA-256。构建检查文件集合和内容，新增、删除或修改均失败。

既有差异供作者审查：

| 模块 | 已有差异 |
| --- | --- |
| lib、main | 独立库、JSON 请求／报告、能力查询、预览；排除桌面配置和更新器 |
| runtime、reporting、scheduler | 保守选项、取消、进度、图像预算、任务记录、修复记录 |
| zip、version_converter、overlays | 严格解压、取消收尾、覆盖层合成、目标元数据、错误传播 |
| hurray context／texture | 缓存、队列、异步写入失败传播 |
| GUI surgeon、字体等迁移模块 | 现代标题识别、已有 sprites 优先、字体保留 |
| 0.2.1 | 并发预算、降低实际线程数、零高度标题裁切修复 |
| 0.2.2 | BMP/JPEG 错命名 PNG 修复、版本范围误报处理 |

细节见根目录 `FIXES-0.2.1.md`、`FIXES-0.2.2.md`。并线时逐项审阅，核心冻结不代表与上游完全一致。明确决定升级共享核心时，才更新冻结清单。

后续 MOD 通过现有 CLI 接口对接核心，保留原始警告和未知代码。中文展示、历史、诊断导出属于 Java；打包脚本和流水线属于集成层。拥有本地旧基线时可用 `git diff 46bcee4 -- src tools build.gradle .github pyramid-bridge/package*.py` 审阅外层；GitHub 克隆以当前发布历史及冻结清单为准。验证冻结：`gradlew verifyCoreBaseline`。

首次 GitHub 发布重新编译原生程序，通过编译器路径映射去除构建者的用户目录与工作区路径。仅二进制和 runner.json 的构建哈希变化；转换逻辑、207 个冻结文件、协议、模板与依赖均不变。可用 `python tools/release/build_native.py` 重现该路径处理。

基线提交使用 `Pack Mod Baseline <packmod@localhost>` 标识。基线建立时未配置远端。后续以 Enzyme 名称发布项目源码和 MOD，不上传用户素材。
