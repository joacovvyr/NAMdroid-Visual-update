package com.namdroid.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.namdroid.app.R
import kotlin.math.roundToInt

// Shared geometry keeps every non-expression effect aligned across screen sizes.
private val DroidTitleFont = FontFamily(Font(R.font.droid_wordmark, FontWeight.Bold))

private data class DroidStyle(val title: String, val accent: Color)

private fun styleFor(type: BlockType) = when (type) {
    BlockType.COMPRESSOR -> DroidStyle("COMP-DROID", Color(0xFFFFD166))
    BlockType.DRIVE -> DroidStyle("DRIVE-DROID", Color(0xFFFF8A3D))
    BlockType.EQ -> DroidStyle("EQ-DROID", Color(0xFFD59AFF))
    BlockType.CHORUS -> DroidStyle("CHORUS-DROID", Color(0xFF7BE7F6))
    BlockType.AUTO_WAH -> DroidStyle("AUTO-WAH-DROID", Color(0xFFFFD85A))
    BlockType.TREMOLO -> DroidStyle("TREMOLO-DROID", Color(0xFFFF6C78))
    BlockType.DETUNE -> DroidStyle("DETUNE-DROID", Color(0xFF65B7FF))
    BlockType.MIKU -> DroidStyle("MIKU-DROID", Color(0xFF55DDE0))
    else -> DroidStyle(type.shortLabel + "-DROID", Color.White)
}

private fun chassisFor(type: BlockType) = when (type) {
    BlockType.COMPRESSOR -> R.drawable.droid_chassis_comp
    BlockType.DRIVE -> R.drawable.droid_chassis_drive
    BlockType.EQ -> R.drawable.droid_chassis_eq
    BlockType.CHORUS -> R.drawable.droid_chassis_chorus
    BlockType.AUTO_WAH -> R.drawable.droid_chassis_auto_wah
    BlockType.TREMOLO -> R.drawable.droid_chassis_tremolo
    BlockType.DETUNE -> R.drawable.droid_chassis_detune
    BlockType.MIKU -> R.drawable.droid_chassis_detune
    else -> R.drawable.droid_chassis_gate
}

@Composable
internal fun StandardDroidEditor(
    modifier: Modifier,
    block: PedalBlock,
    change: (ParameterSpec, Float) -> Unit,
    toggle: () -> Unit,
) {
    var advanced by rememberSaveable(block.id) { mutableStateOf(false) }
    val style = remember(block.type) { styleFor(block.type) }
    if (advanced) {
        FamilyAdvancedEditor(modifier, block, style, change) { advanced = false }
        return
    }

    val pulse = rememberInfiniteTransition(label = "family-led").animateFloat(
        .35f, 1f,
        infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "family-led-alpha",
    ).value
    val switchDepth by animateFloatAsState(
        if (block.enabled) 3f else 0f,
        spring(stiffness = Spring.StiffnessMedium),
        label = "family-switch-depth",
    )
    val controls = remember(block.type) { block.type.parameters }

    Box(modifier, contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.pedal_editor_background), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        BoxWithConstraints(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val pedalWidth = minOf(maxWidth, maxHeight * (16f / 9f))
            val pedalHeight = pedalWidth * (9f / 16f)
            Box(Modifier.size(pedalWidth, pedalHeight)) {
                Image(
                    painterResource(chassisFor(block.type)), null,
                    Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
                )
                FamilyModeButton(
                    "CONTROLES", { advanced = true },
                    Modifier.align(Alignment.TopEnd).padding(end = pedalWidth * .022f, top = pedalHeight * .022f),
                    style.accent,
                )

                if (block.type == BlockType.DETUNE) {
                    DetuneFace(block, change, style, pedalWidth, pedalHeight)
                } else {
                    val rows = if (controls.size <= 7) listOf(controls) else controls.chunked(5)
                    rows.forEachIndexed { rowIndex, row ->
                        val y = if (rows.size == 1) .145f else if (rowIndex == 0) .075f else .405f
                        val regionStart = if (rows.size == 1) .075f else .105f
                        val regionEnd = if (rows.size == 1) .925f else .78f
                        val step = (regionEnd - regionStart) / row.size
                        val controlWidth = pedalWidth * minOf(.132f, step * .92f)
                        val controlHeight = pedalHeight * if (rows.size == 1) .39f else .31f
                        row.forEachIndexed { index, spec ->
                            val center = regionStart + step * (index + .5f)
                            FamilyFrontKnob(
                                spec, frontLabel(spec), block.parameters[spec.key] ?: spec.default,
                                style.accent, change,
                                Modifier.align(Alignment.TopStart)
                                    .offset(x = pedalWidth * center - controlWidth / 2f, y = pedalHeight * y)
                                    .size(controlWidth, controlHeight),
                            )
                        }
                    }
                }

                Canvas(
                    Modifier.align(Alignment.TopStart)
                        .offset(x = pedalWidth * .854f, y = pedalHeight * .555f).size(12.dp),
                ) {
                    drawCircle(style.accent.copy(alpha = if (block.enabled) pulse else .14f))
                    if (block.enabled) drawCircle(Color.White.copy(alpha = .78f), radius = size.minDimension * .20f)
                }
                Box(
                    Modifier.align(Alignment.TopStart)
                        .offset(x = pedalWidth * .822f, y = pedalHeight * .61f)
                        .size(pedalWidth * .088f).clickable(onClick = toggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(painterResource(R.drawable.delay_footswitch_base), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    Image(
                        painterResource(R.drawable.delay_footswitch_cap), "Activar o desactivar ${block.type.label}",
                        Modifier.fillMaxSize().graphicsLayer {
                            translationY = switchDepth
                            scaleX = if (block.enabled) .97f else 1f
                            scaleY = if (block.enabled) .97f else 1f
                        }, contentScale = ContentScale.Fit,
                    )
                }
                DroidWordmark(
                    style.title,
                    Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = pedalHeight * .055f)
                        .width(pedalWidth * .62f)
                        .height(pedalHeight * .115f),
                    if (style.title.length > 13) 22.sp else 27.sp,
                )
            }
        }
    }
}

@Composable
private fun DetuneFace(
    block: PedalBlock,
    change: (ParameterSpec, Float) -> Unit,
    style: DroidStyle,
    pedalWidth: androidx.compose.ui.unit.Dp,
    pedalHeight: androidx.compose.ui.unit.Dp,
) {
    val drop = block.type.parameters.first { it.key == "drop" }
    val step = (block.parameters[drop.key] ?: drop.default).roundToInt().coerceIn(-2, 8)
    val semitones = when {
        step < 0 -> -step
        step >= 8 -> -12
        else -> -step
    }
    val knobs = block.type.parameters.filterNot { it.key == "drop" }
    val knobWidth = pedalWidth * .14f
    val knobHeight = pedalHeight * .34f
    knobs.forEachIndexed { index, spec ->
        val center = .17f + index * .17f
        FamilyFrontKnob(
            spec, frontLabel(spec), block.parameters[spec.key] ?: spec.default,
            style.accent, change,
            Modifier.offset(x = pedalWidth * center - knobWidth / 2f, y = pedalHeight * .10f)
                .size(knobWidth, knobHeight),
        )
    }
    Column(
        Modifier.offset(x = pedalWidth * .07f, y = pedalHeight * .47f)
            .size(pedalWidth * .63f, pedalHeight * .21f)
            .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .28f))
            .border(1.dp, style.accent.copy(alpha = .38f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val modeText = when {
            semitones > 0 -> "UP  +$semitones SEMITONES"
            semitones < 0 -> "DROP  $semitones SEMITONES"
            else -> "STANDARD  0 SEMITONES"
        }
        Text(modeText, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            (-2..8).forEach { position ->
                val positionSemitones = when {
                    position < 0 -> -position
                    position >= 8 -> -12
                    else -> -position
                }
                val positionLabel = if (positionSemitones > 0) "+$positionSemitones" else positionSemitones.toString()
                Column(
                    Modifier.clickable { change(drop, position.toFloat()) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Canvas(Modifier.size(10.dp)) {
                        drawCircle(if (position == step) style.accent else Color.Black.copy(alpha = .58f))
                        if (position == step) drawCircle(Color.White, radius = size.minDimension * .20f)
                    }
                    Text(positionLabel, color = if (position == step) Color.White else Color.White.copy(alpha = .58f), fontSize = 5.sp)
                }
            }
        }
    }
    DetuneSelectorKnob(
        selector = step,
        tint = style.accent,
        change = { change(drop, it.toFloat()) },
        modifier = Modifier.offset(x = pedalWidth * .735f, y = pedalHeight * .39f)
            .size(pedalWidth * .13f, pedalHeight * .22f),
    )
}

@Composable
private fun DetuneSelectorKnob(
    selector: Int,
    tint: Color,
    change: (Int) -> Unit,
    modifier: Modifier,
) {
    val currentSelector by rememberUpdatedState(selector)
    val fraction = ((selector + 2f) / 10f).coerceIn(0f, 1f)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FamilyKnob(
            fraction,
            tint,
            Modifier.fillMaxWidth(.72f).weight(1f).pointerInput(Unit) {
                var dragStart = currentSelector.toFloat()
                var dragDistance = 0f
                detectVerticalDragGestures(
                    onDragStart = {
                        dragStart = currentSelector.toFloat()
                        dragDistance = 0f
                    },
                ) { event, amount ->
                    event.consume()
                    dragDistance += amount
                    val next = (dragStart - dragDistance / 24.dp.toPx())
                        .roundToInt().coerceIn(-2, 8)
                    change(next)
                }
            },
        )
        Text("TUNING", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FamilyFrontKnob(
    spec: ParameterSpec,
    label: String,
    value: Float,
    tint: Color,
    change: (ParameterSpec, Float) -> Unit,
    modifier: Modifier,
) {
    val currentValue by rememberUpdatedState(value)
    val fraction = ((value - spec.range.start) / (spec.range.endInclusive - spec.range.start)).coerceIn(0f, 1f)
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val knobSize = minOf(maxWidth * .62f, maxHeight * .53f)
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatValue(value, spec.unit), color = tint, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(1.dp))
            FamilyKnob(
                fraction, tint,
                Modifier.size(knobSize).pointerInput(spec.key) {
                    detectVerticalDragGestures { event, amount ->
                        event.consume()
                        change(spec, (currentValue - amount / 210.dp.toPx() *
                            (spec.range.endInclusive - spec.range.start)).coerceIn(spec.range))
                    }
                },
            )
            Spacer(Modifier.height(1.dp))
            Text(label, color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FamilyKnob(fraction: Float, tint: Color, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(Color.Black.copy(alpha = .56f), 135f, 270f, false, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round))
            drawArc(tint, 135f, fraction.coerceIn(0f, 1f) * 270f, false, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round))
        }
        Image(
            painterResource(R.drawable.knob_master), null,
            Modifier.fillMaxSize(.86f).graphicsLayer { rotationZ = -135f + fraction.coerceIn(0f, 1f) * 270f },
            contentScale = ContentScale.Fit,
        )
    }
}

@Composable
private fun FamilyModeButton(label: String, click: () -> Unit, modifier: Modifier, tint: Color) {
    Surface(
        onClick = click, modifier = modifier.height(38.dp), color = Color(0xD9141A20),
        shape = RoundedCornerShape(7.dp), border = androidx.compose.foundation.BorderStroke(1.dp, tint),
    ) {
        Box(Modifier.padding(horizontal = 15.dp), contentAlignment = Alignment.Center) {
            Text(label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun FamilyAdvancedEditor(
    modifier: Modifier,
    block: PedalBlock,
    style: DroidStyle,
    change: (ParameterSpec, Float) -> Unit,
    close: () -> Unit,
) {
    Box(modifier) {
        Image(painterResource(R.drawable.pedal_editor_background), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            FamilyModeButton("VOLVER AL PEDAL", close, Modifier.align(Alignment.End), style.accent)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(170.dp), modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                items(block.type.parameters, key = { it.key }) { spec ->
                    val value = block.parameters[spec.key] ?: spec.default
                    Column(
                        Modifier.background(Color(0xE8181D23), RoundedCornerShape(9.dp)).padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(spec.label.uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FamilyKnob(
                                ((value - spec.range.start) / (spec.range.endInclusive - spec.range.start)).coerceIn(0f, 1f),
                                style.accent, Modifier.size(58.dp),
                            )
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(formatValue(value, spec.unit), color = style.accent, fontWeight = FontWeight.Bold)
                                Slider(
                                    value,
                                    { change(spec, if (block.type == BlockType.DETUNE && spec.key == "drop") it.roundToInt().toFloat() else it) },
                                    valueRange = spec.range,
                                    steps = if (block.type == BlockType.DETUNE && spec.key == "drop") 9 else 0,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CabDroidEditor(
    modifier: Modifier,
    block: PedalBlock,
    change: (ParameterSpec, Float) -> Unit,
    toggle: () -> Unit,
    pickIr: () -> Unit,
) {
    val accent = Color(0xFF65D9E4)
    val pulse = rememberInfiniteTransition(label = "cab-led").animateFloat(
        .35f, 1f, infiniteRepeatable(tween(640), RepeatMode.Reverse), label = "cab-led-alpha",
    ).value
    val switchDepth by animateFloatAsState(if (block.enabled) 3f else 0f, label = "cab-switch-depth")
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.pedal_editor_background), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            val width = minOf(maxWidth, maxHeight * (16f / 9f))
            val height = width * (9f / 16f)
            Box(Modifier.size(width, height)) {
                Image(
                    painterResource(R.drawable.droid_chassis_cab), null,
                    Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
                )
                Image(
                    painterResource(R.drawable.cab), null,
                    Modifier.offset(x = width * .08f, y = height * .34f).size(width * .56f, height * .47f),
                    contentScale = ContentScale.Fit,
                )
                val controlWidth = width * .15f
                val controlHeight = height * .34f
                block.type.parameters.forEachIndexed { index, spec ->
                    FamilyFrontKnob(
                        spec, frontLabel(spec), block.parameters[spec.key] ?: spec.default, accent, change,
                        Modifier.offset(x = width * (.18f + index * .20f) - controlWidth / 2f, y = height * .07f)
                            .size(controlWidth, controlHeight),
                    )
                }
                Surface(
                    onClick = pickIr,
                    modifier = Modifier.offset(x = width * .675f, y = height * .22f).size(width * .245f, height * .20f),
                    color = Color(0xDD05090C), shape = RoundedCornerShape(7.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, accent),
                ) {
                    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("IR MODEL", color = Color.White.copy(alpha = .7f), fontSize = 7.sp)
                        Text(block.assetName?.takeIf { it.isNotBlank() } ?: "CARGAR CABINET IR", color = accent,
                            fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Canvas(Modifier.offset(x = width * .80f, y = height * .49f).size(12.dp)) {
                    drawCircle(accent.copy(alpha = if (block.enabled) pulse else .14f))
                }
                Box(
                    Modifier.offset(x = width * .765f, y = height * .55f).size(width * .09f).clickable(onClick = toggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(painterResource(R.drawable.delay_footswitch_base), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    Image(painterResource(R.drawable.delay_footswitch_cap), "Activar o desactivar cabinet",
                        Modifier.fillMaxSize().graphicsLayer { translationY = switchDepth }, contentScale = ContentScale.Fit)
                }
                DroidWordmark(
                    "CAB-DROID",
                    Modifier.align(Alignment.BottomCenter)
                        .padding(bottom = height * .055f)
                        .width(width * .46f)
                        .height(height * .115f),
                    27.sp,
                )
            }
        }
    }
}

@Composable
internal fun DroidWordmark(
    text: String,
    modifier: Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = Color.White,
            fontSize = fontSize,
            lineHeight = fontSize,
            fontFamily = DroidTitleFont,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.1.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}

private fun frontLabel(spec: ParameterSpec): String = when (spec.key) {
    "lowfreq" -> "LOW FREQ"
    "midfreq" -> "MID FREQ"
    "highfreq" -> "HIGH FREQ"
    "minfreq" -> "MIN FREQ"
    "maxfreq" -> "MAX FREQ"
    "threshold" -> "THRESHOLD"
    "character" -> "CHARACTER"
    "hysteresis" -> "HYSTERESIS"
    "sensitivity" -> "SENSITIVITY"
    "symmetry" -> "SYMMETRY"
    "direction" -> "DIRECTION"
    "window" -> "TRACKING"
    "tight" -> "LOW CUT"
    "knee" -> "KNEE"
    "makeup" -> "MAKEUP"
    else -> spec.label.uppercase()
}

private fun formatValue(value: Float, unit: String): String = "%.1f%s".format(
    java.util.Locale.US, value, if (unit.isBlank()) "" else " $unit",
)
