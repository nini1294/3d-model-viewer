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

## Automated Releases
Pushing a version tag automatically triggers GitHub Actions to run tests, build the optimized release APK, and publish a GitHub Release with the APK attached:

```bash
git tag v0.1.0
git push origin v0.1.0
```

## Roadmap

### 📦 Additional Formats
- [ ] **Wavefront OBJ (`.obj`)**: streaming parser for vertex and face definitions
- [ ] **3D Manufacturing Format (`.3mf`)**: ZIP archive extraction + XML mesh reading (modern 3D printing standard)
- [ ] **Polygon File Format (`.ply`)**: binary and ASCII mesh parsing
- [ ] **glTF / GLB (`.gltf`, `.glb`)**: modern real-time 3D asset support

### 🛠️ Features & Tools
- [ ] **Measurement Tool**: tap two points or vertices on the mesh to measure real-world distance in mm
- [ ] **Standard & Orthographic Views**: one-tap perspective ↔ orthographic switch, and instant Top / Front / Side / Isometric camera alignment
- [ ] **Cross-Section Clipping Plane**: interactive slicer plane to inspect internal cavities and wall thickness
- [ ] **Mesh Diagnostics**: detect and warn about non-manifold edges, holes, or inverted/flipped face normals
- [ ] **Recent Files**: quick-access list of recently opened models with persisted document URI permissions
- [ ] **Snapshot & Share**: render high-res transparent or framed screenshots directly to the system Share sheet
- [ ] **F-Droid Packaging**: reproducible build metadata and fastlane listing for F-Droid inclusion

## Contributing
Issues and pull requests are welcome! Please run `./gradlew testDebugUnitTest` before submitting.

## License
[Apache License 2.0](LICENSE)
