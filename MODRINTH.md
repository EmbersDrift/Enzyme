# Enzyme

**Check, preview, and convert Minecraft resource packs without leaving the game.**

**Created and maintained by EmbersDrift.**

[简体中文](https://github.com/EmbersDrift/Enzyme/blob/main/MODRINTH.zh-CN.md)

Enzyme brings resource pack conversion to Minecraft's vanilla resource pack screen, with support for Fabric and NeoForge. Update an older pack or convert a newer one for a supported older version. Preview the task, choose your settings, and review the report before applying the result.

## Features

- **In-game batch conversion:** inspect pack formats and select one or more inactive packs to convert.
- **Vanilla UI integration:** open the converter directly from the confirmation dialog for an incompatible pack.
- **Separate conversion settings:** use the defaults, Conservative Preset, or Complete Preset, with options for missing resources, shaders, alpha layers, and overlays.
- **Model adaptation:** migrate supported legacy item predicates and adapt shield models and custom model texture atlases.
- **Reports and history:** review warnings and repairs, retry failed tasks, and export diagnostics.
- **Original pack protection:** conversion runs on a copy. Applied packs and packs pending application are protected; originals can be archived and restored.

Converted packs are never enabled automatically. Review the report, then apply the output yourself.

## Screenshots

Captured in Minecraft 26.2 with the game language set to English (US), using small demonstration packs.

### Select packs to convert

Select inactive packs, open conversion settings, or preview the task. Packs already applied or pending application remain protected.

![Enzyme pack selection: one pack selected for conversion, with applied and pending packs protected](https://raw.githubusercontent.com/EmbersDrift/Enzyme/main/docs/images/pack-selection-en-us.png)

### Adjust conversion settings

Choose a preset or configure resource generation, shader adaptation, alpha fixes, and overlay flattening individually.

![Enzyme conversion settings in English, showing the default conversion options](https://raw.githubusercontent.com/EmbersDrift/Enzyme/main/docs/images/conversion-settings-en-us.png)

### Convert an incompatible pack

Use the conversion button in Minecraft's incompatible pack confirmation dialog to open the converter with that pack selected.

![Minecraft's incompatible resource pack confirmation dialog with Enzyme's conversion button](https://raw.githubusercontent.com/EmbersDrift/Enzyme/main/docs/images/incompatible-prompt-en-us.png)

### Review the result

Check the conversion status and original pack archive status before enabling the output.

![Enzyme conversion result in English: conversion completed and the original pack archived](https://raw.githubusercontent.com/EmbersDrift/Enzyme/main/docs/images/conversion-result-en-us.png)

## Supported Minecraft versions

| Minecraft | Fabric | NeoForge | Java |
| --- | --- | --- | --- |
| 1.18.1 | Yes | — | 17 |
| 1.20.1 | Yes | Yes | 17 |
| 1.20.6 | Yes | Yes | 21 |
| 1.21.8 | Yes | Yes | 21 |
| 1.21.10 | Yes | Yes | 21 |
| 1.21.11 | Yes | Yes | 21 |
| 26.2 | Yes | Yes | 25 or newer |

Enzyme is a **client-side mod**; servers do not need it. Published builds currently bundle a **Windows x64** conversion runner. Other operating systems and architectures require a separately built compatible runner.

## Installation

1. Install Fabric or NeoForge for your Minecraft version.
2. For Fabric, also install **Fabric API** for the same game version.
3. Download the Enzyme JAR matching both your Minecraft version and loader, and place it in your instance's `mods` folder.

Install one Enzyme JAR per instance. The Minecraft 1.20.1 NeoForge build uses the early Forge-compatible NeoForge release; check the download's version notes for the required loader.

## How to use

1. Open **Options → Resource Packs** and click **Check Pack Versions**.
2. Select the inactive packs you want to convert. Open **Conversion Settings** or preview the task if needed.
3. Start conversion, review the report, and manually enable the converted pack.

You can also open the converter when moving an incompatible pack to the selected list: use the conversion button in the confirmation dialog.

Missing resource generation, shader adaptation, alpha layer fixes, and overlay flattening are enabled by default. Deletion of old resources is disabled by default. Warning-free originals are archived to `resourcepacks/Old Pack/`; originals with warnings stay in place for inspection and comparison.

## Compatibility

ZIP and folder packs are supported; MCPACK conversion is experimental. The target format is checked against the running Minecraft version.

OptiFine/CIT rules are preserved, but their visual effects require compatible client extensions. Custom models, shaders, and combinations of third-party mods may still need manual checks. A successfully loaded pack does not guarantee that every extension-specific effect works in a vanilla client.

## Credits and license

The Enzyme mod is created and maintained solely by **EmbersDrift**.

The conversion core and templates come from [realLivedInCorner / 2-Pyramid](https://github.com/realLivedInCorner/2-Pyramid), with 2-Pyramid Studio's copyright and MIT notices preserved. See [NOTICE](https://github.com/EmbersDrift/Enzyme/blob/main/NOTICE.md) for copyright and licensing details.

Enzyme is licensed under the [MIT License](https://github.com/EmbersDrift/Enzyme/blob/main/LICENSE).

[Source code](https://github.com/EmbersDrift/Enzyme) · [Report an issue](https://github.com/EmbersDrift/Enzyme/issues)
