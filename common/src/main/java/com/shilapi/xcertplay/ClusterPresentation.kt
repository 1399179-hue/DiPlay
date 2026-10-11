package com.shilapi.xcertplay

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Point
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Shows the CarPlay instrument-cluster stream on the head unit's cluster display.
 *
 * "Flying the map to the cluster" is not a mirror of the main screen. CarPlay has a second video
 * stream (type 111) that the phone only starts once the accessory declares an alt screen, and that
 * stream is aimed at a separate Android display. This presentation owns the surface for that
 * display; the main screen keeps its own decoder untouched.
 *
 * The display has to be handed over by the car first - a Geely cluster is hidden until its
 * navigation projection mode is on - which is what [GeelyClusterDisplayControl] does.
 */
internal class ClusterPresentation(
    context: Context,
    display: Display,
    private val onSurface: (android.view.Surface?) -> Unit,
) : Presentation(context, display) {

    private var surfaceView: SurfaceView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val view = SurfaceView(context)
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "cluster surface created ${holder.surface.isValid}")
                onSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "cluster surface changed ${width}x$height")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "cluster surface destroyed")
                onSurface(null)
            }
        })
        container.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        surfaceView = view
        setContentView(container)
    }

    override fun onStop() {
        // A stopped presentation keeps its display; drop the surface so the decoder is not left
        // bound to a window that is no longer visible.
        onSurface(null)
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        // The presentation's own display is the cluster; the activity's window manager describes
        // the main screen and would report that size instead.
        val size = Point().also { display.getRealSize(it) }
        Log.i(TAG, "cluster presentation started ${size.x}x${size.y} display=${display.name}")
    }

    companion object {
        const val TAG = "xcertplay-usb"

        /**
         * True when this display looks like an instrument cluster.
         *
         * The classifier is deliberately generous: a renumbered firmware must still project, so a
         * match on the known 领克 03 identity only decides which candidate wins, never whether any
         * candidate is acceptable.
         */
        fun isClusterDisplay(display: Display): Boolean =
            display.state == Display.STATE_ON && display.isValid
    }
}
