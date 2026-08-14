# NovaForge roadmap

The repository started empty, so the first branch establishes an executable architecture rather than pretending the entire long-term specification can be completed at once.

## Implemented foundation

- Android application skeleton and CI APK build.
- SAF workspace selection with persisted permission and visible NovaForge folder structure.
- Create/list/open projects.
- Safe ZIP project import.
- Versioned `project.nova` and `.scene` JSON.
- Hierarchical scene nodes with UUIDs, transforms, tags, scripts and blocks.
- 2D viewport with pan, pinch zoom, selection, drag, grid and snapping.
- Scene tree dialog, inspector, node create/duplicate/delete.
- Command undo/redo foundation.
- Real Lua runtime, lifecycle callbacks, node API and signal bus.
- Lua editor with a mobile symbol row.
- Versioned NovaBlocks IR and compiler to Lua.
- Minimal touch NovaBlocks event/action builder.
- Isolated Play/Stop loop and runtime console.
- 30-second editor autosave while dirty.

## Next milestones

### 0.2 editor reliability

- Rotating backups in `Backups/` and explicit crash recovery.
- Dirty-region autosave rather than whole-scene writes.
- Reparenting and hierarchy drag/drop.
- Multi-selection, resize/rotate gizmos and richer snapping.
- Asset browser with import/move/rename/delete/search/preview.
- Bitmap/font/audio rendering backed by project assets.
- Camera preview, limits and smoothing.
- Copy/paste across nodes/components.

### 0.3 NovaBlocks editor

- Touch-first block canvas with snapping, zoom and two-finger pan.
- Palette categories and search.
- Expression editor with variables/properties/math/logic.
- Signal tag picker.
- Nested control blocks, functions, lists and strings.
- Hold/press TouchButton event semantics.
- Coroutine-based wait/timer blocks.

### 0.4 runtime 2D

- Component registry (`Transform`, `SpriteRenderer`, `Collider`, `LuaScript`, `VisualScript`).
- Sprite/AnimatedSprite, audio, timers and UI controls.
- Collision/Area2D and initial physics integration.
- TileMap and resource cache.
- Debug overlay and profiler counters.

### 0.5 authoring/export

- Theme ZIP validation and Nova Dark/Nova Light theme packs.
- Templates.
- Project ZIP export.
- On-device game APK export pipeline. This is intentionally separate from the editor APK build: SDK/toolchain packaging, signing, Gradle isolation and Android storage/security constraints need a dedicated implementation instead of a fake Export button.
- Import/export migration tests across project format versions.

## Definition of done

A visible control is not considered implemented until it reads/writes real project data or performs the advertised runtime action. Partial complex features are documented here instead of being represented as completed UI.
