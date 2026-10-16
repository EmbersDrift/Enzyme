# Fabric 1.21.10 支持

2026-10-16，Enzyme MOD 0.4.0。新增版本仅在 MOD 构建层配置，转换核心仍为冻结的 0.2.2，未调整 Rust、模板、执行器或核心能力列表。

## 安装

- Minecraft Java Edition **1.21.10**，Windows x64，游戏使用 **Java 21**。
- 本次验证组合：Fabric Loader **0.19.5**、Fabric API **0.138.4+1.21.10**。
- 将 `build/compat-delivery/enzyme-1.21.10-0.4.0.jar` 放入该实例的 `mods`，移除旧的 Enzyme JAR 后重启。每个实例只安装对应版本的一个 JAR。
- 本次为本地交付，GitHub 上已有的 v0.4.0 六版本 Release 尚未更新。

## 版本依据与适配

[Mojang 官方发布元数据](https://piston-meta.mojang.com/v1/packages/b14d03617088d907aef01138b042e8e5c1ab35d4/1.21.10.json) 声明 Java 21；该客户端下载包中的 `version.json` 声明资源包格式 **69.0**。[Fabric 官方 Maven 元数据](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml) 提供对应的 API 版本。

`compat/versions.json` 新增该版本，继续由 `compat/generate.py` 从共享 Java 代码生成适配层。1.21.10 使用 `KeyEvent` / `MouseButtonEvent`、矩阵姿态栈、原版 `Button` 和 `net.minecraft.Util`，与 1.21.11 的 `Button.Plain` / `net.minecraft.util.Util` 有区别。JAR 仅声明适用于 1.21.10。

格式 69 已存在于执行器能力清单。转换完成后，MOD 和原版 Minecraft 资源包仓库分别校验输出兼容性；转换结果不自动启用。

## 验证与复测

```powershell
python -X utf8 compat/build_all.py --versions 1.21.10 --production-audit
```

构建包含真实执行器的升降级、控制字符元数据修复、原包字节保护、ZIP/CRC、历史、归档和诊断检查。正式客户端使用最终 remap JAR、真实 Java 21 与官方 Fabric 启动流程，独立探针操作界面，覆盖转换设置、预览、转换、结果、取消、历史，以及不兼容包确认框的转换入口和数据包界面排除。另检查六个页面标题截图的清晰度。

`build/compat-delivery/verification.json` 记录该次命令的版本与结果，`build/compat-production-runs/1.21.10/verification.json` 绑定实测 JAR 的 SHA256。审计实例、探针和日志只保存在 build，不写现有游戏实例，探针不进入正式 JAR。

本次构建、原生回归、正式 JAR 客户端操作及六页面清晰度检查均通过；207 个核心文件哈希一致。源码、HEAD 对应历史及该 JAR 的上传扫描未发现被拦截的敏感内容。未另跑开发客户端审计，验证记录中的该项保持 false。

本次 JAR SHA256：`4eb482908ea56155513ae639fcdb27701d10311646453a7515d3e6f3eef97774`。

自动检查使用合成资源包。实际材质效果和其他 MOD 共存请按 [TESTING.md](TESTING.md) 复测，重点观察原版 UI、转换设置默认值和不兼容包的转换入口。
