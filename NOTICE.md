# Attribution and licenses

Enzyme is distributed under the MIT license in [LICENSE](LICENSE).

The Enzyme MOD is created and maintained by EmbersDrift.
The upstream and third-party attributions below identify the sources of
components and artwork and remain separate from the MOD author metadata.

The conversion core and UImage templates derive from
[realLivedInCorner/2-Pyramid](https://github.com/realLivedInCorner/2-Pyramid),
version 2.200.0, commit `812a7358536c28772cfda3541a83e83f9a2d8825`.
Copyright (c) 2025-2026 2-Pyramid Studio. The original MIT notice remains in
`pyramid-bridge/LICENSE-2-Pyramid` and the bundled third-party notices.
The frozen local core is version 0.3.0; its integration differences from upstream
are documented in `tools/upstream/LOCAL-PATCHES.md`.

The MOD icon was supplied by the project owner from
`KyanitePackTool-Stable-Edition1.4.0/KyanitePackTool.exe`. The embedded 256x256
Windows icon PNG was extracted without resizing or recoloring to
`assets/packmod/icon.png`. Source and export hashes are recorded in
`tools/upstream/desktop-icon.json`. This artwork retains its original
attribution and licensing; Enzyme's project license does not relicense it.

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
