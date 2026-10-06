package io.github.yuubiz.folirel.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.ui.theme.CustomPalette
import io.github.yuubiz.folirel.ui.theme.SeedColor

/**
 * What the colour picker is holding while it is open (0.9.3). The pointer position (hue, saturation, value) and the typed `#RRGGBB` are
 * two ways to say one colour; whichever was used last wins. [shown] is the colour of the preview and is always a valid colour: text that is
 * half typed or wrong does not change it (and so cannot break what is drawn), and only a valid text can be applied ([applyOrNull]).
 * Nothing is stored here; the settings are written only when the caller applies the result.
 */
internal class CustomColorDraft(initialSeed: Int) {
    private val initial = SeedColor.hsv(initialSeed)
    var hue by mutableFloatStateOf(initial.hue)
        private set
    var saturation by mutableFloatStateOf(initial.saturation)
        private set
    var value by mutableFloatStateOf(initial.value)
        private set
    var hexText by mutableStateOf(SeedColor.hex(initialSeed))
        private set

    /** The colour of the preview: the last valid colour, from the pointer or from the text. */
    var shown by mutableIntStateOf(AppSettings.opaque(initialSeed))
        private set

    /** The pointer moved on the saturation/value area. */
    fun movePointer(saturation: Float, value: Float) {
        this.saturation = saturation.coerceIn(0f, 1f)
        this.value = value.coerceIn(0f, 1f)
        fromPointer()
    }

    fun moveHue(hue: Float) {
        this.hue = hue.coerceIn(0f, 360f)
        fromPointer()
    }

    private fun fromPointer() {
        shown = SeedColor.fromHsv(hue, saturation, value)
        hexText = SeedColor.hex(shown)
    }

    /** The text field changed. A valid `#RRGGBB` moves the pointer and the preview; anything else only changes the text. */
    fun typeHex(text: String) {
        hexText = text
        val color = SeedColor.parse(text) ?: return
        shown = color
        val hsv = SeedColor.hsv(color)
        // Grey and black have no hue of their own: the slider stays where it was.
        if (hsv.saturation > 0f && hsv.value > 0f) hue = hsv.hue
        saturation = hsv.saturation
        value = hsv.value
    }

    /** The text is a colour that can be applied. */
    val valid: Boolean get() = SeedColor.parse(hexText) != null

    /** The colour to store, or null while the text is not a valid colour (nothing invalid is ever stored). */
    fun applyOrNull(): Int? = SeedColor.parse(hexText)

    companion object {
        val Saver: Saver<CustomColorDraft, Any> = listSaver(
            save = { listOf(it.hue, it.saturation, it.value, it.hexText, it.shown) },
            restore = {
                CustomColorDraft(it[4] as Int).also { draft ->
                    draft.hue = it[0] as Float
                    draft.saturation = it[1] as Float
                    draft.value = it[2] as Float
                    draft.hexText = it[3] as String
                }
            },
        )
    }
}

/** The line under the colour sets while Custom is chosen: the base colour as a swatch and in `#RRGGBB`, "Choose colour" and "Reset to default". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CustomColorRow(seed: Int, onPick: () -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().testTag("custom_color_row"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val label = stringResource(R.string.custom_seed_description, SeedColor.hex(seed))
            Box(
                Modifier
                    .size(40.dp)
                    .background(Color(seed), RoundedCornerShape(8.dp))
                    .border(1.dp, colors.outline, RoundedCornerShape(8.dp))
                    .semantics { contentDescription = label }
                    .testTag("custom_seed_swatch"),
            )
            Column {
                Text(stringResource(R.string.custom_base_color), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                Text(SeedColor.hex(seed), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("custom_seed_hex"))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPick, modifier = Modifier.testTag("custom_pick")) { Text(stringResource(R.string.custom_pick)) }
            TextButton(onClick = onReset, enabled = seed != AppSettings.DEFAULT_CUSTOM_SEED, modifier = Modifier.testTag("custom_reset")) {
                Text(stringResource(R.string.custom_reset))
            }
        }
    }
}

/**
 * The colour picker: a saturation/value square and a hue slider (the usual two-dimensional picker), a `#RRGGBB` field as a precise and
 * accessible alternative, and the existing small preview drawn with the scheme the colour would give, in the current light or dark mode.
 * Nothing is applied while it is moved: the app keeps its own colours, [onApply] gets the colour once, and Cancel, Back and a tap outside
 * apply nothing.
 */
@Composable
internal fun CustomColorDialog(initialSeed: Int, dark: Boolean, storedPercent: Int, onApply: (Int) -> Unit, onCancel: () -> Unit) {
    val draft = rememberSaveable(saver = CustomColorDraft.Saver) { CustomColorDraft(initialSeed) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.custom_picker_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // The preview alone is drawn with the draft colours; the rest of the app is not changed until Apply.
                MaterialTheme(colorScheme = CustomPalette.scheme(draft.shown, dark)) {
                    AppearancePreview(previewPercent = null, storedPercent = storedPercent)
                }
                SaturationValueArea(draft)
                HueSlider(draft)
                OutlinedTextField(
                    value = draft.hexText,
                    onValueChange = draft::typeHex,
                    label = { Text(stringResource(R.string.custom_hex_label)) },
                    singleLine = true,
                    isError = !draft.valid,
                    supportingText = { if (!draft.valid) Text(stringResource(R.string.custom_hex_invalid), modifier = Modifier.testTag("custom_hex_error")) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth().testTag("custom_hex_field"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { draft.applyOrNull()?.let(onApply) }, enabled = draft.valid, modifier = Modifier.testTag("custom_apply")) {
                Text(stringResource(R.string.custom_apply))
            }
        },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.testTag("custom_cancel")) { Text(stringResource(R.string.cancel)) } },
        modifier = Modifier.testTag("custom_picker"),
    )
}

@Composable
private fun SaturationValueArea(draft: CustomColorDraft) {
    var size by androidx.compose.runtime.remember { mutableStateOf(IntSize.Zero) }
    val hueColor = Color(SeedColor.fromHsv(draft.hue, 1f, 1f))
    val description = stringResource(R.string.custom_area_description)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .onSizeChanged { size = it }
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectTapGestures { p -> pick(p, size, draft) }
            }
            .pointerInput(Unit) {
                detectDragGestures(onDragStart = { p -> pick(p, size, draft) }) { change, _ ->
                    change.consume()
                    pick(change.position, size, draft)
                }
            }
            .testTag("custom_sv_area"),
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        // The thumb: a ring that shows on both light and dark colours.
        val centre = Offset(draft.saturation * this.size.width, (1f - draft.value) * this.size.height)
        drawCircle(Color.Black, radius = 11.dp.toPx(), center = centre, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4.dp.toPx()))
        drawCircle(Color.White, radius = 11.dp.toPx(), center = centre, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
    }
}

private fun pick(p: Offset, size: IntSize, draft: CustomColorDraft) {
    if (size.width <= 0 || size.height <= 0) return
    draft.movePointer(p.x / size.width, 1f - p.y / size.height)
}

@Composable
private fun HueSlider(draft: CustomColorDraft) {
    val rainbow = Brush.horizontalGradient((0..6).map { Color(SeedColor.fromHsv(it * 60f, 1f, 1f)) })
    val description = stringResource(R.string.custom_hue_description)
    Box(Modifier.fillMaxWidth().height(36.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(12.dp).background(rainbow, RoundedCornerShape(6.dp)))
        Slider(
            value = draft.hue,
            onValueChange = draft::moveHue,
            valueRange = 0f..360f,
            colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent, thumbColor = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }.testTag("custom_hue_slider"),
        )
    }
}
