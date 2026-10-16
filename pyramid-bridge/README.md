# 2-Pyramid shared conversion core

Upstream: https://github.com/realLivedInCorner/2-Pyramid
Pinned commit: `812a7358536c28772cfda3541a83e83f9a2d8825` (2.200.0, MIT).

The crate exports `pyramid_core::{Request, Options, JobResult, convert, plan, capabilities}`. The CLI is a thin adapter:

```
embers-pyramid request.json result.json
embers-pyramid --capabilities
embers-pyramid --plan 34 88
```

Request fields: `input`, `output_dir`, `target_major`, `target_minor`, optional `options`, `cancel_file`, `events_file`, `templates_dir`. Inputs are ZIPs; the Java integration stages folder/MCPACK inputs into owned ZIPs. The library never archives the user's original. Calls are serialized within a process; Rayon pools are per request. Desktop shell/config/updater/network code is excluded. Desktop integration can use this crate without spawning the CLI, but the upstream desktop repository is not modified here.

Options default to conservative conversion: destructive cleanup, generation, shader adaptation and alpha fixes disabled; source overlay materialization enabled; two workers; 256 MiB image admission budget. Worker range is 1–4, budget 128–1024 MiB. Admission estimates the largest image at 16 bytes per pixel per concurrent worker, plus one quarter of the budget for the context cache. It reduces actual conversion concurrency before rejecting a pack that cannot fit at one worker, rather than summing every image in the pack. Images above 16,777,216 pixels are rejected. Reports include `image_budget_checks` and task `worker_threads` diagnostics. This remains an estimate, not a hard process memory limit.

Overlays active at the declared source format are merged in original order before migration. Output targets one version and reports `overlays_flattened` so the MOD retains the original. Overlay migration can be disabled, which retains layers without converting them and reports `overlays_unconverted`. Invalid ranges, unsafe paths and overlapping overlay directories fail. Bedrock remains experimental. Legacy font assets are retained; removed legacy_unicode providers are replaced with the vanilla unifont include.

Protocol schema remains 1, with `completed`, `needs_review`, `failed` and `cancelled` statuses. Tasks include a `skipped` flag. The collector records paths, tasks, warnings and file counts, and consumes every output ZIP entry to validate decompression/CRC. Java additionally verifies Minecraft metadata. Readability and task success do not prove visual fidelity.

Version 0.2.2 reads image headers using content detection. BMP/JPEG files named `.png` are re-encoded as PNG in the staging tree only, after all images pass admission checks. Dimensions and decoded pixels are retained; potentially animated or multipage formats are not flattened. Decoder allocation for these repairs is limited to one quarter of the selected budget. Corrupt and oversized inputs still fail. Additive `repairs` entries contain `code`, `path` and `detail` for resolved issues; they do not force `needs_review`. Source format-range notices become `format_range_rewritten` repairs only after the final target metadata is written and checked; unresolved warnings remain warnings.

The cancellation file is checked at stage boundaries, during inventory, extraction, packing and before task execution. Image operations already running may finish before a cancellation check. The Java adapter enforces process timeout and can terminate an unresponsive runner. Progress is flushed as NDJSON events with `stage`, `task`, `completed`, `total`.

Build on the intended OS/CPU:

```
cargo test --locked --manifest-path pyramid-bridge/Cargo.toml
cargo build --release --locked --manifest-path pyramid-bridge/Cargo.toml
python pyramid-bridge/package.py
```

Packaging supports host platform detection, `--platform`, `--binary`, `CARGO_TARGET_DIR` and `CARGO_HOME`. Supported resource IDs are windows/linux/macos with x86_64/aarch64. Only the Windows x86_64 runner is currently bundled and tested locally; `.github/workflows/native-runners.yml` builds on native CI hosts when run. The known format graph is reported by `--capabilities`; only 97.1 is accepted as a nonzero minor target in this pinned snapshot.

Core 0.3.0 integrates upstream A-ROM (`arom`, `natives`, `native_run`, `pack`, `bedrock_convert`). The obsolete hurray/converters execution tree is removed. Source provenance and the eleven adapted upstream files are recorded in `tools/upstream/import-manifest.json` and `LOCAL-PATCHES.md`. Repairs, active overlays and normalized pack roots feed A-ROM through an owned DirSource. ZIP output remains native; output image admission reads headers from the Pack view without materializing another image tree. Decoded caches evict within their configured shares; encoded blobs use the selected budget as an additional capacity limit. These limits are not a process RSS guarantee.
