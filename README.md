# NovaForge Engine

NovaForge Engine is a mobile-first 2D game engine for Android. Its goal is to let creators create/import projects, edit scenes, write Lua or NovaBlocks logic, test games and manage project files directly on a phone or tablet.

## Current 0.1 foundation

This repository began empty. The first implementation establishes a real executable editor/runtime foundation:

- Android native app;
- Storage Access Framework workspace (no `Android/data` project jail);
- visible `NovaForge/Projects` layout;
- project creation and safe ZIP import;
- versioned `project.nova` and `.scene` JSON;
- hierarchical 2D scene graph;
- touch viewport with pan, pinch zoom, select, drag, grid and snapping;
- inspector, scene tree and undo/redo base;
- real Lua execution through LuaJ;
- `NovaForge` Lua API + signals;
- NovaBlocks versioned IR compiled to Lua;
- isolated Play/Stop mode;
- runtime console;
- GitHub Actions debug APK build.

See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) and [`docs/ROADMAP.md`](docs/ROADMAP.md).

## Project structure

```text
NovaForge/
  Projects/
    MyGame/
      project.nova
      Scenes/Main.scene
      Scripts/
      Blocks/
      Assets/
      Resources/
      Plugins/
      .novaforge/
  Templates/
  Themes/
  Exports/
  Backups/
  Cache/
  Logs/
  UserData/
```

## Build

CI builds an installable debug APK on every push/PR. Locally, use JDK 17, Android SDK 35 and Gradle 8.9:

```bash
gradle :app:assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Lua example

```lua
function ready()
    NovaForge.print("Player iniciado")
end

function update(delta)
    self.x = self.x + 120 * delta
end

NovaForge.onSignal("move_right", function(value)
    self.x = self.x + (value or 2)
end)
```

NovaBlocks can coexist with Lua on the same node and compiles internally to Lua before execution.
