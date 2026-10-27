package com.opencanvas.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

class ShapeResult(val kind: String, val xs: FloatArray, val ys: FloatArray)

/**
 * QuickShape: turns a rough hand-drawn stroke into a clean line, arc, circle, ellipse,
 * rectangle, triangle or polygon. Pure math, no Android types, so it can be unit-tested on a JVM.
 */
object ShapeFit {
    const val LINE = "line"
    const val ARC = "arc"
    const val CIRCLE = "circle"
    const val ELLIPSE = "ellipse"
    const val RECT = "rectangle"
    const val TRIANGLE = "triangle"
    const val POLYGON = "polygon"

    private const val RESAMPLE = 72

    fun recognize(px: FloatArray, py: FloatArray): ShapeResult {
        val n = px.size
        val x = DoubleArray(n) { px[it].toDouble() }
        val y = DoubleArray(n) { py[it].toDouble() }
        if (n < 3) return line(x[0], y[0], x[n - 1], y[n - 1])

        var len = 0.0
        var minX = x[0]
        var maxX = x[0]
        var minY = y[0]
        var maxY = y[0]
        for (i in 1 until n) {
            len += hypot(x[i] - x[i - 1], y[i] - y[i - 1])
            minX = min(minX, x[i]); maxX = max(maxX, x[i])
            minY = min(minY, y[i]); maxY = max(maxY, y[i])
        }
        val diag = hypot(maxX - minX, maxY - minY)
        if (diag < 1e-6 || len < 1e-6) return line(x[0], y[0], x[n - 1], y[n - 1])

        val gap = hypot(x[0] - x[n - 1], y[0] - y[n - 1])
        val closed = gap < 0.25 * len
        return if (closed) fitClosed(x, y, diag) else fitOpen(x, y)
    }

    // ---- open strokes: line or arc ------------------------------------------------

    private fun fitOpen(x: DoubleArray, y: DoubleArray): ShapeResult {
        val n = x.size
        val sx = x[0]
        val sy = y[0]
        val ex = x[n - 1]
        val ey = y[n - 1]
        val c = hypot(ex - sx, ey - sy)
        if (c < 1e-6) return line(sx, sy, ex, ey)

        val nx = -(ey - sy) / c
        val ny = (ex - sx) / c
        var dev = 0.0
        var apex = 0
        for (i in 0 until n) {
            val d = abs((x[i] - sx) * nx + (y[i] - sy) * ny)
            if (d > dev) {
                dev = d
                apex = i
            }
        }
        if (dev <= 0.08 * c) return line(sx, sy, ex, ey)

        val circ = circleThrough(sx, sy, x[apex], y[apex], ex, ey) ?: return line(sx, sy, ex, ey)
        val cx = circ[0]
        val cy = circ[1]
        val r = circ[2]
        if (r > 25.0 * c) return line(sx, sy, ex, ey)
        var err = 0.0
        for (i in 0 until n) err += abs(hypot(x[i] - cx, y[i] - cy) - r)
        err /= n
        if (err > 0.10 * r) return line(sx, sy, ex, ey)

        val a0 = atan2(sy - cy, sx - cx)
        val a1 = atan2(ey - cy, ex - cx)
        val am = atan2(y[apex] - cy, x[apex] - cx)
        val sweepCcw = ccw(a0, a1)
        val apexCcw = ccw(a0, am)
        val sweep = if (apexCcw <= sweepCcw) sweepCcw else sweepCcw - 2 * PI
        val steps = 48
        val ox = FloatArray(steps + 1)
        val oy = FloatArray(steps + 1)
        for (i in 0..steps) {
            val a = a0 + sweep * i / steps
            ox[i] = (cx + r * cos(a)).toFloat()
            oy[i] = (cy + r * sin(a)).toFloat()
        }
        return ShapeResult(ARC, ox, oy)
    }

    private fun ccw(from: Double, to: Double): Double {
        var d = (to - from) % (2 * PI)
        if (d < 0) d += 2 * PI
        return d
    }

    private fun circleThrough(
        ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double
    ): DoubleArray? {
        val d = 2 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
        if (abs(d) < 1e-9) return null
        val a2 = ax * ax + ay * ay
        val b2 = bx * bx + by * by
        val c2 = cx * cx + cy * cy
        val ux = (a2 * (by - cy) + b2 * (cy - ay) + c2 * (ay - by)) / d
        val uy = (a2 * (cx - bx) + b2 * (ax - cx) + c2 * (bx - ax)) / d
        return doubleArrayOf(ux, uy, hypot(ax - ux, ay - uy))
    }

    // ---- closed strokes: ellipse / circle / polygon -------------------------------

    private fun fitClosed(x0: DoubleArray, y0: DoubleArray, diag: Double): ShapeResult {
        val rs = resampleClosed(x0, y0, RESAMPLE)
        val x = rs[0]
        val y = rs[1]
        val m = x.size

        // ellipse candidate (axes from principal directions, size from extents)
        var mx = 0.0
        var my = 0.0
        for (i in 0 until m) {
            mx += x[i]
            my += y[i]
        }
        mx /= m
        my /= m
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (i in 0 until m) {
            val dx = x[i] - mx
            val dy = y[i] - my
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        val theta = 0.5 * atan2(2 * sxy, sxx - syy)
        val ct = cos(theta)
        val st = sin(theta)
        var minU = Double.MAX_VALUE
        var maxU = -Double.MAX_VALUE
        var minV = Double.MAX_VALUE
        var maxV = -Double.MAX_VALUE
        val pu = DoubleArray(m)
        val pv = DoubleArray(m)
        for (i in 0 until m) {
            pu[i] = (x[i] - mx) * ct + (y[i] - my) * st
            pv[i] = -(x[i] - mx) * st + (y[i] - my) * ct
            minU = min(minU, pu[i]); maxU = max(maxU, pu[i])
            minV = min(minV, pv[i]); maxV = max(maxV, pv[i])
        }
        var a = (maxU - minU) / 2
        var b = (maxV - minV) / 2
        val cu = (maxU + minU) / 2
        val cv = (maxV + minV) / 2
        val ecx = mx + cu * ct - cv * st
        val ecy = my + cu * st + cv * ct

        // a closed stroke that is almost flat is really a line
        if (min(a, b) < 0.10 * max(a, b)) {
            return if (a >= b) {
                line(ecx - a * ct, ecy - a * st, ecx + a * ct, ecy + a * st)
            } else {
                line(ecx + b * st, ecy - b * ct, ecx - b * st, ecy + b * ct)
            }
        }
        a = max(a, 1e-6)
        b = max(b, 1e-6)

        var errE = 0.0
        for (i in 0 until m) {
            val du = pu[i] - cu
            val dv = pv[i] - cv
            val f = (du / a) * (du / a) + (dv / b) * (dv / b) - 1.0
            val g = 2.0 * sqrt((du / (a * a)) * (du / (a * a)) + (dv / (b * b)) * (dv / (b * b)))
            errE += abs(f) / max(g, 1e-9)
        }
        errE /= m

        // polygon candidate
        val poly = polygonCandidate(x, y, diag)
        if (poly != null) {
            val errP = poly.error
            val factor = if (poly.vx.size >= 5) 0.6 else 0.8
            if (errP < factor * errE && errP < 0.04 * diag) {
                return polygonResult(poly)
            }
        }

        // ellipse / circle output with light regularisation
        var ea = a
        var eb = b
        var th = theta
        if (abs(ea - eb) / max(ea, eb) < 0.12) {
            val r = (ea + eb) / 2
            ea = r
            eb = r
        } else {
            var deg = Math.toDegrees(th)
            val nearest = round(deg / 90.0) * 90.0
            if (abs(deg - nearest) < 7.0) {
                th = Math.toRadians(nearest)
            }
        }
        val kind = if (ea == eb) CIRCLE else ELLIPSE
        val cth = cos(th)
        val sth = sin(th)
        val steps = 72
        val ox = FloatArray(steps + 1)
        val oy = FloatArray(steps + 1)
        for (i in 0..steps) {
            val t = 2 * PI * i / steps
            val ux = ea * cos(t)
            val vy = eb * sin(t)
            ox[i] = (ecx + ux * cth - vy * sth).toFloat()
            oy[i] = (ecy + ux * sth + vy * cth).toFloat()
        }
        return ShapeResult(kind, ox, oy)
    }

    private fun resampleClosed(x: DoubleArray, y: DoubleArray, m: Int): Array<DoubleArray> {
        val n = x.size
        val cum = DoubleArray(n + 1)
        for (i in 1..n) {
            val j = i % n
            cum[i] = cum[i - 1] + hypot(x[j] - x[i - 1], y[j] - y[i - 1])
        }
        val total = cum[n]
        val rx = DoubleArray(m)
        val ry = DoubleArray(m)
        var seg = 1
        for (k in 0 until m) {
            val t = total * k / m
            while (seg < n && cum[seg] < t) seg++
            val a = seg - 1
            val b = seg % n
            val segLen = cum[seg] - cum[a]
            val f = if (segLen > 1e-12) (t - cum[a]) / segLen else 0.0
            rx[k] = x[a] + (x[b] - x[a]) * f
            ry[k] = y[a] + (y[b] - y[a]) * f
        }
        return arrayOf(rx, ry)
    }

    private class Poly(val vx: DoubleArray, val vy: DoubleArray, val error: Double)

    private fun polygonCandidate(x: DoubleArray, y: DoubleArray, diag: Double): Poly? {
        val m = x.size
        // closed Ramer-Douglas-Peucker: split at point 0 and the point farthest from it
        var far = 0
        var farD = 0.0
        for (i in 1 until m) {
            val d = hypot(x[i] - x[0], y[i] - y[0])
            if (d > farD) {
                farD = d
                far = i
            }
        }
        val keep = BooleanArray(m)
        keep[0] = true
        keep[far] = true
        val eps = 0.04 * diag
        rdp(x, y, 0, far, eps, keep)
        rdp(x, y, far, m, eps, keep)

        val idx = ArrayList<Int>()
        for (i in 0 until m) if (keep[i]) idx.add(i)

        // drop near-collinear vertices and tiny edges
        var changed = true
        while (changed && idx.size > 3) {
            changed = false
            for (k in idx.indices) {
                val p = idx[(k - 1 + idx.size) % idx.size]
                val v = idx[k]
                val q = idx[(k + 1) % idx.size]
                val lenPrev = hypot(x[v] - x[p], y[v] - y[p])
                val cross = (x[v] - x[p]) * (y[q] - y[v]) - (y[v] - y[p]) * (x[q] - x[v])
                val dot = (x[v] - x[p]) * (x[q] - x[v]) + (y[v] - y[p]) * (y[q] - y[v])
                val turn = abs(atan2(cross, dot))
                if (lenPrev < 0.17 * diag || turn < 0.35) {
                    idx.removeAt(k)
                    changed = true
                    break
                }
            }
        }
        if (idx.size < 3 || idx.size > 6) return null

        val full = fitPoly(x, y, idx, diag)
        if (idx.size == 5) {
            // five corners where one is just a rounded corner: prefer the simpler four-corner shape
            var best: Poly? = null
            for (j in idx.indices) {
                val sub = ArrayList(idx)
                sub.removeAt(j)
                val cand = fitPoly(x, y, sub, diag)
                if (best == null || cand.error < best.error) best = cand
            }
            if (best != null && best.error < 1.6 * full.error + 0.004 * diag) return best
        }
        return full
    }

    /** Refines each corner by fitting a line to every side and intersecting neighbours. */
    private fun fitPoly(x: DoubleArray, y: DoubleArray, idx: List<Int>, diag: Double): Poly {
        val m = x.size
        val k = idx.size
        // refine corners: fit a line to each side, intersect neighbours
        val lpx = DoubleArray(k)
        val lpy = DoubleArray(k)
        val ldx = DoubleArray(k)
        val ldy = DoubleArray(k)
        val ok = BooleanArray(k)
        for (s in 0 until k) {
            val i0 = idx[s]
            var i1 = idx[(s + 1) % k]
            if (i1 <= i0) i1 += m
            val count = i1 - i0 + 1
            val trim = max(1, (count * 0.2).toInt())
            val pts = ArrayList<Int>()
            for (i in (i0 + trim)..(i1 - trim)) pts.add(i % m)
            if (pts.size < 3) {
                pts.clear()
                for (i in i0..i1) pts.add(i % m)
            }
            var cx = 0.0
            var cy = 0.0
            for (i in pts) {
                cx += x[i]
                cy += y[i]
            }
            cx /= pts.size
            cy /= pts.size
            var axx = 0.0
            var ayy = 0.0
            var axy = 0.0
            for (i in pts) {
                axx += (x[i] - cx) * (x[i] - cx)
                ayy += (y[i] - cy) * (y[i] - cy)
                axy += (x[i] - cx) * (y[i] - cy)
            }
            val ang = 0.5 * atan2(2 * axy, axx - ayy)
            lpx[s] = cx
            lpy[s] = cy
            ldx[s] = cos(ang)
            ldy[s] = sin(ang)
            ok[s] = true
        }
        val vx = DoubleArray(k)
        val vy = DoubleArray(k)
        for (j in 0 until k) {
            val prev = (j - 1 + k) % k
            vx[j] = x[idx[j]]
            vy[j] = y[idx[j]]
            val cross = ldx[prev] * ldy[j] - ldy[prev] * ldx[j]
            if (abs(cross) > 0.15) {
                val t = ((lpx[j] - lpx[prev]) * ldy[j] - (lpy[j] - lpy[prev]) * ldx[j]) / cross
                val ix = lpx[prev] + t * ldx[prev]
                val iy = lpy[prev] + t * ldy[prev]
                if (hypot(ix - vx[j], iy - vy[j]) < 0.3 * diag) {
                    vx[j] = ix
                    vy[j] = iy
                }
            }
        }

        var err = 0.0
        for (i in 0 until m) {
            var best = Double.MAX_VALUE
            for (s in 0 until k) {
                val t = (s + 1) % k
                best = min(best, distToSegment(x[i], y[i], vx[s], vy[s], vx[t], vy[t]))
            }
            err += best
        }
        err /= m
        return Poly(vx, vy, err)
    }

    private fun rdp(
        x: DoubleArray, y: DoubleArray, i0: Int, i1: Int, eps: Double, keep: BooleanArray
    ) {
        val m = x.size
        val ax = x[i0 % m]
        val ay = y[i0 % m]
        val bx = x[i1 % m]
        val by = y[i1 % m]
        var dmax = 0.0
        var idx = -1
        for (i in (i0 + 1) until i1) {
            val d = distToSegment(x[i % m], y[i % m], ax, ay, bx, by)
            if (d > dmax) {
                dmax = d
                idx = i
            }
        }
        if (idx >= 0 && dmax > eps) {
            keep[idx % m] = true
            rdp(x, y, i0, idx, eps, keep)
            rdp(x, y, idx, i1, eps, keep)
        }
    }

    private fun distToSegment(
        px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double
    ): Double {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        if (l2 < 1e-12) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun polygonResult(p: Poly): ShapeResult {
        val k = p.vx.size
        if (k == 4) {
            val rect = rectangleFrom(p)
            if (rect != null) return rect
        }
        val kind = if (k == 3) TRIANGLE else POLYGON
        val ox = FloatArray(k + 1)
        val oy = FloatArray(k + 1)
        for (i in 0..k) {
            ox[i] = p.vx[i % k].toFloat()
            oy[i] = p.vy[i % k].toFloat()
        }
        return ShapeResult(kind, ox, oy)
    }

    /** If the four corners are close to right angles, return a true (possibly axis-aligned) rectangle. */
    private fun rectangleFrom(p: Poly): ShapeResult? {
        for (i in 0 until 4) {
            val a = (i + 3) % 4
            val b = (i + 1) % 4
            val ux = p.vx[a] - p.vx[i]
            val uy = p.vy[a] - p.vy[i]
            val wx = p.vx[b] - p.vx[i]
            val wy = p.vy[b] - p.vy[i]
            val lu = hypot(ux, uy)
            val lw = hypot(wx, wy)
            if (lu < 1e-6 || lw < 1e-6) return null
            val ang = Math.toDegrees(acos(((ux * wx + uy * wy) / (lu * lw)).coerceIn(-1.0, 1.0)))
            if (abs(ang - 90.0) > 16.0) return null
        }
        val s0x = p.vx[1] - p.vx[0]
        val s0y = p.vy[1] - p.vy[0]
        val s1x = p.vx[2] - p.vx[1]
        val s1y = p.vy[2] - p.vy[1]
        val s2x = p.vx[3] - p.vx[2]
        val s2y = p.vy[3] - p.vy[2]
        val s3x = p.vx[0] - p.vx[3]
        val s3y = p.vy[0] - p.vy[3]
        val ax = (s0x - s2x) / 2
        val ay = (s0y - s2y) / 2
        val bx = (s1x - s3x) / 2
        val by = (s1y - s3y) / 2
        val la = hypot(ax, ay)
        val lb = hypot(bx, by)
        if (la < 1e-6 || lb < 1e-6) return null
        val orient = ax * by - ay * bx
        // rotate B back by 90 degrees onto A's direction
        val rbx = if (orient > 0) by / lb else -by / lb
        val rby = if (orient > 0) -bx / lb else bx / lb
        var ux = ax / la + rbx
        var uy = ay / la + rby
        val lu = hypot(ux, uy)
        if (lu < 1e-6) return null
        ux /= lu
        uy /= lu
        var phi = Math.toDegrees(atan2(uy, ux))
        val nearest = round(phi / 90.0) * 90.0
        if (abs(phi - nearest) < 6.0) phi = nearest
        val ph = Math.toRadians(phi)
        val ex = cos(ph)
        val ey = sin(ph)
        val fx = -ey
        val fy = ex
        val cx = (p.vx[0] + p.vx[1] + p.vx[2] + p.vx[3]) / 4
        val cy = (p.vy[0] + p.vy[1] + p.vy[2] + p.vy[3]) / 4
        val hw = la / 2
        val hh = lb / 2
        val ox = FloatArray(5)
        val oy = FloatArray(5)
        val sx = doubleArrayOf(-1.0, 1.0, 1.0, -1.0, -1.0)
        val sy = doubleArrayOf(-1.0, -1.0, 1.0, 1.0, -1.0)
        for (i in 0..4) {
            ox[i] = (cx + sx[i] * hw * ex + sy[i] * hh * fx).toFloat()
            oy[i] = (cy + sx[i] * hw * ey + sy[i] * hh * fy).toFloat()
        }
        return ShapeResult(RECT, ox, oy)
    }

    private fun line(x0: Double, y0: Double, x1: Double, y1: Double): ShapeResult =
        ShapeResult(
            LINE,
            floatArrayOf(x0.toFloat(), x1.toFloat()),
            floatArrayOf(y0.toFloat(), y1.toFloat())
        )
}
