package com.arny.mlscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.data.preprocessing.NativeScan
import com.arny.mlscanner.domain.models.OcrEngineType
import com.arny.mlscanner.domain.models.ScanSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Прогон деворпа на устройстве на тех же 12 страницах, что в Python-эталоне.
 *
 * Страницы лежат в assets ТЕСТОВОГО apk (app/src/androidTest/assets/dewarp/), поэтому
 * не нужны ни разрешения на хранилище, ни сеть: scoped storage не даёт приложению читать
 * ни Download, ни собственный external-каталог.
 *
 * Результат — строки в logcat с тегом DewarpTest.
 */
@RunWith(AndroidJUnit4::class)
class DewarpInstrumentedTest {

    private val tag = "DewarpTest"

    // Independent consumer of the wire format: two remaps, with shifts in output-row units.
    private fun replayField(bytes: ByteArray, w: Int, h: Int, field: FloatArray): ByteArray {
        val outH = field[1].toInt()
        val source = Mat(h, w, CvType.CV_8UC1)
        val mapX = Mat(outH, w, CvType.CV_32FC1)
        val mapY = Mat(outH, w, CvType.CV_32FC1)
        val affine = Mat()
        val refined = Mat()
        try {
            source.put(0, 0, bytes)
            val xs = FloatArray(w * outH) { (it % w).toFloat() }
            val ys = FloatArray(xs.size) {
                val x = it % w
                val y = it / w
                (field[2 + x].toDouble() + y *
                    (field[2 + w + x].toDouble() - field[2 + x].toDouble()) /
                    field[2 + 2 * w].toDouble()).toFloat()
            }
            mapX.put(0, 0, xs)
            mapY.put(0, 0, ys)
            Imgproc.remap(source, affine, mapX, mapY, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
            val hasResidual = (0 until w).any { field[3 + 2 * w + it] != 0f }
            val result = if (hasResidual) {
                for (i in ys.indices) ys[i] = (i / w + field[3 + 2 * w + i % w].toDouble()).toFloat()
                mapY.put(0, 0, ys)
                Imgproc.remap(affine, refined, mapX, mapY, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
                refined
            } else affine
            return ByteArray(w * outH).also { result.get(0, 0, it) }
        } finally {
            source.release()
            mapX.release()
            mapY.release()
            affine.release()
            refined.release()
        }
    }

    @Test
    fun suppliedFieldPreservesScaleAndResidualDirection() {
        assertTrue(OpenCVLoader.initLocal() || OpenCVLoader.initDebug())
        assertTrue(NativeScan.isAvailable)
        val w = 8
        val h = 20
        val outH = 8
        val input = ByteArray(w * h) { it.toByte() }
        val field = FloatArray(3 + 3 * w)
        field[0] = w.toFloat()
        field[1] = outH.toFloat()
        field[2 + 2 * w] = 8f // Deliberately not mean(b - t) == 16.
        for (x in 0 until w) {
            field[2 + x] = 1f
            field[2 + w + x] = 17f
            field[3 + 2 * w + x] = if (x % 2 == 0) 1f else -1f
        }
        val expected = ByteArray(w * outH) {
            val x = it % w
            val shiftedY = (it / w + if (x % 2 == 0) 1 else -1).coerceIn(0, outH - 1)
            ((1 + 2 * shiftedY) * w + x).toByte()
        }
        val output = NativeScan.dewarpApply(input, w, h, field)
        assertNotNull(output)
        assertArrayEquals("integer-coordinate analytic mapping", expected, output)
    }

    @Test
    fun sidewaysPageIsRotatedBack() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assertTrue(OpenCVLoader.initLocal() || OpenCVLoader.initDebug())
        val preprocessor = ImagePreprocessor()
        val settings = ScanSettings()
        assertTrue("auto rotation must be on by default", settings.autoRotateEnabled)

        // dewarp_test.png (1262x1639) stored rotated 90 clockwise => 1639x1262.
        val bitmap = assets.open("rotated/dewarp_test_rot90.png").use { BitmapFactory.decodeStream(it) }
            ?: throw AssertionError("cannot decode the rotated page")
        assertEquals(1639, bitmap.width)
        assertEquals(1262, bitmap.height)

        val out = preprocessor.prepareBaseImage(bitmap, settings)
        Log.i(tag, "rotated page 1639x1262 -> ${out.width}x${out.height}")
        assertEquals("page must be turned upright (width back to 1262)", 1262, out.width)
        assertEquals("and straightened like its upright original", 1533, out.height)
    }

    @Test
    fun uprightPagesAreNotRotated() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assertTrue(OpenCVLoader.initLocal() || OpenCVLoader.initDebug())
        val preprocessor = ImagePreprocessor()
        val names = assets.list("dewarp")?.filter { it.endsWith(".png") }?.sorted() ?: emptyList()
        assertEquals(12, names.size)
        // Dewarp never changes the width, so a width change would mean a wrong rotation.
        for (name in names) {
            val bitmap = assets.open("dewarp/$name").use { BitmapFactory.decodeStream(it) }
                ?: throw AssertionError("cannot decode $name")
            val out = preprocessor.prepareBaseImage(bitmap, ScanSettings())
            assertEquals("$name must not be rotated", bitmap.width, out.width)
        }
    }

    @Test
    fun photoOnBackgroundIsCropped() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        assertTrue(OpenCVLoader.initLocal() || OpenCVLoader.initDebug())
        val preprocessor = ImagePreprocessor()
        val settings = ScanSettings()
        val outDir = java.io.File(instrumentation.targetContext.filesDir, "processed")
        outDir.mkdirs()

        val names = assets.list("photo")?.filter { it.endsWith(".png") }?.sorted() ?: emptyList()
        assertTrue("no photo assets", names.isNotEmpty())
        for (name in names) {
            val bitmap = assets.open("photo/$name").use { BitmapFactory.decodeStream(it) }
                ?: throw AssertionError("cannot decode $name")
            assertEquals(1600, bitmap.width)
            assertEquals(2100, bitmap.height)

            val out = preprocessor.prepareBaseImage(bitmap, settings)
            Log.i(tag, "crop $name 1600x2100 -> ${out.width}x${out.height}")
            // The pages occupy 60-80 % of the frame, so cropping must fire and drop the grey
            // background; they are portrait, so the result must stay portrait.
            assertTrue("$name must be cropped", out.width < 1560 && out.height < 2050)
            assertTrue("$name must stay portrait", out.height > out.width)

            java.io.FileOutputStream(java.io.File(outDir, name)).use { fos ->
                out.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            bitmap.recycle()
        }
    }

    @Test
    fun dewarpAppliesOnDefaultTesseractPath() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assertTrue(OpenCVLoader.initLocal() || OpenCVLoader.initDebug())
        val preprocessor = ImagePreprocessor()
        val settings = ScanSettings() // Default engine type is HYBRID (smart: Huawei → Tesseract).
        assertEquals("default engine", OcrEngineType.HYBRID, settings.engineType)

        val curved = prepareForTesseractSize(preprocessor, settings, assets, "dewarp_test.png")
        val flat = prepareForTesseractSize(preprocessor, settings, assets, "clean_scan.png")
        assertTrue(
            "prepareForTesseract must straighten the curved page, got ${curved.second}",
            curved.second < curved.first
        )
        assertEquals("flat page must keep its height", flat.first, flat.second)
    }

    private fun prepareForTesseractSize(
        preprocessor: ImagePreprocessor,
        settings: ScanSettings,
        assets: android.content.res.AssetManager,
        name: String,
    ): Pair<Int, Int> {
        val bitmap = assets.open("dewarp/$name").use { BitmapFactory.decodeStream(it) }
            ?: throw AssertionError("cannot decode $name")
        val inputHeight = bitmap.height
        val out = preprocessor.prepareForTesseract(bitmap, settings)
        Log.i(tag, "prepareForTesseract $name ${bitmap.width}x$inputHeight -> ${out.width}x${out.height}")
        return inputHeight to out.height
    }

    @Test
    fun dewarpRunsOnDevicePages() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testAssets = instrumentation.context.assets
        assertTrue("OpenCV must initialise", OpenCVLoader.initLocal() || OpenCVLoader.initDebug())

        val names = testAssets.list("dewarp")?.filter { it.endsWith(".png") }?.sorted() ?: emptyList()
        assertEquals("fixture count", 12, names.size)
        val expectedHeights = mapOf("dewarp_test.png" to 1533, "dewarp_test_asymmetric.png" to 1553)
        assertTrue("missing curved fixtures", names.containsAll(expectedHeights.keys))
        assertTrue("native library must load", NativeScan.isAvailable)

        Log.i(tag, "native: ${NativeScan.buildInfo()}")
        val preprocessor = ImagePreprocessor()
        val settings = ScanSettings()
        var applied = 0

        for (name in names) {
            val bitmap: Bitmap = testAssets.open("dewarp/$name").use { BitmapFactory.decodeStream(it) }
                ?: throw AssertionError("cannot decode $name")

            val rgba = Mat()
            Utils.bitmapToMat(bitmap, rgba)
            val gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val w = gray.cols()
            val h = gray.rows()
            val bytes = ByteArray(w * h)
            gray.get(0, 0, bytes)
            rgba.release()
            gray.release()

            val t0 = System.nanoTime()
            val field = NativeScan.dewarpField(bytes, w, h)
            val fieldMs = (System.nanoTime() - t0) / 1_000_000
            assertEquals("$name decision", name in expectedHeights, field != null)
            if (field != null) {
                assertEquals("$name field layout", 3 + 3 * w, field.size)
                assertTrue("$name finite field", field.all { it.isFinite() })
                assertEquals("$name width", w, field[0].toInt())
                assertEquals("$name height", expectedHeights.getValue(name), field[1].toInt())
                assertTrue("$name scale denominator", field[2 + 2 * w] > 0f)
                val output = NativeScan.dewarpApply(bytes, w, h, field)
                assertNotNull("$name JNI apply", output)
                assertEquals("$name byte count", w * field[1].toInt(), output!!.size)
                assertArrayEquals("$name JNI versus independent remap", replayField(bytes, w, h, field), output)
            }

            val t1 = System.nanoTime()
            val processed = preprocessor.prepareBaseImage(bitmap, settings)
            val pipelineMs = (System.nanoTime() - t1) / 1_000_000
            assertEquals("$name pipeline width", w, processed.width)
            assertEquals("$name pipeline height", expectedHeights[name] ?: h, processed.height)

            if (field != null) applied++
            Log.i(
                tag,
                "%s in=%dx%d field=%s out=%s fieldMs=%d pipelineMs=%d".format(
                    name, w, h,
                    if (field == null) "none" else "${field[0].toInt()}x${field[1].toInt()}",
                    "${processed.width}x${processed.height}", fieldMs, pipelineMs
                )
            )
            bitmap.recycle()
        }
        Log.i(tag, "TOTAL applied=$applied/${names.size}")
        assertEquals("applied count", 2, applied)
    }
}
