package space.nish.stlviewer.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.EGLExt
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import space.nish.stlviewer.model.Mesh
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * GLSurfaceView hosting [ModelRenderer] with touch controls:
 * one finger orbits, two fingers pan, pinch zooms, double tap resets the view.
 */
@SuppressLint("ViewConstructor")
class ModelSurfaceView(context: Context) : GLSurfaceView(context) {

    private val camera = OrbitCamera()
    private val renderer = ModelRenderer(camera)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentMesh: Mesh? = null

    /** Called on the main thread when rendering fails (e.g. GPU out of memory). */
    var onRenderError: ((String) -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private var lastPointerCount = 0

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            camera.zoom(detector.scaleFactor)
            requestRender()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetCamera()
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MultisampleConfigChooser())
        preserveEGLContextOnPause = true
        renderer.onError = { message -> mainHandler.post { onRenderError?.invoke(message) } }
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setMesh(mesh: Mesh?) {
        if (mesh === currentMesh) return
        currentMesh = mesh
        camera.frame(mesh?.bounds)
        renderer.setMesh(mesh)
        requestRender()
    }

    fun setDisplayMode(mode: RenderMode) {
        if (renderer.renderMode == mode) return
        renderer.renderMode = mode
        requestRender()
    }

    fun setShowGrid(show: Boolean) {
        if (renderer.showGrid == show) return
        renderer.showGrid = show
        requestRender()
    }

    fun setColors(model: FloatArray, background: FloatArray, darkBackground: Boolean) {
        renderer.modelColor = model
        renderer.backgroundColor = background
        renderer.darkBackground = darkBackground
        requestRender()
    }

    fun resetCamera() {
        camera.reset()
        requestRender()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        // Use the centroid of all pointers so adding/removing a finger does not cause a jump.
        val pointerUp = event.actionMasked == MotionEvent.ACTION_POINTER_UP
        val skipIndex = if (pointerUp) event.actionIndex else -1
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == skipIndex) continue
            sumX += event.getX(i)
            sumY += event.getY(i)
            count++
        }
        if (count == 0) return true
        val x = sumX / count
        val y = sumY / count

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                lastX = x; lastY = y; lastPointerCount = count
            }
            MotionEvent.ACTION_MOVE -> {
                if (count != lastPointerCount) {
                    lastX = x; lastY = y; lastPointerCount = count
                    return true
                }
                val dx = x - lastX
                val dy = y - lastY
                lastX = x; lastY = y
                if (count == 1) {
                    camera.rotate(dx, dy, resources.displayMetrics.density)
                } else {
                    camera.pan(dx, dy)
                }
                requestRender()
            }
        }
        return true
    }

    /** Prefers 4× MSAA for smooth edges, falling back to plain configs on devices that lack it. */
    private class MultisampleConfigChooser : EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val es3 = EGLExt.EGL_OPENGL_ES3_BIT_KHR
            val candidates = listOf(
                intArrayOf(
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_RENDERABLE_TYPE, es3,
                    EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4, EGL10.EGL_NONE,
                ),
                intArrayOf(
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_RENDERABLE_TYPE, es3, EGL10.EGL_NONE,
                ),
                intArrayOf(EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, es3, EGL10.EGL_NONE),
            )
            for (attributes in candidates) {
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                if (egl.eglChooseConfig(display, attributes, configs, 1, count) && count[0] > 0) {
                    configs[0]?.let { return it }
                }
            }
            throw IllegalStateException("No OpenGL ES 3.0 compatible EGL config found")
        }
    }
}
