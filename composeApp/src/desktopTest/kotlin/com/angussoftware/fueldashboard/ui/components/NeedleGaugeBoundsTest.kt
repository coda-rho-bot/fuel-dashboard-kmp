package com.angussoftware.fueldashboard.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Rasterization bounds test (review 1834 offer): draws the needle gauge's
 * arc-track geometry into an ImageBitmap with padding above the gauge canvas
 * and asserts no ink lands above it — the exact defect class where a bad
 * topLeft pushed the arc crown through the provider-name label.
 */
class NeedleGaugeBoundsTest {

    @Test
    fun gaugeArc_noInkAboveCanvas() {
        val w = 144  // px, 200dp tile proportion
        val h = 44   // production gauge canvas height
        val pad = 12 // probe padding above the gauge canvas
        val bitmap = ImageBitmap(w, h + pad)
        val canvas = Canvas(bitmap)
        val scope = CanvasDrawScope()
        val size = Size(w.toFloat(), h.toFloat())

        scope.draw(Density(1f), LayoutDirection.Ltr, canvas, size) {
            // Production geometry (mirror of NeedleGauge's draw body), offset
            // down by `pad` so ink ABOVE the gauge canvas is observable
            // instead of silently clipped.
            translate(0f, pad.toFloat()) {
                val r = size.height * 0.64f
                val arcSize = Size(r * 2f, r * 2f)
                val halfStroke = size.height * 0.10f / 2f
                val topLeft = Offset((size.width - arcSize.width) / 2f, halfStroke)
                drawArc(
                    color = Color.Red,
                    startAngle = START_ANGLE_DEG,
                    sweepAngle = SWEEP_DEG,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = size.height * 0.10f),
                )
            }
        }

        // No ink in the pad rows above the gauge canvas — flush-top keeps the
        // crown's centered-stroke bleed inside the canvas.
        var padInk = 0
        val pixels = bitmap.toPixelMap()
        for (y in 0 until pad) {
            for (x in 0 until w) {
                if (pixels[x, y].alpha > 0f) padInk++
            }
        }
        assertTrue(padInk == 0, "arc ink above the gauge canvas ($padInk px in pad rows) — topLeft displacement defect")
    }
}
