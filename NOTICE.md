# Attribution and licenses

Enzyme is distributed under the MIT license in [LICENSE](LICENSE).

The conversion core and UImage templates derive from
[realLivedInCorner/2-Pyramid](https://github.com/realLivedInCorner/2-Pyramid),
version 2.7.0, commit `503a0859e3ab6bf09ed03f361b9335e5931a6d6d`.
Copyright (c) 2025-2026 2-Pyramid Studio. The original MIT notice remains in
`pyramid-bridge/LICENSE-2-Pyramid` and the bundled third-party notices.
The frozen local core is version 0.2.2; its existing differences from upstream
are documented in `tools/upstream/LOCAL-PATCHES.md`.

The Java client layer was extracted from Embers Client and adapted for this
standalone project. Its provenance is recorded in `EXTRACTION.json`.
Enzyme's project code, build tools, adapters and documentation are released
under MIT at the project owner's direction. The internal `packmod` MOD ID and
configuration names are retained for compatibility with earlier versions.

Bundled Rust dependencies retain their own licenses and copyright notices
under `src/client/resources/META-INF/licenses/pyramid-dependencies/` (also
included in MOD JARs). MIT for this project does not replace those licenses.
Minecraft, Fabric Loader and Fabric API are external dependencies and are not
included in the release assets.
