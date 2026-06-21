package com.fleece.screensaver

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Black-hole accretion simulation (Kotlin port of the Gargantua mockup). Pure physics — no
 * allocation in the hot path. Rendering is done by [BlackHoleRenderer].
 */
class Sim(val n: Int = 2800) {
    val x = FloatArray(n); val y = FloatArray(n); val z = FloatArray(n)
    val vx = FloatArray(n); val vy = FloatArray(n); val vz = FloatArray(n)

    private val tmpDir = FloatArray(3)
    private val tmpAxis = FloatArray(3)
    private val tmpT = FloatArray(3)

    init {
        for (i in 0 until n) if (i < n * 0.55f) seedDisk(i) else seedInfall(i)
    }

    private fun rnd(a: Float, b: Float) = a + Math.random().toFloat() * (b - a)
    private fun vcirc(r: Float) = sqrt(G / r)

    private fun randUnit(out: FloatArray) {
        val u = (Math.random() * 2 - 1).toFloat()
        val th = (Math.random() * 2.0 * PI).toFloat()
        val s = sqrt(1f - u * u)
        out[0] = s * cos(th); out[1] = s * sin(th); out[2] = u
    }
    private fun crossInto(a: FloatArray, b: FloatArray, out: FloatArray) {
        out[0] = a[1] * b[2] - a[2] * b[1]
        out[1] = a[2] * b[0] - a[0] * b[2]
        out[2] = a[0] * b[1] - a[1] * b[0]
    }
    private fun normalize(v: FloatArray) {
        var m = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); if (m == 0f) m = 1f
        v[0] /= m; v[1] /= m; v[2] /= m
    }
    private fun spinAxisInto(out: FloatArray) {
        out[0] = rnd(-0.55f, 0.55f); out[1] = rnd(-0.55f, 0.55f); out[2] = 1f; normalize(out)
    }

    private fun seedDisk(i: Int) {
        val ang = (Math.random() * 2.0 * PI).toFloat()
        val rr = R_in + (R_out - R_in) * Math.pow(Math.random(), 1.4).toFloat() // denser inner
        x[i] = rr * cos(ang); y[i] = rr * sin(ang); z[i] = rnd(-0.01f, 0.01f) * rr
        val v = vcirc(rr)
        vx[i] = -sin(ang) * v; vy[i] = cos(ang) * v; vz[i] = 0f
    }
    private fun seedInfall(i: Int) {
        randUnit(tmpDir)
        val rr = rnd(0.55f, R_spawn)
        x[i] = tmpDir[0] * rr; y[i] = tmpDir[1] * rr; z[i] = tmpDir[2] * rr
        spinAxisInto(tmpAxis); crossInto(tmpAxis, tmpDir, tmpT); normalize(tmpT)
        val v = vcirc(rr) * rnd(0.62f, 0.82f)
        vx[i] = tmpT[0] * v; vy[i] = tmpT[1] * v; vz[i] = tmpT[2] * v
    }
    private fun respawn(i: Int) {
        // Most respawns refill the bright accretion disk (near-circular, in-plane) so it stays
        // populated; the rest come back as inclined sub-circular infallers for plunge streams.
        // Without this the disk drains into the hole faster than it's replenished and empties out.
        if (Math.random().toFloat() < DISK_RESPAWN) {
            seedDisk(i)
            return
        }
        randUnit(tmpDir)
        val rr = rnd(R_out * 1.3f, R_spawn)
        x[i] = tmpDir[0] * rr; y[i] = tmpDir[1] * rr; z[i] = tmpDir[2] * rr
        spinAxisInto(tmpAxis); crossInto(tmpAxis, tmpDir, tmpT); normalize(tmpT)
        val v = vcirc(rr) * rnd(0.62f, 0.82f)
        vx[i] = tmpT[0] * v; vy[i] = tmpT[1] * v; vz[i] = tmpT[2] * v
    }

    fun step(dt: Float) {
        for (i in 0 until n) {
            val px = x[i]; val py = y[i]; val pz = z[i]
            val r2 = px * px + py * py + pz * pz + 1e-6f
            val r = sqrt(r2)
            val inv = G / (r2 * r)
            val ax = -inv * px
            val ay = -inv * py
            var az = -inv * pz
            val f = ((R_out * 1.1f - r) / (R_out * 1.1f)).coerceIn(0f, 1f)
            az += -kPlane * pz * f
            vx[i] += ax * dt; vy[i] += ay * dt; vz[i] += az * dt
            vz[i] *= (1f - planeDmp * f)
            // gentle accretion: DISK MATTER ONLY (never a global drag)
            if (pz * pz < 0.0036f && r < R_out) {
                val d = 1f - diskDrain * dt
                vx[i] *= d; vy[i] *= d; vz[i] *= d
            }
            x[i] = px + vx[i] * dt; y[i] = py + vy[i] * dt; z[i] = pz + vz[i] * dt
            if (r < Rh * 0.92f || r > R_spawn * 1.25f) respawn(i)
        }
    }

    companion object {
        const val G = 3e-6f
        const val Rh = 0.10f
        const val R_in = 0.13f
        const val R_out = 0.48f
        const val R_spawn = 1.30f
        const val diskDrain = 0.00015f       // gentle inward accretion; low enough the disk persists
        const val kPlane = 0.0012f
        const val planeDmp = 0.03f
        const val DISK_RESPAWN = 0.85f       // fraction of respawns that refill the disk (vs infall)
    }
}
