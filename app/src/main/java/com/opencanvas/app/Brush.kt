package com.opencanvas.app

enum class TipKind { ROUND, GRAIN, IMAGE }

/**
 * Everything that defines how a brush behaves. Pure data (no Android types),
 * so it can be saved as JSON and unit-tested.
 */
data class BrushSpec(
    val id: String,
    val name: String,
    val tip: TipKind = TipKind.ROUND,
    val tipFile: String? = null,
    val invertTip: Boolean = false,
    /** Distance between stamps as a fraction of the brush diameter. */
    val spacing: Float = 0.08f,
    /** 0 = very soft edge, 1 = hard edge. */
    val hardness: Float = 0.9f,
    /** Opacity of each stamp; low values build up like an airbrush. */
    val flow: Float = 1f,
    /** How much pen pressure changes the size (0 = not at all, 1 = fully). */
    val sizePressure: Float = 0.6f,
    /** How much pen pressure changes the flow. */
    val flowPressure: Float = 0f,
    val sizeJitter: Float = 0f,
    /** Rotation of the tip in degrees. */
    val angle: Float = 0f,
    /** Random rotation per stamp, 0..1 (1 = any direction). */
    val angleJitter: Float = 0f,
    /** Rotate the tip to follow the stroke direction. */
    val followStroke: Boolean = false,
    /** 1 = round, small = flat chisel. */
    val roundness: Float = 1f,
    /** Random sideways offset per stamp, as a fraction of the diameter. */
    val scatter: Float = 0f,
    /** Amount of grain texture in the tip (grain tips only). */
    val grain: Float = 0f,
    /** Stroke stabilisation: 0 = follows the finger exactly, 1 = very smooth. */
    val smoothing: Float = 0.3f,
    val builtin: Boolean = false
) {
    val variants: Int get() = if (tip == TipKind.GRAIN) 3 else 1
}

object Brushes {
    val PENCIL = BrushSpec(
        id = "pencil", name = "Pencil", tip = TipKind.GRAIN,
        spacing = 0.2f, hardness = 0.75f, flow = 0.7f, sizePressure = 0.5f,
        flowPressure = 0.6f, grain = 1f, angleJitter = 1f, smoothing = 0.15f, builtin = true
    )
    val INK = BrushSpec(
        id = "ink", name = "Ink Pen", tip = TipKind.ROUND,
        spacing = 0.04f, hardness = 1f, flow = 1f, sizePressure = 0.85f,
        smoothing = 0.55f, builtin = true
    )
    val SOFT = BrushSpec(
        id = "soft", name = "Soft Brush", tip = TipKind.ROUND,
        spacing = 0.05f, hardness = 0.25f, flow = 0.9f, sizePressure = 0.5f,
        flowPressure = 0.3f, smoothing = 0.3f, builtin = true
    )
    val MARKER = BrushSpec(
        id = "marker", name = "Marker", tip = TipKind.ROUND,
        spacing = 0.05f, hardness = 0.95f, flow = 1f, sizePressure = 0.1f,
        roundness = 0.45f, angle = 35f, smoothing = 0.35f, builtin = true
    )
    val AIRBRUSH = BrushSpec(
        id = "airbrush", name = "Airbrush", tip = TipKind.ROUND,
        spacing = 0.06f, hardness = 0f, flow = 0.1f, sizePressure = 0.1f,
        flowPressure = 0.9f, smoothing = 0.2f, builtin = true
    )
    val CHARCOAL = BrushSpec(
        id = "charcoal", name = "Charcoal", tip = TipKind.GRAIN,
        spacing = 0.16f, hardness = 0.7f, flow = 0.8f, sizePressure = 0.5f,
        flowPressure = 0.7f, grain = 1f, angleJitter = 1f, scatter = 0.06f,
        sizeJitter = 0.2f, smoothing = 0.15f, builtin = true
    )
    val CALLIGRAPHY = BrushSpec(
        id = "calligraphy", name = "Calligraphy", tip = TipKind.ROUND,
        spacing = 0.03f, hardness = 1f, flow = 1f, sizePressure = 0.5f,
        roundness = 0.22f, angle = 40f, smoothing = 0.5f, builtin = true
    )

    /** Used internally by the smudge tool. */
    val SMUDGE_TIP = BrushSpec(
        id = "smudge-tip", name = "Smudge", tip = TipKind.ROUND,
        spacing = 0.1f, hardness = 0.5f, flow = 1f, sizePressure = 0.3f,
        smoothing = 0.2f, builtin = true
    )

    val ALL: List<BrushSpec> = listOf(PENCIL, INK, SOFT, MARKER, AIRBRUSH, CHARCOAL, CALLIGRAPHY)
}
