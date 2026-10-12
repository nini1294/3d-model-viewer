package space.nish.stlviewer.render

import android.opengl.Matrix
import space.nish.stlviewer.model.BoundingBox
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Z-up orbit camera (STL / 3D-printing convention). Mutated from the UI thread by gestures and read from the
 * GL thread while rendering, so every public member is synchronised.
 */
class OrbitCamera {
    private val target = floatArrayOf(0f, 0f, 0f)
    private var distance = 10f
    private var yawDeg = DEFAULT_YAW
    private var pitchDeg = DEFAULT_PITCH
    private var radius = 1f
    private var viewportWidth = 1
    private var viewportHeight = 1
    private var bounds: BoundingBox? = null
    private var viewportKnown = false
    private var pendingFit = false

    @Synchronized
    fun setViewport(width: Int, height: Int) {
        viewportWidth = max(width, 1)
        viewportHeight = max(height, 1)
        viewportKnown = true
        if (pendingFit) {
            distance = fitDistance()
            pendingFit = false
        }
    }

    /** Centres the model and picks a distance that fits its bounding sphere on screen. */
    @Synchronized
    fun frame(box: BoundingBox?) {
        bounds = box
        reset()
    }

    @Synchronized
    fun reset() {
        val box = bounds
        if (box != null) {
            target[0] = box.centerX; target[1] = box.centerY; target[2] = box.centerZ
            radius = box.radius
        } else {
            target.fill(0f)
            radius = 1f
        }
        yawDeg = DEFAULT_YAW
        pitchDeg = DEFAULT_PITCH
        distance = fitDistance()
        pendingFit = !viewportKnown
    }

    @Synchronized
    fun rotate(dxPx: Float, dyPx: Float, density: Float) {
        val degreesPerPx = ROTATE_DEG_PER_DP / density
        yawDeg -= dxPx * degreesPerPx
        pitchDeg = (pitchDeg + dyPx * degreesPerPx).coerceIn(-89f, 89f)
    }

    @Synchronized
    fun pan(dxPx: Float, dyPx: Float) {
        val worldPerPx = 2f * distance * tan(Math.toRadians(FOV_Y_DEG / 2.0)).toFloat() / viewportHeight
        val (right, up) = rightAndUp()
        for (i in 0..2) {
            target[i] += (-right[i] * dxPx + up[i] * dyPx) * worldPerPx
        }
    }

    @Synchronized
    fun zoom(scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        distance = (distance / scaleFactor).coerceIn(radius * 0.05f, radius * 50f)
    }

    /** Writes the view and projection matrices and returns the camera eye position in [eyeOut]. */
    @Synchronized
    fun computeMatrices(view: FloatArray, projection: FloatArray, eyeOut: FloatArray) {
        val eye = eyePosition()
        Matrix.setLookAtM(view, 0, eye[0], eye[1], eye[2], target[0], target[1], target[2], 0f, 0f, 1f)
        val aspect = viewportWidth.toFloat() / viewportHeight
        val near = max(distance - radius * 4f, distance * 0.01f)
        val far = distance + radius * 20f
        Matrix.perspectiveM(projection, 0, FOV_Y_DEG.toFloat(), aspect, near, far)
        eye.copyInto(eyeOut)
    }

    private fun eyePosition(): FloatArray {
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        val cp = cos(pitch)
        return floatArrayOf(
            target[0] + (distance * cp * cos(yaw)).toFloat(),
            target[1] + (distance * cp * sin(yaw)).toFloat(),
            target[2] + (distance * sin(pitch)).toFloat(),
        )
    }

    private fun rightAndUp(): Pair<FloatArray, FloatArray> {
        val eye = eyePosition()
        val f = normalize(floatArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2]))
        val right = normalize(cross(f, floatArrayOf(0f, 0f, 1f)))
        val up = cross(right, f)
        return right to up
    }

    private fun fitDistance(): Float {
        val aspect = viewportWidth.toFloat() / viewportHeight
        val halfFovY = Math.toRadians(FOV_Y_DEG / 2.0)
        val halfFovX = kotlin.math.atan(tan(halfFovY) * aspect)
        val halfFov = min(halfFovY, halfFovX)
        return (radius / sin(halfFov)).toFloat() * 1.1f
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun normalize(v: FloatArray): FloatArray {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (len > 0f) floatArrayOf(v[0] / len, v[1] / len, v[2] / len) else v
    }

    private companion object {
        const val FOV_Y_DEG = 40.0
        /** Looking from the front-right (+X, -Y), slightly above: the usual "slicer" default view. */
        const val DEFAULT_YAW = -60f
        const val DEFAULT_PITCH = 25f
        const val ROTATE_DEG_PER_DP = 0.45f
    }
}
