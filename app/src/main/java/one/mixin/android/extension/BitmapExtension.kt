package one.mixin.android.extension

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import androidx.annotation.IntRange
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import one.mixin.android.crypto.Base64
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.EnumMap
import java.util.EnumSet

fun Bitmap.toBytes(): ByteArray {
    ByteArrayOutputStream().use { stream ->
        compress(Bitmap.CompressFormat.JPEG, 100, stream)
        return stream.toByteArray()
    }
}

fun Bitmap.toPNGBytes(): ByteArray {
    ByteArrayOutputStream().use { stream ->
        compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}

fun Bitmap.saveQRCode(ctx: Context, name: String) {
    try {
        ByteArrayOutputStream().use { bos ->
            compress(Bitmap.CompressFormat.PNG, 100, bos)
            FileOutputStream(ctx.getQRCodePath(name)).use { fos ->
                fos.write(bos.toByteArray())
                fos.flush()
            }
        }
    } catch (ignored: Exception) {
    }
}

fun Bitmap.saveGroupAvatar(ctx: Context, name: String) {
    try {
        ByteArrayOutputStream().use { bos ->
            compress(Bitmap.CompressFormat.PNG, 100, bos)
            FileOutputStream(ctx.getGroupAvatarPath(name)).use { fos ->
                fos.write(bos.toByteArray())
                fos.flush()
            }
        }
    } catch (ignored: Exception) {
    }
}

@Throws(IOException::class)
fun Bitmap.save(file: File) {
    ByteArrayOutputStream().use { bos ->
        compress(Bitmap.CompressFormat.PNG, 100, bos)
        FileOutputStream(file).use { fos ->
            fos.write(bos.toByteArray())
            fos.flush()
        }
    }
}

fun Bitmap.decodeQR(): String? {
    val width = width
    val height = height
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return decodeLuminanceSource(RGBLuminanceSource(width, height, pixels))
}

private fun decodeLuminanceSource(source: LuminanceSource): String? {
    val binaryBitmap = BinaryBitmap(GlobalHistogramBinarizer(source))
    val reader = QRCodeReader()
    val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
    hints[DecodeHintType.TRY_HARDER] = true
    hints[DecodeHintType.POSSIBLE_FORMATS] = EnumSet.allOf(BarcodeFormat::class.java)

    val results = ArrayList<Result>(1)
    var readException: ReaderException? = null
    try {
        val hintsPure = EnumMap<DecodeHintType, Any>(hints)
        hintsPure[DecodeHintType.PURE_BARCODE] = true
        val theResult = reader.decode(binaryBitmap, hintsPure)
        if (theResult != null) {
            results.add(theResult)
        }
    } catch (e: ReaderException) {
        readException = e
    }

    if (results.isEmpty()) {
        try {
            val hybridBitmap = BinaryBitmap(HybridBinarizer(source))
            val theResult = reader.decode(hybridBitmap, hints)
            if (theResult != null) {
                results.add(theResult)
            }
        } catch (e: ReaderException) {
            readException = e
        }
    }

    if (results.isEmpty()) {
        readException?.printStackTrace()
        return null
    }

    return results[0].text
}

fun Bitmap.maxSizeScale(maxWidth: Int, maxHeight: Int): Bitmap {
    if (maxHeight > 0 && maxWidth > 0) {
        val width = this.width
        val height = this.height
        val ratioBitmap = width.toFloat() / height.toFloat()
        val ratioMax = maxWidth.toFloat() / maxHeight.toFloat()

        var finalWidth = maxWidth
        var finalHeight = maxHeight
        if (ratioMax > ratioBitmap) {
            finalWidth = (maxHeight.toFloat() * ratioBitmap).toInt()
        } else {
            finalHeight = (maxWidth.toFloat() / ratioBitmap).toInt()
        }
        return Bitmap.createScaledBitmap(this, finalWidth, finalHeight, true)
    } else {
        return this
    }
}

fun Bitmap.base64Encode(format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG): String? {
    var result: String? = null
    var baos: ByteArrayOutputStream? = null
    try {
        if (isRecycled) return null
        baos = ByteArrayOutputStream()
        compress(format, 100, baos)
        baos.flush()
        baos.close()
        val bitmapBytes = baos.toByteArray()
        result = Base64.encodeBytes(bitmapBytes)
    } catch (e: IOException) {
        Timber.e(e)
    } finally {
        try {
            if (baos != null) {
                baos.flush()
                baos.close()
            }
        } catch (e: IOException) {
            Timber.e(e)
        }
    }
    return result
}

fun decodeBitmapFromBase64(base64Data: String): Bitmap {
    val bytes = Base64.decode(base64Data)
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

fun Bitmap.blurBitmap(
    @IntRange(from = 0, to = 25) radius: Int
): Bitmap {
    val input = Allocation.createFromBitmap(rs, this)
    val output = Allocation.createTyped(rs, input.type)
    val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
    val result = Bitmap.createBitmap(width, height, config)
    script.setRadius(radius.toFloat())
    script.setInput(input)
    script.forEach(output)
    output.copyTo(result)
    return result
}

private lateinit var rs: RenderScript
fun initRenderScript(context: Context) {
    if (!::rs.isInitialized) {
        rs = RenderScript.create(context.applicationContext)
    }
}
