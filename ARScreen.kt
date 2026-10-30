package com.tonix.wifireader

import android.content.Context
import android.graphics.*
import android.hardware.Camera
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Live camera + REAL Wi-Fi telemetry HUD for one target network. */
@Suppress("DEPRECATION")
class ARScreen(ctx: Context, targetBssid: String, private val targetSsid: String) : FrameLayout(ctx) {
    private var camera: Camera? = null
    private val surface = SurfaceView(ctx)
    private val tele = WifiTelemetry(ctx, targetBssid, targetSsid)
    private val beeper = Beeper { if (tele.inRange) tele.rssi else null }
    private val hud = HudView(ctx, tele)
    private val camMsg: TextView
    private val beepBtn: TextView
    private var beepOn = false

    init {
        setBackgroundColor(Color.BLACK)
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(hud, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        camMsg = TextView(ctx).apply {
            setTextColor(Color.parseColor("#FF6B6B")); textSize = 12f; gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24); visibility = View.GONE
        }
        addView(camMsg, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        beepBtn = TextView(ctx).apply {
            textSize = 12f; setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE
            setPadding(28, 16, 28, 16)
            setBackgroundColor(Color.parseColor("#990B1622"))
            setOnClickListener { setBeep(!beepOn) }
        }
        addView(beepBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(16, 16, 0, 0) })
        setBeep(false)

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) { openCamera(holder) }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) { releaseCamera() }
        })
        tele.start()
    }

    private fun setBeep(on: Boolean) {
        beepOn = on
        beepBtn.text = if (on) "🔊 BEEP ON" else "🔈 BEEP OFF"
        if (on) beeper.start() else beeper.stop()
    }

    private fun displayRotation(): Int {
        val w = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return when (w.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    private fun openCamera(holder: SurfaceHolder) {
        try {
            releaseCamera()
            val info = Camera.CameraInfo()
            var id = 0
            for (i in 0 until Camera.getNumberOfCameras()) {
                Camera.getCameraInfo(i, info)
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id = i; break }
            }
            Camera.getCameraInfo(id, info)
            val cam = Camera.open(id)

            // correct orientation for ANY phone (sensor angle differs per device) - fixes the sideways/upside-down preview
            val rot = displayRotation()
            val orient = if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT)
                (360 - (info.orientation + rot) % 360) % 360
            else (info.orientation - rot + 360) % 360
            cam.setDisplayOrientation(orient)
            val portraitLike = orient == 90 || orient == 270

            val vw = if (width > 0) width else resources.displayMetrics.widthPixels
            val vh = if (height > 0) height else resources.displayMetrics.heightPixels
            val params = cam.parameters
            val target = if (portraitLike) vh.toDouble() / vw else vw.toDouble() / vh
            val best = params.supportedPreviewSizes
                .filter { it.width * it.height <= 1920 * 1080 }
                .minWithOrNull(compareBy<Camera.Size>({ (abs(it.width.toDouble() / it.height - target) * 100).toInt() }, { -(it.width * it.height) }))
                ?: params.supportedPreviewSizes.first()
            params.setPreviewSize(best.width, best.height)
            if (params.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) == true)
                params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE
            try { cam.parameters = params } catch (e: Exception) { }
            cam.setPreviewDisplay(holder)
            cam.startPreview()
            camera = cam
            camMsg.visibility = View.GONE

            // scale preview to COVER the screen without stretching
            val dw = if (portraitLike) best.height else best.width
            val dh = if (portraitLike) best.width else best.height
            val scale = max(vw.toFloat() / dw, vh.toFloat() / dh)
            val lp = LayoutParams((dw * scale).toInt(), (dh * scale).toInt(), Gravity.CENTER)
            post { surface.layoutParams = lp }
        } catch (e: Exception) {
            camMsg.text = "Camera unavailable (${e.message ?: "busy"}).\nClose other camera apps and reopen."
            camMsg.visibility = View.VISIBLE
        }
    }

    private fun releaseCamera() {
        try { camera?.stopPreview() } catch (e: Exception) { }
        try { camera?.release() } catch (e: Exception) { }
        camera = null
    }

    fun resume() { tele.start(); if (beepOn) beeper.start() }
    fun pause() { beeper.stop(); releaseCamera(); tele.stop() }
    fun stop() { pause() }
}

/** Cyberpunk glass HUD. Everything drawn here is read from WifiTelemetry (real values). */
class HudView(ctx: Context, private val t: WifiTelemetry) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
    private var phase = 0f
    private var shownRssi = -100f
    private var shownMbps = 0f
    private val rect = RectF()
    private val path = Path()

    private val loop = object : Runnable {
        override fun run() {
            phase = (System.currentTimeMillis() % 4000L) / 4000f
            invalidate(); postDelayed(this, 50)
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(loop) }
    override fun onDetachedFromWindow() { removeCallbacks(loop); super.onDetachedFromWindow() }

    private fun sigColor(r: Int) = when {
        r >= -55 -> Color.parseColor("#00E676")
        r >= -70 -> Color.parseColor("#FFB300")
        else -> Color.parseColor("#FF3B30")
    }
    private fun bars(r: Int) = when { r >= -55 -> 3; r >= -70 -> 2; r >= -85 -> 1; else -> 0 }

    private fun glass(c: Canvas, l: Float, tp: Float, r: Float, b: Float, rad: Float, accent: Int) {
        rect.set(l, tp, r, b)
        fill.style = Paint.Style.FILL; fill.shader = null
        fill.color = Color.argb(120, 8, 16, 26); c.drawRoundRect(rect, rad, rad, fill)
        fill.shader = LinearGradient(0f, tp, 0f, b, Color.argb(40, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, rad, rad, fill); fill.shader = null
        for (i in 3 downTo 1) {                       // glow
            line.color = accent; line.alpha = 28 * (4 - i); line.strokeWidth = (1.2f + i * 2.2f) * d
            c.drawRoundRect(rect, rad, rad, line)
        }
        line.color = accent; line.alpha = 230; line.strokeWidth = 1.4f * d
        c.drawRoundRect(rect, rad, rad, line)
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.CENTER) {
        txt.textSize = size * d; txt.color = color; txt.textAlign = align
        txt.typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
        c.drawText(s, x, y, txt)
    }

    private fun waveIcon(c: Canvas, cx: Float, cy: Float, lit: Int, color: Int) {
        for (i in 1..3) {
            val rr = i * 9f * d
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            line.strokeWidth = 3f * d; line.strokeCap = Paint.Cap.ROUND
            line.color = if (i <= lit) color else Color.argb(70, 255, 255, 255)
            line.alpha = if (i <= lit) 255 else 70
            c.drawArc(rect, 225f, 90f, false, line)
        }
        line.strokeCap = Paint.Cap.BUTT
        fill.style = Paint.Style.FILL; fill.color = color
        c.drawCircle(cx, cy, 3.2f * d, fill)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val live = t.inRange && t.rssi != null
        val r = t.rssi ?: -100
        shownRssi += (r - shownRssi) * 0.35f
        val rs = Math.round(shownRssi)
        val col = if (live) sigColor(rs) else Color.parseColor("#FF3B30")

        // ---------- main floating AR tag ----------
        val bob = (sin(phase * 2 * Math.PI) * 6 * d).toFloat()
        val cx = w / 2f
        val top = h * 0.14f + bob
        val tw = min(w * 0.78f, 300f * d)
        glass(c, cx - tw / 2, top, cx + tw / 2, top + 46f * d, 14f * d, col)
        var name = t.targetSsid
        if (name.length > 22) name = name.take(21) + "…"
        text(c, name, cx, top + 29f * d, 17f, Color.WHITE, true)

        val bw = 130f * d
        rect.set(cx - bw / 2, top + 56f * d, cx + bw / 2, top + 88f * d)
        fill.style = Paint.Style.FILL; fill.color = col
        c.drawRoundRect(rect, 10f * d, 10f * d, fill)
        text(c, if (live) "$rs dBm" else "NO SIGNAL", cx, top + 79f * d, 17f, Color.parseColor("#04121A"), true)

        waveIcon(c, cx, top + 124f * d, if (live) bars(rs) else 0, col)
        line.color = col; line.alpha = 160; line.strokeWidth = 1.5f * d
        c.drawLine(cx, top + 132f * d, cx, top + 160f * d, line)
        path.reset(); path.moveTo(cx, top + 172f * d); path.lineTo(cx + 7f * d, top + 164f * d)
        path.lineTo(cx, top + 156f * d); path.lineTo(cx - 7f * d, top + 164f * d); path.close()
        fill.color = col; c.drawPath(path, fill)

        // ---------- nearby networks (real scan list) ----------
        val oy = h * 0.40f
        val list = t.others
        if (list.isNotEmpty()) {
            text(c, "NEARBY", w - 14f * d, oy - 6f * d, 9f, Color.parseColor("#7FDBFF"), false, Paint.Align.RIGHT)
            for ((i, ap) in list.withIndex()) {
                val y = oy + i * 36f * d + (sin((phase + i * 0.25f) * 2 * Math.PI) * 3 * d).toFloat()
                val cw = 150f * d
                val ac = sigColor(ap.level)
                glass(c, w - cw - 10f * d, y, w - 10f * d, y + 30f * d, 8f * d, ac)
                var n = ap.ssid; if (n.length > 11) n = n.take(10) + "…"
                text(c, n, w - cw - 4f * d, y + 20f * d, 10f, Color.WHITE, false, Paint.Align.LEFT)
                text(c, "${ap.level}", w - 16f * d, y + 20f * d, 11f, ac, true, Paint.Align.RIGHT)
            }
        }

        // ---------- bottom telemetry panel ----------
        val ph = 214f * d
        val pl = 10f * d; val pr = w - 10f * d; val pb = h - 10f * d; val pt = pb - ph
        glass(c, pl, pt, pr, pb, 18f * d, Color.parseColor("#00E5FF"))
        val colw = (pr - pl) / 3f

        // DOWN Mbps (measured), smoothed for display
        val mb = t.downMbps
        if (mb != null) shownMbps += (mb.toFloat() - shownMbps) * 0.4f else shownMbps = 0f

        fun cell(i: Int, label: String, value: String, vc: Int) {
            val x = pl + colw * i + colw / 2
            text(c, label, x, pt + 24f * d, 10f, Color.parseColor("#7FDBFF"))
            text(c, value, x, pt + 52f * d, 20f, vc, true)
        }
        cell(0, "SIGNAL", if (live) "$rs dBm" else "--", col)
        cell(1, "DOWN Mbps", if (mb != null) "%.1f".format(shownMbps) else "--", Color.parseColor("#00FFC6"))
        cell(2, "PING", t.pingMs?.let { "$it ms" } ?: "--", Color.WHITE)

        val ty = pt + 84f * d
        val txr = if (t.connected && t.txMbps > 0) "${t.txMbps}/${t.rxMbps}" else if (t.connected) "${t.linkMbps}" else "--"
        fun cell2(i: Int, label: String, value: String) {
            val x = pl + colw * i + colw / 2
            text(c, label, x, ty, 9f, Color.parseColor("#7FDBFF"))
            text(c, value, x, ty + 18f * d, 13f, Color.WHITE, true)
        }
        cell2(0, "LINK TX/RX Mbps", txr)
        cell2(1, "BAND / CH", if (t.freq > 0) "${t.bandName().replace(" GHz", "G")} / ${t.channel()}" else "--")
        cell2(2, "≈ DISTANCE", if (live) "%.1f m".format(SignalMath.estimateDistanceMeters(rs)) else "--")

        // sparkline of measured Mbps
        val sl = pl + 14f * d; val sr = pr - 14f * d; val st = pt + 128f * d; val sb = pt + 176f * d
        val hist = synchronized(t.history) { ArrayList(t.history) }
        if (hist.size >= 2) {
            val mx = max(1f, hist.max())
            path.reset()
            for ((i, v) in hist.withIndex()) {
                val x = sl + (sr - sl) * i / (hist.size - 1)
                val y = sb - (sb - st) * (v / mx)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            line.color = Color.parseColor("#00FFC6"); line.alpha = 255; line.strokeWidth = 2f * d
            c.drawPath(path, line)
            text(c, "peak %.0f Mbps".format(mx), sr, st - 2f * d, 8f, Color.parseColor("#7FDBFF"), false, Paint.Align.RIGHT)
        } else {
            val msg = when {
                !t.locationOn -> "Turn ON Location (GPS) - Android hides Wi-Fi data without it"
                t.connected && t.testNote.isNotEmpty() -> t.testNote
                t.connected -> "Measuring real download speed..."
                else -> "Connect phone to \"${t.targetSsid}\" to measure real Mbps & ping"
            }
            text(c, msg, (sl + sr) / 2, (st + sb) / 2 + 4f * d, 10f, Color.parseColor("#FFC400"))
        }

        // status line
        val status = when {
            !t.inRange -> "TARGET NOT IN RANGE"
            t.source == "LIVE" -> "● LIVE from Wi-Fi chip (connected) · ${t.standard} · ${t.width}"
            else -> "◌ SCAN data, ${t.scanAgeSec}s old · connect for 2x/sec live · ${t.standard}"
        }
        text(c, status, (pl + pr) / 2, pb - 12f * d, 9f, Color.parseColor("#B2EBF2"))
    }
}
