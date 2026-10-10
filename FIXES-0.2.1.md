# 0.2.1 转换失败修复

问题来自 `convert-12185933210367561924`。原始失败报告在进入迁移任务前，以 `Estimated decoded image working set exceeds the selected budget` 拒绝转换。

修复三个问题：

- 图像预算曾将全包 4,159 张 PNG 的像素工作集累计为约 951 MiB。现按最大单图、实际转换并发数和缓存预留估算峰值；必要时先降并发。默认 2 线程、256 MiB 即可处理这个包，预算仍不是系统内存硬限制。
- 修正预算后发现，548×138 的整张标题图被按旧版方形图集裁切，导致生成零高度 `realms.png`。现在仅对符合旧布局的方形图集执行拆分，其他标题布局保持原样。
- 包中同时保留旧 GUI 图集和已适配的 GUI sprites。现在已有 sprites 优先，旧图集只补缺失文件，避免覆盖作者的新版本贴图。

报告增加 `image_budget_checks`，含最大图路径、尺寸、预估峰值及有效线程数；任务记录 `worker_threads`，便于确认实际转换线程池大小。

验证使用原包的工作区副本，不修改游戏实例中的原包及历史报告。Rust 共 152 项测试通过，覆盖并发预算、单图超限、标题布局和现代 sprite 保留。Gradle `build` 及 Java 调用内嵌原生程序的完整回归通过，包含预算降并发、取消、历史、归档恢复与报告校验。实际原包副本经 CLI 和 Java 两条链路均成功转换为格式 88.0；ZIP 完整性通过，224 个既有 GUI/标题/天空资源逐字节保留，原包 SHA-256 不变。

输出仍标记 `needs_review`：源包声明多版本范围，以及物品/方块/GUI 混合分辨率。目标元数据已改写为 88.0；混合分辨率下 GUI 对齐仍需进入游戏观察。自动化验证不保证所有 OptiFine 功能在 Fabric 中生效。

除默认 `build` 检查外，可用自有 ZIP 运行实际 Java 转换链路回归，不将素材提交进源码：

```powershell
.\gradlew.bat build '-PconversionRegressionPack=D:\path\to\pack.zip'
```

此可选检查同时验证目标格式、源文件保留，以及已有 GUI sprites、标题和 OptiFine 天空文件的字节一致性。
