package com.angussoftware.fueldashboard.ui.components

import com.angussoftware.fueldashboard.model.AgentConfig
import com.angussoftware.fueldashboard.model.AgentSettings
import com.angussoftware.fueldashboard.model.ProviderConfig
import com.angussoftware.fueldashboard.model.ProviderKind
import com.angussoftware.fueldashboard.model.SettingsSyncData
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import qrcode.QRCode
import qrcode.color.Colors

/**
 * Renders the settings-sync QR code to disk (under /tmp) and simulates how it is
 * scaled for display, so the output PNGs can be verified with an external QR
 * scanner (e.g. `python3 -c "import cv2 ..."`).
 *
 * The interesting comparison is the two display simulations: the "blurred" one
 * uses bilinear filtering (the old Compose default, FilterQuality.Low) and the
 * "fixed" one uses nearest-neighbour (FilterQuality.None). The former destroys
 * the sharp module edges when the large source bitmap is shrunk to the dialog
 * width, which is exactly why the code failed to scan.
 */
class QrCodeScannabilityTest {

    private val outDir = File("/tmp")

    /** A realistic payload: several providers with long API keys + agents + theme. */
    private fun realisticSyncData(): SettingsSyncData =
        SettingsSyncData(
            providers = listOf(
                ProviderConfig(
                    id = "openai-main",
                    kind = ProviderKind.OPENAI,
                    apiKey = "sk-proj-9aXbQ2mZ7hT4kLpR8wYcV1nD6fJ0sG3eH5uI2oP7qA4rB8tC1xW6yE9zK3mN5vL",
                    displayName = "OpenAI Production",
                    monthlyBudgetUsd = 250.0,
                ),
                ProviderConfig(
                    id = "anthropic-main",
                    kind = ProviderKind.ANTHROPIC,
                    apiKey = "sk-ant-api03-Kj8Hg2Lp9Qw7Er4Ty1Ui6Op3As5Df0Gh8Jk2Lz9Xc4Vb7Nm1Qw6Er3Ty5Ui8-Op2As",
                    displayName = "Anthropic Claude",
                    monthlyBudgetUsd = 300.0,
                ),
                ProviderConfig(
                    id = "zai-main",
                    kind = ProviderKind.ZAI,
                    apiKey = "zai-3f7b9c1d2e4a6b8c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c",
                    serverUrl = "https://api.z.ai",
                ),
                ProviderConfig(
                    id = "groq-fast",
                    kind = ProviderKind.GROQ,
                    apiKey = "gsk_AbCdEf12GhIjKl34MnOpQr56StUvWx78YzAbCd90EfGhIj12KlMnOp34QrStUv",
                ),
            ),
            themeMode = "SYSTEM",
            lightColorTheme = "Default",
            darkColorTheme = "Default",
            serverUrl = "https://fuel-dashboard.example.com:8322",
            agentSettings = AgentSettings(
                agents = listOf(
                    AgentConfig(id = "agent-coda", name = "Coda", command = "coda-acp", args = "--yolo"),
                    AgentConfig(id = "agent-junie", name = "Junie", command = "junie", args = "--headless"),
                ),
            ),
            junieBalance = 42.5,
            junieLicense = "JB-XXXX-YYYY-ZZZZ-1234",
            junieLastChecked = 1_733_000_000_000L,
        )

    /** Downscale [src] to [targetWidth] keeping aspect ratio, using the given interpolation hint. */
    private fun scaleTo(src: BufferedImage, targetWidth: Int, interpolation: Any): BufferedImage {
        val ratio = targetWidth.toDouble() / src.width
        val targetHeight = (src.height * ratio).toInt().coerceAtLeast(1)
        val dst = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        val g = dst.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation)
        g.drawImage(src, 0, 0, targetWidth, targetHeight, null)
        g.dispose()
        return dst
    }

    @Test
    fun writesScannableQrPngsToTmp() {
        val syncData = realisticSyncData()
        val qrData = syncData.toQrData()
        val capacity = estimateQrCapacity(qrData)
        val density = minimumInformationDensity(qrData)

        println("[QR] payload bytes=${qrData.length} version=${capacity.version} density=$density tooLarge=${capacity.tooLarge}")
        assertTrue(!capacity.tooLarge, "payload unexpectedly too large for a QR code")
        // The fix must keep the version well below the max (40 = 177x177 modules).
        assertTrue(capacity.version in 1..30, "QR version ${capacity.version} is too dense to scan")

        // 1. FIXED production render (minimal version + module-based quiet zone).
        val debugPng = renderQrPngBytes(qrData, moduleSize = 20, margin = 4, informationDensity = density)
        assertNotNull(debugPng, "renderQrPngBytes returned null")
        File(outDir, "qr_debug.png").writeBytes(debugPng)
        val fixedSource = ImageIO.read(ByteArrayInputStream(debugPng))
        println("[QR] fixed raw png size=${fixedSource.width}x${fixedSource.height}")

        // 2. OLD/buggy render: auto density -> jumps to version 40 (177x177), tiny quiet zone.
        val oldPng = QRCode.ofSquares()
            .withSize(20)
            .withColor(Colors.BLACK)
            .withBackgroundColor(Colors.WHITE)
            .withMargin(4)
            .build(qrData)
            .render()
            .getBytes("PNG")
        File(outDir, "qr_old_v40.png").writeBytes(oldPng)
        val oldSource = ImageIO.read(ByteArrayInputStream(oldPng))
        println("[QR] old raw png size=${oldSource.width}x${oldSource.height}")

        // 3. Display simulations at a typical dialog width.
        val displayWidth = 280
        // Old behaviour: dense v40 shrunk with bilinear filtering (Compose FilterQuality.Low).
        ImageIO.write(
            scaleTo(oldSource, displayWidth, RenderingHints.VALUE_INTERPOLATION_BILINEAR),
            "PNG",
            File(outDir, "qr_display_old.png"),
        )
        // New behaviour: low version shrunk with nearest-neighbour (FilterQuality.None).
        ImageIO.write(
            scaleTo(fixedSource, displayWidth, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR),
            "PNG",
            File(outDir, "qr_display_fixed.png"),
        )

        assertTrue(File(outDir, "qr_debug.png").length() > 0)
        assertTrue(File(outDir, "qr_display_fixed.png").length() > 0)
    }
}
