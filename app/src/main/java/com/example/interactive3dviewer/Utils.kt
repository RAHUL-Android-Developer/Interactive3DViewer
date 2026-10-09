package com.example.interactive3dviewer

import android.content.Context
import kotlin.math.cos
import kotlin.math.sin

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density

/** Tiny column-major 4x4 matrix helpers (what Filament's TransformManager expects). No allocations in hot paths except the result. */
object M4 {
    fun mul(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(16)
        for (c in 0..3) for (row in 0..3) {
            var s = 0f
            for (k in 0..3) s += a[k * 4 + row] * b[c * 4 + k]
            r[c * 4 + row] = s
        }
        return r
    }

    fun translation(x: Float, y: Float, z: Float) =
        floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, x, y, z, 1f)

    fun scale(s: Float) =
        floatArrayOf(s, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, s, 0f, 0f, 0f, 0f, 1f)

    fun rotX(rad: Float): FloatArray {
        val c = cos(rad); val s = sin(rad)
        return floatArrayOf(1f, 0f, 0f, 0f, 0f, c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f)
    }

    fun rotY(rad: Float): FloatArray {
        val c = cos(rad); val s = sin(rad)
        return floatArrayOf(c, 0f, -s, 0f, 0f, 1f, 0f, 0f, s, 0f, c, 0f, 0f, 0f, 0f, 1f)
    }
}
