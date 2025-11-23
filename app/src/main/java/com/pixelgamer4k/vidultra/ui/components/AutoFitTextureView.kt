package com.pixelgamer4k.vidultra.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.TextureView

/**
 * A [TextureView] that can be adjusted to maintain a specific aspect ratio.
 * Based on Google's Camera2 sample implementation.
 */
class AutoFitTextureView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextureView(context, attrs, defStyle) {

    private var aspectRatio = 0f

    /**
     * Sets the aspect ratio for this view. The size of the view will be
     * measured based on the ratio calculated from the parameters.
     *
     * @param width  Camera resolution width
     * @param height Camera resolution height
     */
    fun setAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Size cannot be negative" }
        aspectRatio = width.toFloat() / height.toFloat()
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        
        if (aspectRatio == 0f) {
            setMeasuredDimension(width, height)
        } else {
            // Fit within measured dimensions while maintaining aspect ratio
            val newWidth: Int
            val newHeight: Int
            val actualRatio = if (width > height) aspectRatio else 1f / aspectRatio
            if (width < height * actualRatio) {
                newWidth = width
                newHeight = (width / actualRatio).toInt()
            } else {
                newWidth = (height * actualRatio).toInt()
                newHeight = height
            }
            setMeasuredDimension(newWidth, newHeight)
        }
    }
}
