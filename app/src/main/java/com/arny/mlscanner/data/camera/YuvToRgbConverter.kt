package com.arny.mlscanner.data.camera

import android.graphics.Bitmap
import android.media.Image
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import androidx.core.graphics.createBitmap

class YuvToRgbConverter(private val context: android.content.Context) {
    private var rs: RenderScript? = null
    private var scriptIntrinsicYuvToRGB: ScriptIntrinsicYuvToRGB? = null

    init {
        // Инициализация RenderScript
        rs = RenderScript.create(context)
        scriptIntrinsicYuvToRGB = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
    }

    fun yuvToRgb(image: Image, width: Int, height: Int): Bitmap {
        val yuvBytes = imageToByteArray(image)
        val inputAllocation = Allocation.createSized(
            rs, Element.U8(rs), yuvBytes.size
        )
        inputAllocation.copyFrom(yuvBytes)

        val outputBitmap = createBitmap(width, height)
        val outputAllocation = Allocation.createFromBitmap(rs, outputBitmap)

        scriptIntrinsicYuvToRGB?.setInput(inputAllocation)
        scriptIntrinsicYuvToRGB?.forEach(outputAllocation)
        outputAllocation.copyTo(outputBitmap)

        inputAllocation.destroy()
        outputAllocation.destroy()

        return outputBitmap
    }

    private fun imageToByteArray(image: Image): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uvSize = uBuffer.remaining()

        val nv21 = ByteArray(ySize + uvSize)

        yBuffer.get(nv21, 0, ySize)

        val uBytes = ByteArray(uvSize)
        uBuffer.get(uBytes)
        val vBytes = ByteArray(uvSize)
        vBuffer.get(vBytes)

        for (i in 0 until uvSize) {
            nv21[ySize + i] = vBytes[i]
        }
        for (i in 0 until uvSize) {
            nv21[ySize + uvSize + i] = uBytes[i]
        }

        return nv21
    }
}