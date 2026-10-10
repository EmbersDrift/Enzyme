# Enzyme

**A Fabric client MOD for inspecting and converting Minecraft resource packs.**

Enzyme 在原版资源包界面提供版本检测、转换预览、批量转换、报告和历史记录。转换使用 [2-Pyramid](https://github.com/realLivedInCorner/2-Pyramid) 的核心，操作在副本上进行，输出不会自动启用。项目采用 **MIT** 许可证，与源转换器一致。

## 下载与支持版本

从 [GitHub Releases](https://github.com/EmbersDrift/Enzyme/releases) 下载对应版本的 JAR，安装 Fabric Loader 和匹配游戏版本的 **Fabric API**。每个游戏实例只安装一个 Enzyme JAR。

| Minecraft | Release 文件 | 游戏 Java | 本次验证的 Fabric API |
| --- | --- | --- | --- |
| 1.18.1 | `enzyme-1.18.1-0.4.0.jar` | 17 | `0.46.6+1.18` |
| 1.20.1 | `enzyme-1.20.1-0.4.0.jar` | 17 | `0.92.12+1.20.1` |
| 1.20.6 | `enzyme-1.20.6-0.4.0.jar` | 21 | `0.100.8+1.20.6` |
| 1.21.8 | `enzyme-1.21.8-0.4.0.jar` | 21 | `0.136.1+1.21.8` |
| 1.21.11 | `enzyme-1.21.11-0.4.0.jar` | 21 | `0.141.6+1.21.11` |
| 26.2 | `enzyme-26.2-0.4.0.jar` | 25 或更新 | `0.161.0+26.2` |

本次使用 **Fabric Loader 0.19.5、Windows x64** 验证。Release 内置 Windows x64 原生执行器；Linux/macOS 和 ARM64 须构建对应执行器，当前发布不声明这些平台已完成实机验证。

1.18.1 请安装 [Fabric 官方完整 API 包](https://github.com/FabricMC/fabric-api/releases/tag/0.46.6%2B1.18)，旧 Maven 文件仅声明 API 模块。不要安装 sources JAR、审计探针，也不要混用其他 Minecraft 版本的 Enzyme。旧 Pack Mod 用户保留原配置即可：内部 MOD ID `packmod` 和配置目录名称保持兼容。

## 使用

1. 打开“选项 → 资源包”。首次自动检测，后续点击“检查资源包版本”。
2. 勾选未启用的包，打开“转换选项”或“预览转换任务”。
3. 转换完成后查看报告，手动启用输出包并确认材质效果。
4. 在“转换历史”中查看详情、导出诊断、重试失败任务或归档／恢复原包。

- 支持 ZIP、MCPACK、文件夹输入；只转换暂存副本，输出通过 ZIP 全量读取/CRC 和目标格式检查后安装。
- 已启用、实际应用及原版右侧待应用的包受保护，不可转换或归档。
- 无警告结果自动将原包移入 `resourcepacks/Old Pack/`；有警告则保留原包，供检查后显式归档。
- 提供保守和完整预设，支持后台运行、取消、历史搜索与筛选。默认关闭生成、删除、着色器和透明图层修复，开启源版本覆盖层合成，使用 2 线程、256 MiB 图像预算。
- 源覆盖层按源包声明版本合成。输出面向一个目标版本，多版本分支留在原包中。MCPACK 属于实验性转换；模型、着色器、OptiFine/CIT 等仍需检查真实视觉效果。
- 图像预算是准入估算，不是操作系统内存硬限制。输入上限 100,000 文件 / 500 MiB，单图上限 16,777,216 像素。

转换请求、结果和操作日志在实例的 `packmod-tools/pyramid/jobs/`。报告详情可导出经过路径、账户及凭据脱敏的文本诊断 ZIP；不会包含玩家资源包、贴图或完整游戏日志。缺少对应平台执行器时，转换按钮会禁用。与 Embers Client 共装时停用独立转换入口，避免两个转换器同时操作资源包。

## 构建与验证

主构建对应 26.2，需要 JDK 25 或更新版本。首次构建需要网络：

```powershell
.\gradlew.bat clean build --console=plain
```

其余五个版本需要 Python 3.12+ 和 JDK 17/21 x64：

```powershell
$env:PACKMOD_JAVA17_HOME = '你的 JDK 17 目录'
$env:PACKMOD_JAVA21_HOME = '你的 JDK 21 目录'
python -X utf8 compat/build_all.py --audit --production-audit
```

26.2 JAR 位于 `build/libs/`，其他版本位于 `build/compat-delivery/`。审计需要可用桌面与显卡，会打开隔离测试客户端；所有测试数据位于 build，不操作现有游戏实例。

构建检查真实原生转换、源包保护、选项、历史、归档恢复、ZIP、核心/模板哈希和生产 JAR。兼容构建另检查各目标格式的升降级、Java 17/21 字节码，并可执行开发和正式 Fabric 客户端的鼠标流程审计。自动检查不能覆盖全部真实材质和第三方 MOD 组合，实测步骤见 [TESTING.md](TESTING.md)。

```powershell
python -X utf8 tools/release/audit_upload.py --history-ref HEAD
python -X utf8 tools/release/test_packaging.py
cargo test --locked --manifest-path pyramid-bridge/Cargo.toml
```

发布前扫描 Git 工作文件、历史 blob 和最终发行包。游戏实例、账户文件、密钥、聊天记录及缓存不属于仓库内容。远端流水线配置见 `.github/workflows/`；本地验证结果与尚未运行的 CI 分开记录。

## 维护与来源

MOD 版本 **0.4.0**，转换核心保持 **0.2.2**。207 个核心文件冻结且构建验证集合与 SHA256；新增版本只适配 MOD 层，不改变转换器核心。核心已有本地补丁与并线基线见 [LOCAL-PATCHES.md](tools/upstream/LOCAL-PATCHES.md)，接口与维护说明见 [HANDOFF.md](HANDOFF.md) 和 [兼容层说明](compat/README.md)。

上游：**2-Pyramid 2.7.0**，固定提交 `503a0859e3ab6bf09ed03f361b9335e5931a6d6d`，MIT，Copyright © 2025–2026 2-Pyramid Studio。Java 层来源记录在 `EXTRACTION.json`。项目 MIT 许可见 [LICENSE](LICENSE)；上游与第三方依赖的版权和各自许可证保留在 [NOTICE.md](NOTICE.md) 及 JAR 的 `META-INF/licenses/`。

遇到问题请在 [Issues](https://github.com/EmbersDrift/Enzyme/issues) 提供游戏版本、操作步骤、其他 MOD 列表与诊断 ZIP；提交前自行检查文件，避免上传访问令牌、账户文件和未经允许的资源包。
