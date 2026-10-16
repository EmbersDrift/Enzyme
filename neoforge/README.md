# NeoForge 适配

Enzyme 0.5.1 为以下版本提供独立 NeoForge JAR。转换逻辑与 Fabric 共用，转换核心保持 0.3.0。

| Minecraft | 验证的 NeoForge | 游戏 Java | 目标资源格式 |
| --- | --- | --- | --- |
| 1.20.1 | 1.20.1-47.1.106 | 17 | 15.0 |
| 1.20.6 | 20.6.144 | 21 | 32.0 |
| 1.21.8 | 21.8.57 | 21 | 64.0 |
| 1.21.10 | 21.10.64 | 21 | 69.0 |
| 1.21.11 | 21.11.48 | 21 | 75.0 |
| 26.2 | 26.2.0.93 | 25 或更新 | 88.0 |

版本来源为 [NeoForge 官方 Maven](https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml)；1.20.1 使用 [官方早期 NeoForge 分支](https://maven.neoforged.net/releases/net/neoforged/forge/maven-metadata.xml)，仍保留 `net.minecraftforge` 包名。1.18.1 没有 NeoForge 版本，请使用 Fabric 版。

安装对应的 NeoForge 后，将 `enzyme-neoforge-<Minecraft>-0.5.1.jar` 放入实例的 `mods` 目录。不需要 Fabric API。每个实例只安装一个与游戏版本、加载器匹配的 Enzyme JAR。当前内置转换器面向 Windows x64。

## 构建

需要 Python 3.12+、Java 17/21/26 JDK；26.2 编译为 Java 25 字节码。可使用环境变量指定工具链：

```powershell
$env:PACKMOD_JAVA17_HOME = '你的 JDK 17 目录'
$env:PACKMOD_JAVA21_HOME = '你的 JDK 21 目录'
$env:PACKMOD_JAVA26_HOME = '你的 JDK 26 目录'
python -X utf8 neoforge/build_all.py --production-audit
```

只构建一个版本：

```powershell
python -X utf8 neoforge/build_all.py --versions 1.21.10 --production-audit
```

产物在 `build/neoforge-delivery/`。客户端检查会打开隔离游戏窗口，使用测试账户占位符和 build 下的实例，不读取玩家账户。仅构建时省略 `--production-audit`；已有证据可通过 `--reuse-production-audit` 复核，但必须与成品 JAR 的 SHA256 一致。

## 维护边界

- `generate.py` 从公共 Java 层生成 Minecraft API 适配，并替换加载器事件、配置目录及版本查询；独立目录避免影响 Fabric 构建。
- `platform/` 接入 NeoForge 的客户端 tick、关闭和界面初始化事件。
- 新版使用 ModDevGradle；1.20.1 使用其官方 legacy 插件、NeoForge 依赖、SRG 重映射和 Mixin refmap。发布 `build/libs` 下完成重映射的产物，不能发布 `devlibs`。
- `production_audit.py` 使用官方安装器和生产启动参数加载最终 JAR，验证实际加载来源、转换目标、原版不兼容提示、转换输出和六页 UI 清晰度。
- 测试探针是独立 MOD，审计字节码仅作为探针资源加载，不进入交付 JAR。不要安装 `enzyme-neoforge-probe-*`、`audit-*` 或 sources JAR。
- `tools/release/verify_targets.py` 校验 Mojang 客户端 SHA1，并读取其内置 `version.json`；公共 `ConversionTarget` 再核对实际运行版本及资源格式。格式相同的游戏版本共享转换算法，但构建与运行版本仍分别核对。
- 不修改 `pyramid-bridge`、UImage 或核心基线来解决加载器问题。183 个冻结文件必须保持原哈希。
