# Enzyme

**Created and maintained by EmbersDrift.**

Check pack versions, preview conversion tasks, and batch-convert resource packs from Minecraft's vanilla resource pack screen. Supports Fabric and NeoForge, using the [2-Pyramid](https://github.com/realLivedInCorner/2-Pyramid) conversion core under the same [MIT License](LICENSE).

English | [简体中文](README.zh-CN.md)

## Screenshots

![Enzyme's resource pack selection and conversion screen in English](https://raw.githubusercontent.com/EmbersDrift/Enzyme/main/docs/images/pack-selection-en-us.png)

See the [Modrinth page draft](https://github.com/EmbersDrift/Enzyme/blob/main/MODRINTH.md) for more in-game screenshots and a player-facing overview.

## Download and installation

Download published builds from [Releases](https://github.com/EmbersDrift/Enzyme/releases), or find builds of the latest source in [Actions](https://github.com/EmbersDrift/Enzyme/actions). The current source version is **0.5.1**.

| Minecraft | Fabric | NeoForge | Java | Target pack format |
| --- | --- | --- | --- | --- |
| 1.18.1 | Yes | — | 17 | 8.0 |
| 1.20.1 | Yes | 47.1.106 | 17 | 15.0 |
| 1.20.6 | Yes | 20.6.144 | 21 | 32.0 |
| 1.21.8 | Yes | 21.8.57 | 21 | 64.0 |
| 1.21.10 | Yes | 21.10.64 | 21 | 69.0 |
| 1.21.11 | Yes | 21.11.48 | 21 | 75.0 |
| 26.2 | Yes | 26.2.0.93 | 25 or newer | 88.0 |

Install one JAR matching your game version and loader in your instance's `mods` folder:

- **Fabric:** `enzyme-<minecraft-version>-0.5.1.jar`, plus **Fabric API** for that Minecraft version. Tested with Fabric Loader 0.19.5. For 1.18.1, use the [official complete API package](https://github.com/FabricMC/fabric-api/releases/tag/0.46.6%2B1.18).
- **NeoForge:** `enzyme-neoforge-<minecraft-version>-0.5.1.jar`. The 1.20.1 build uses the early Forge-compatible NeoForge release. There is no NeoForge build for 1.18.1.

Enzyme runs on the client. The bundled conversion runner currently supports **Windows x64**; other operating systems and architectures require a separately built runner. Install the production JAR rather than a sources JAR or test probe.

## Usage

1. Open **Options → Resource Packs** and click **Check Pack Versions**. A check also runs automatically the first time you open this screen.
2. Select inactive packs. Preview the conversion task or adjust its settings, then start conversion.
3. Review the report, move the converted pack to the selected list, and apply it to check the result.

When you move an incompatible pack to the selected list, Minecraft's confirmation dialog also offers a conversion button. It opens the converter with that pack selected.

The separate **Conversion Settings** button provides **Restore Defaults**, **Conservative Preset**, and **Complete Preset**. Missing resource generation, shader adaptation, alpha layer fixes, and overlay flattening are enabled by default; deletion of old resources is disabled. Existing saved settings are retained. Use **Restore Defaults** and save to adopt the current defaults.

Conversion runs on a copy. Output is installed after ZIP/CRC and target format checks. Warning-free originals are archived to `resourcepacks/Old Pack/`; originals with warnings stay in place for inspection. Applied packs and packs pending application are protected. Converted packs are never enabled automatically. Conversion history provides report details, retries, diagnostic exports, and archive restoration.

## Conversion compatibility

The target format is read from the running Minecraft client and checked against the JAR's declaration. Conversion is disabled if they disagree. Each output targets the current game's format; its filename may show a range of versions that share that format.

The mod's Java adaptation layer handles supported legacy item predicates, shader interfaces, and atlas registration for custom model textures. Repairs and items needing review are recorded in the report. Predicates that cannot be migrated automatically are retained with a warning.

OptiFine/CIT rules are preserved, but their visual effects require compatible client extensions. Models, shaders, and third-party mod combinations still need visual testing. MCPACK conversion is experimental.

Input limits are 100,000 files and 500 MiB, with up to 16,777,216 pixels per image. Defaults are two worker threads and a 256 MiB image budget. This budget is used for image caching and task admission estimates; it is not a hard limit on total process memory.

## Building and testing

Requires Python 3.12+, JDK 17/21/26, and network access for the initial build.

```powershell
# Set JAVA_HOME to JDK 26, plus PACKMOD_JAVA17_HOME and PACKMOD_JAVA21_HOME.
.\gradlew.bat build --console=plain
python -X utf8 compat/build_all.py --production-audit
python -X utf8 neoforge/build_all.py --production-audit
```

The root build produces Fabric 26.2. Other Fabric builds are collected in `build/compat-delivery/`, and NeoForge builds in `build/neoforge-delivery/`. Client tests launch isolated game instances and require a desktop and GPU.

Test all three presets across supported versions with your own ZIP pack, and load the converted result in each client:

```powershell
python -X utf8 tools/regressions/test_real_pack.py 'D:\test-pack.zip' --clients
```

Checks cover target formats, unchanged source packs, ZIP CRC, PNG decoding, audio preservation, item definition migration, client resource reloads, and UI interactions. See [TESTING.md](TESTING.md), the [Fabric compatibility notes](compat/README.md), and the [NeoForge notes](neoforge/README.md).

For English UI audit screenshots, set `PACKMOD_AUDIT_LANGUAGE=en_us` before running `tools/game/run_audit.py` or `compat/run_audit.py`. The default audit language remains `zh_cn`.

## Upstream and maintenance

Conversion core **0.3.0** is based on 2-Pyramid **2.200.0**, pinned to commit `812a7358536c28772cfda3541a83e83f9a2d8825`. Its 183 files are frozen with file-set and SHA-256 checks. The pack compatibility fixes are in the Java mod layer. See [LOCAL-PATCHES.md](tools/upstream/LOCAL-PATCHES.md) for upstream differences and [HANDOFF.md](HANDOFF.md) for maintenance details.

Upstream and third-party copyright and licensing notices are in [NOTICE.md](NOTICE.md).

To [report a problem](https://github.com/EmbersDrift/Enzyme/issues), include your Minecraft version, loader, reproduction steps, and conversion report.
