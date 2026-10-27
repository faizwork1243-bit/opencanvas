# OpenCanvas

An original, Procreate-style drawing app for Android. Built from scratch in Kotlin.

## Build (GitHub Actions)
Push to `main` -> Actions tab -> latest run -> download the `opencanvas-debug-apk` artifact -> install on your device.

## Roadmap
- [x] Phase 1: canvas, pressure, smoothing, pan/zoom, undo/redo, eraser, colors, size
- [x] Phase 2: layers (opacity, visibility, lock, blend modes, reorder, copy), Procreate-style layout, color picker, Save PNG
- [x] Phase 3: gestures (two-finger tap undo, three-finger tap redo, hold-to-straighten, long-press eyedropper)
- [ ] Phase 4: GPU engine (OpenGL ES), tiled undo, low-latency stylus path
- [ ] Phase 5: brush engine and Brush Studio (stamps, spacing, jitter, textures, smudge)
- [ ] Phase 6: selection and transform, QuickShape
- [ ] Phase 7: color wheel and palettes, ColorDrop fill, alpha lock, clipping masks
- [ ] Phase 8: gallery, project save/load, export PNG/JPEG/PSD, import images
- [ ] Phase 9: adjustments, Liquify, time-lapse, drawing guides, animation
