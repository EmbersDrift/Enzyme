# 并线基线与既有差异

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
