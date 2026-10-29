package com.opencanvas.app

enum class TipKind(val label: String) {
    ROUND("Round"),
    GRAIN("Grain"),
    CRAYON("Crayon"),
    WATER("Watercolor"),
    BRISTLE("Bristle"),
    WEAVE("Canvas weave"),
    STAR("Star"),
    SQUARE("Square"),
    STRIPES("Stripes"),
    BLADE("Grass tuft"),
    HAIR("Hair strand"),
    LEAF("Leaf"),
    FOLIAGE("Foliage"),
    PINE("Pine tree"),
    CLOUD("Cloud"),
    IMAGE("Image")
}

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
    /** 0 = very soft edge, 1 = hard edge (round and grain tips). */
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
    /** Random offset per stamp, as a fraction of the diameter. */
    val scatter: Float = 0f,
    /** Amount of grain texture in the tip (grain tips only). */
    val grain: Float = 0f,
    /** Stroke stabilisation: 0 = follows the finger exactly, 1 = very smooth. */
    val smoothing: Float = 0.3f,
    /** Stamps dropped at every step (spray, grass tufts, freckles ...). */
    val count: Int = 1,
    /** Size of each stamp relative to the brush size (small dots inside a big spray area). */
    val tipScale: Float = 1f,
    /** Random lighter/darker shade per stamp, for natural-looking foliage and skin. */
    val colorJitter: Float = 0f,
    val category: String = "My brushes",
    val builtin: Boolean = false
) {
    val variants: Int
        get() = when (tip) {
            TipKind.GRAIN, TipKind.CRAYON, TipKind.BRISTLE, TipKind.FOLIAGE, TipKind.CLOUD -> 3
            else -> 1
        }
}

object Brushes {
    const val SKETCHING = "Sketching"
    const val INKING = "Inking"
    const val PAINTING = "Painting"
    const val AIRBRUSH_CAT = "Airbrushing"
    const val TEXTURES = "Textures"
    const val PATTERNS = "Patterns"
    const val SKIN = "Skin details"
    const val ENVIRONMENT = "Environment"
    const val CUSTOM = "My brushes"

    val CATEGORIES = listOf(
        SKETCHING, INKING, PAINTING, AIRBRUSH_CAT, TEXTURES, PATTERNS, SKIN, ENVIRONMENT
    )

    // ---- sketching ----
    val PENCIL = BrushSpec(
        id = "pencil", name = "Pencil", tip = TipKind.GRAIN, category = SKETCHING,
        spacing = 0.2f, hardness = 0.75f, flow = 0.7f, sizePressure = 0.5f,
        flowPressure = 0.6f, grain = 1f, angleJitter = 1f, smoothing = 0.15f, builtin = true
    )
    val CRAYON = BrushSpec(
        id = "crayon", name = "Crayon", tip = TipKind.CRAYON, category = SKETCHING,
        spacing = 0.14f, flow = 0.85f, sizePressure = 0.35f, flowPressure = 0.5f,
        angleJitter = 1f, smoothing = 0.2f, builtin = true
    )
    val CHARCOAL = BrushSpec(
        id = "charcoal", name = "Charcoal", tip = TipKind.GRAIN, category = SKETCHING,
        spacing = 0.16f, hardness = 0.7f, flow = 0.8f, sizePressure = 0.5f,
        flowPressure = 0.7f, grain = 1f, angleJitter = 1f, scatter = 0.06f,
        sizeJitter = 0.2f, smoothing = 0.15f, builtin = true
    )

    // ---- inking ----
    val INK = BrushSpec(
        id = "ink", name = "Ink Pen", tip = TipKind.ROUND, category = INKING,
        spacing = 0.04f, hardness = 1f, flow = 1f, sizePressure = 0.85f,
        smoothing = 0.55f, builtin = true
    )
    val MARKER = BrushSpec(
        id = "marker", name = "Marker", tip = TipKind.ROUND, category = INKING,
        spacing = 0.05f, hardness = 0.95f, flow = 1f, sizePressure = 0.1f,
        roundness = 0.45f, angle = 35f, smoothing = 0.35f, builtin = true
    )
    val CALLIGRAPHY = BrushSpec(
        id = "calligraphy", name = "Calligraphy", tip = TipKind.ROUND, category = INKING,
        spacing = 0.03f, hardness = 1f, flow = 1f, sizePressure = 0.5f,
        roundness = 0.22f, angle = 40f, smoothing = 0.5f, builtin = true
    )

    // ---- painting ----
    val SOFT = BrushSpec(
        id = "soft", name = "Soft Brush", tip = TipKind.ROUND, category = PAINTING,
        spacing = 0.05f, hardness = 0.25f, flow = 0.9f, sizePressure = 0.5f,
        flowPressure = 0.3f, smoothing = 0.3f, builtin = true
    )
    val WATERCOLOR = BrushSpec(
        id = "watercolor", name = "Watercolor", tip = TipKind.WATER, category = PAINTING,
        spacing = 0.07f, flow = 0.25f, sizePressure = 0.4f, flowPressure = 0.5f,
        sizeJitter = 0.1f, angleJitter = 1f, smoothing = 0.4f, builtin = true
    )
    val DRY_BRUSH = BrushSpec(
        id = "drybrush", name = "Dry Brush", tip = TipKind.BRISTLE, category = PAINTING,
        spacing = 0.08f, flow = 0.9f, sizePressure = 0.1f, flowPressure = 0.3f,
        followStroke = true, smoothing = 0.35f, builtin = true
    )

    // ---- airbrushing ----
    val AIRBRUSH = BrushSpec(
        id = "airbrush", name = "Airbrush", tip = TipKind.ROUND, category = AIRBRUSH_CAT,
        spacing = 0.06f, hardness = 0f, flow = 0.1f, sizePressure = 0.1f,
        flowPressure = 0.9f, smoothing = 0.2f, builtin = true
    )
    val SPRAY = BrushSpec(
        id = "spray", name = "Spray", tip = TipKind.ROUND, category = AIRBRUSH_CAT,
        spacing = 0.12f, hardness = 0.9f, flow = 0.85f, sizePressure = 0.3f,
        count = 30, tipScale = 0.05f, scatter = 0.5f, smoothing = 0.2f, builtin = true
    )

    // ---- textures ----
    val CANVAS = BrushSpec(
        id = "canvas", name = "Canvas", tip = TipKind.WEAVE, category = TEXTURES,
        spacing = 0.25f, flow = 0.85f, sizePressure = 0.2f, flowPressure = 0.4f,
        smoothing = 0.3f, builtin = true
    )
    val STONE = BrushSpec(
        id = "stone", name = "Stone", tip = TipKind.GRAIN, category = TEXTURES,
        spacing = 0.1f, hardness = 0.5f, flow = 0.9f, sizePressure = 0.3f,
        grain = 1f, angleJitter = 1f, scatter = 0.1f, sizeJitter = 0.3f,
        colorJitter = 0.5f, smoothing = 0.2f, builtin = true
    )

    // ---- patterns ----
    val DOTTED = BrushSpec(
        id = "dotted", name = "Dotted Line", tip = TipKind.ROUND, category = PATTERNS,
        spacing = 1.6f, hardness = 1f, flow = 1f, sizePressure = 0.3f,
        smoothing = 0.4f, builtin = true
    )
    val DASHES = BrushSpec(
        id = "dashes", name = "Dashes", tip = TipKind.ROUND, category = PATTERNS,
        spacing = 1.35f, hardness = 1f, flow = 1f, sizePressure = 0.2f,
        roundness = 0.3f, followStroke = true, smoothing = 0.5f, builtin = true
    )
    val STARS = BrushSpec(
        id = "stars", name = "Stars", tip = TipKind.STAR, category = PATTERNS,
        spacing = 1.1f, flow = 1f, sizePressure = 0.2f, angleJitter = 0.3f,
        sizeJitter = 0.25f, smoothing = 0.3f, builtin = true
    )
    val SQUARES = BrushSpec(
        id = "squares", name = "Squares", tip = TipKind.SQUARE, category = PATTERNS,
        spacing = 1.05f, flow = 1f, sizePressure = 0.2f, followStroke = true,
        smoothing = 0.4f, builtin = true
    )
    val STRIPES = BrushSpec(
        id = "stripes", name = "Stripes", tip = TipKind.STRIPES, category = PATTERNS,
        spacing = 0.5f, flow = 1f, sizePressure = 0.2f, smoothing = 0.3f, builtin = true
    )

    // ---- skin details ----
    val PORES = BrushSpec(
        id = "pores", name = "Pores", tip = TipKind.ROUND, category = SKIN,
        spacing = 0.25f, hardness = 0.8f, flow = 0.5f, sizePressure = 0.3f,
        count = 12, tipScale = 0.07f, scatter = 0.5f, colorJitter = 0.5f,
        smoothing = 0.2f, builtin = true
    )
    val FRECKLES = BrushSpec(
        id = "freckles", name = "Freckles", tip = TipKind.ROUND, category = SKIN,
        spacing = 0.45f, hardness = 0.6f, flow = 0.55f, sizePressure = 0.3f,
        count = 3, tipScale = 0.16f, scatter = 0.45f, sizeJitter = 0.8f,
        colorJitter = 0.3f, smoothing = 0.2f, builtin = true
    )
    val BLUSH = BrushSpec(
        id = "blush", name = "Blush", tip = TipKind.ROUND, category = SKIN,
        spacing = 0.05f, hardness = 0f, flow = 0.06f, sizePressure = 0.1f,
        flowPressure = 0.6f, smoothing = 0.4f, builtin = true
    )
    val FINE_HAIR = BrushSpec(
        id = "finehair", name = "Fine Hair", tip = TipKind.HAIR, category = SKIN,
        spacing = 0.35f, flow = 0.85f, sizePressure = 0.3f, count = 3, scatter = 0.3f,
        angleJitter = 0.12f, sizeJitter = 0.3f, colorJitter = 0.4f,
        smoothing = 0.3f, builtin = true
    )

    // ---- environment ----
    val GRASS = BrushSpec(
        id = "grass", name = "Grass", tip = TipKind.BLADE, category = ENVIRONMENT,
        spacing = 0.28f, flow = 1f, sizePressure = 0.3f, count = 2, scatter = 0.15f,
        tipScale = 0.9f, angleJitter = 0.14f, sizeJitter = 0.5f, colorJitter = 0.6f,
        smoothing = 0.3f, builtin = true
    )
    val FOLIAGE = BrushSpec(
        id = "foliage", name = "Foliage", tip = TipKind.FOLIAGE, category = ENVIRONMENT,
        spacing = 0.35f, flow = 1f, sizePressure = 0.3f, count = 2, scatter = 0.25f,
        angleJitter = 1f, sizeJitter = 0.5f, colorJitter = 0.7f, smoothing = 0.3f, builtin = true
    )
    val LEAVES = BrushSpec(
        id = "leaves", name = "Leaves", tip = TipKind.LEAF, category = ENVIRONMENT,
        spacing = 0.6f, flow = 1f, sizePressure = 0.3f, count = 2, scatter = 0.4f,
        tipScale = 0.5f, angleJitter = 1f, sizeJitter = 0.45f, colorJitter = 0.6f,
        smoothing = 0.3f, builtin = true
    )
    val PINES = BrushSpec(
        id = "pines", name = "Pine Trees", tip = TipKind.PINE, category = ENVIRONMENT,
        spacing = 1.1f, flow = 1f, sizePressure = 0.3f, scatter = 0.1f, angleJitter = 0.04f,
        sizeJitter = 0.35f, colorJitter = 0.5f, smoothing = 0.3f, builtin = true
    )
    val CLOUDS = BrushSpec(
        id = "clouds", name = "Clouds", tip = TipKind.CLOUD, category = ENVIRONMENT,
        spacing = 0.5f, flow = 0.6f, sizePressure = 0.3f, scatter = 0.2f,
        sizeJitter = 0.4f, colorJitter = 0.1f, smoothing = 0.3f, builtin = true
    )

    /** Used internally by the smudge tool. */
    val SMUDGE_TIP = BrushSpec(
        id = "smudge-tip", name = "Smudge", tip = TipKind.ROUND,
        spacing = 0.1f, hardness = 0.5f, flow = 1f, sizePressure = 0.3f,
        smoothing = 0.2f, builtin = true
    )

    val ALL: List<BrushSpec> = listOf(
        PENCIL, CRAYON, CHARCOAL,
        INK, MARKER, CALLIGRAPHY,
        SOFT, WATERCOLOR, DRY_BRUSH,
        AIRBRUSH, SPRAY,
        CANVAS, STONE,
        DOTTED, DASHES, STARS, SQUARES, STRIPES,
        PORES, FRECKLES, BLUSH, FINE_HAIR,
        GRASS, FOLIAGE, LEAVES, PINES, CLOUDS
    )

    fun byId(id: String): BrushSpec? = ALL.firstOrNull { it.id == id }
}
