# NovaForge Engine architecture

## Scope of the 0.1 foundation

NovaForge is deliberately split into editor, serialized project data and runtime. Play Mode never runs directly against the editor scene: `SceneCodec.deepCopy()` creates an isolated runtime scene, and Stop discards it.

### Android shell

- Native Kotlin Android app.
- Mobile-first controls use standard Android Views and a custom touch viewport.
- The editor supports portrait/landscape at the Android level; the viewport handles pan and pinch zoom.

### Storage

`ProjectStorage` uses the Storage Access Framework and `DocumentFile`. The user grants access to a visible NovaForge directory once using `ACTION_OPEN_DOCUMENT_TREE`; the URI permission is persisted. The app does not use `Android/data` as the project workspace.

Expected workspace layout:

```text
NovaForge/
  Projects/
  Templates/
  Themes/
  Exports/
  Backups/
  Cache/
  Logs/
  UserData/
```

Project ZIP import validates every entry before extraction. Absolute paths, drive paths, `.` and `..` segments are rejected to prevent Zip Slip/path traversal.

### Project and scene format

`project.nova` and `.scene` are versioned JSON. Nodes use persistent UUID strings, avoiding fragile object-memory references. A scene node currently stores type, transform, size, tags, script/block paths, generic properties and children.

### 2D editor

`NovaViewportView` reads and mutates scene nodes directly and supports pan, pinch zoom, selection, drag, grid, snapping, selection bounds, camera-frame visualization and forwarding touch events to Play Mode.

Undo/redo is command-based (`EditorCommand` + `UndoManager`) so future operations can join one global history.

### Lua runtime

The engine integrates LuaJ (`org.luaj:luaj-jse`) rather than inventing a fake Lua syntax. Each node script has an isolated Lua global environment and receives `self` plus a `NovaForge` API containing `print`, `getDelta`, `getNode`, `findByTag`, `emitSignal`, `onSignal`, `reloadScene` and `quit`.

Node proxies synchronize `x`, `y`, `rotation`, `scaleX`, `scaleY`, `visible` and `enabled` around Lua callbacks. Methods include `move`, `setPosition`, `rotate`, `setVisible` and `destroy`.

Lifecycle callbacks currently supported are `ready`, `update(delta)`, `fixedUpdate(delta)` and `onTouch(event)`.

### NovaBlocks

NovaBlocks is represented by a versioned IR (`BlockGraph` -> `BlockScript` -> `BlockNode`) and deterministically compiled to Lua. This keeps UI, serialization and runtime independent. The initial compiler covers movement/transform, signals, variables, conditionals, debug output and object destruction.

### Play Mode and console

Play Mode runs on Android `Choreographer`. Lua receives clamped frame delta and a 60 Hz fixed-update accumulator. Play operates on a deep copy of the scene, so runtime mutations are discarded by Stop. Engine/Lua errors are routed to an in-memory console with level, timestamp, source and line when recoverable.

## Architectural constraints

- 2D before 3D.
- Project files remain open/versionable formats.
- Runtime state never mutates the saved editor scene implicitly.
- Storage stays compatible with scoped-storage Android rules.
- Blocks and Lua may coexist on one node.
- New node behavior should migrate toward components rather than an ever-growing monolithic node class.
