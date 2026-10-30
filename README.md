# Carnival

An original, Procreate-style drawing app for Android, built from scratch in Kotlin. (Earlier working name: OpenCanvas.)

## Build (GitHub Actions)
Push to `main` -> Actions tab -> latest run -> download the `opencanvas-debug-apk` artifact -> install on your device.

## Roadmap
- [x] Phase 1: canvas, pressure, smoothing, pan/zoom, undo/redo, eraser, colors, size
- [x] Phase 2: layers (opacity, visibility, lock, blend modes, reorder, copy), Procreate-style layout, color picker, Save PNG
- [x] Phase 3: gestures (two-finger tap undo, three-finger tap redo, QuickShape hold-to-snap, long-press eyedropper)
- [x] Phase 4: brush engine, brush library, Brush Studio, custom brushes and image tips, smudge, patch-based undo
- [x] Phase 4b (0.5.0): 27 built-in brushes in categories, edit/reset built-ins, smudge settings and precise smudge, layer settings/rename/merge, app settings
- [x] 0.5.1: performance (live strokes and smudge drawn in small tiles, faster smudge maths), smaller faster-installing build
- [ ] Phase 5 (0.6.0): canvas sizes (A4, A3, ...), gallery of saved drawings, export formats (PNG, JPG, WebP, PDF, SVG, layered ZIP)
- [ ] Phase 6 (0.7.0): gradients (linear, radial, angular, spiral, reflected, diamond)
- [ ] Phase 7: GPU engine (OpenGL ES), larger canvases, more layers
- [ ] Phase 8: selection and transform, QuickShape editing
- [ ] Phase 9: color wheel and palettes, ColorDrop fill, alpha lock, clipping masks
- [ ] Phase 10: adjustments, Liquify, time-lapse, drawing guides, animation
