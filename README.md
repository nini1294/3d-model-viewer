# 3D Model Viewer for Android

A fast, lightweight, **open-source** 3D model viewer for Android. No ads, no trackers, no network access.

## Features
- Opens **binary and ASCII STL** (including binary files whose header starts with `solid`, and truncated files)
- Smooth rendering of multi-million-triangle models with OpenGL ES 3.0 and 4× MSAA
- Render modes: **Solid**, **Solid + edges**, **Wireframe**
- Touch controls: drag to orbit · two fingers to pan · pinch to zoom · double-tap to reset
- Build-plate grid that adapts to model size
- Model info: triangle count, dimensions, volume, surface area
- Opens files from the picker, **"Open with…"** in file managers, and the **Share** sheet
- Material You dynamic colours, light & dark themes

## Building
Requirements: Android Studio (or JDK 17+) and the Android SDK (platform 36).

```bash
./gradlew assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # parser & geometry unit tests
./gradlew installDebug           # install on a connected device
```

## Architecture
```
app/src/main/java/space/nish/stlviewer/
├── MainActivity.kt          # entry point, handles VIEW/SEND intents
├── model/
│   ├── Mesh.kt              # positions-only triangle soup, bounds, volume/area
│   └── StlParser.kt         # streaming binary + ASCII parser
├── render/
│   ├── ModelSurfaceView.kt  # GLSurfaceView + gestures + MSAA config
│   ├── ModelRenderer.kt     # GLES 3.0 drawing, grid
│   ├── OrbitCamera.kt       # Z-up orbit camera
│   └── Shaders.kt           # GLSL ES 3.00 shaders
└── ui/
    ├── ViewerScreen.kt      # Compose UI
    ├── ViewerViewModel.kt   # loading & state
    └── theme/Theme.kt
```

**Memory-saving design:** only vertex positions are stored (36 bytes/triangle). Flat normals are derived in the
fragment shader from screen-space derivatives, and wireframe barycentrics come from `gl_VertexID`, so no extra
vertex attributes are needed.

## Roadmap
- [ ] Recent files, screenshot & share
- [ ] Standard views (top/front/side), orthographic projection
- [ ] Measurement tool, cross-section plane
- [ ] OBJ, PLY and 3MF support
- [ ] F-Droid release

## Contributing
Issues and pull requests are welcome! Please run `./gradlew testDebugUnitTest` before submitting.

## License
[Apache License 2.0](LICENSE)
