package com.vidultra.pro.ui

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView
import kotlin.math.roundToInt

/**
 * TextureView that maintains the target aspect ratio without letter-box cut-offs.
 */
class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {

    private var ratioWidth = 0
    private var ratioHeight = 0

    fun setAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Aspect ratio must be positive" }
        ratioWidth = width
        ratioHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (ratioWidth == 0 || ratioHeight == 0) {
            setMeasuredDimension(width, height)
            return
        }
        val previewRatio = ratioWidth.toFloat() / ratioHeight
        val viewRatio = width.toFloat() / height

        if (viewRatio > previewRatio) {
            // View is wider than preview -> match height, scale width
            setMeasuredDimension((height * previewRatio).roundToInt(), height)
        } else {
            // View is taller than preview -> match width, scale height
            setMeasuredDimension(width, (width / previewRatio).roundToInt())
        }
    }
}
