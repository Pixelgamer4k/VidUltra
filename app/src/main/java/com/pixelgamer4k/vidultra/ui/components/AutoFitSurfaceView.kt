package com.pixelgamer4k.vidultra.ui.components

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView

/**
 * A SurfaceView that maintains a specific aspect ratio.
 * Much simpler than TextureView - no transformation matrix needed.
 * SurfaceView automatically handles camera sensor rotation.
 */
class AutoFitSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : SurfaceView(context, attrs, defStyle) {

    private var aspectRatio = 0f

    /**
     * Sets the aspect ratio for this view.
     * @param width Camera resolution width
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
            // Calculate dimensions that maintain aspect ratio
            val newWidth: Int
            val newHeight: Int
            
            // For portrait mode with landscape camera
            val actualRatio = if (width > height) aspectRatio else 1f / aspectRatio
            
            if (width < height * actualRatio) {
                // Limited by width
                newWidth = width
                newHeight = (width / actualRatio).toInt()
            } else {
                // Limited by height
                newWidth = (height * actualRatio).toInt()
                newHeight = height
            }
            
            setMeasuredDimension(newWidth, newHeight)
        }
    }
}
