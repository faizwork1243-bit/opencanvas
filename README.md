# Carnival

An original, Procreate-style drawing app for Android, built from scratch in Kotlin. (Earlier working name: OpenCanvas.)

## Build (GitHub Actions)
Push to `main` -> Actions tab -> latest run -> download the `opencanvas-debug-apk` artifact -> install on your device.

## Roadmap
- [x] Phase 1: canvas, pressure, smoothing, pan/zoom, undo/redo, eraser, colors, size
- [x] Phase 2: layers (opacity, visibility, lock, blend modes, reorder, copy), Procreate-style layout, color picker, Save PNG
- [x] Phase 3: gestures (two-finger tap undo, three-finger tap redo, QuickShape hold-to-snap, long-press eyedropper)
- [x] Phase 4: brush engine (stamp brushes, 7 built-in brushes, smudge), brush library, Brush Studio with custom brushes and image tips, patch-based undo
- [ ] Phase 5: GPU engine (OpenGL ES), larger canvases, more layers
- [ ] Phase 6: selection and transform, QuickShape editing
- [ ] Phase 7: color wheel and palettes, ColorDrop fill, alpha lock, clipping masks
- [ ] Phase 8: gallery, project save/load, export PNG/JPEG/PSD, import images
- [ ] Phase 9: adjustments, Liquify, time-lapse, drawing guides, animation
