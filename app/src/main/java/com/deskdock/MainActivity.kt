package com.deskdock

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.net.URL
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

class MainActivity : Activity() {

    companion object {
        // Night mode: dim, red clock, near-black background. Set both to the same hour to turn it off.
        const val NIGHT_FROM = 22
        const val NIGHT_TO = 6

        private const val MODE_MUSIC = 0
        private const val MODE_SPLIT = 1
        private const val MODE_CLOCK = 2

        private val IDLE = intArrayOf(0xFF2A2140.toInt(), 0xFF150F22.toInt(), 0xFF050308.toInt())
        private val NIGHT = intArrayOf(0xFF1C0504.toInt(), 0xFF0B0202.toInt(), 0xFF000000.toInt())
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var msm: MediaSessionManager
    private lateinit var listener: ComponentName
    private lateinit var gestures: GestureDetector

    private lateinit var root: FrameLayout
    private lateinit var row: LinearLayout
    private lateinit var clockPane: LinearLayout
    private lateinit var musicPane: LinearLayout
    private lateinit var timeV: TextClock
    private lateinit var dateV: TextClock
    private lateinit var accessBox: LinearLayout
    private lateinit var accessHint: TextView
    private lateinit var accessBtn: Button
    private lateinit var artV: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvArtist: TextView
    private lateinit var bar: ProgressLine
    private lateinit var timesRow: LinearLayout
    private lateinit var posText: TextView
    private lateinit var durText: TextView
    private lateinit var controls: LinearLayout
    private lateinit var btnPrev: ImageButton
    private lateinit var btnPlay: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var bg: GradientDrawable

    private var controller: MediaController? = null
    private var state: PlaybackState? = null
    private var duration = 0L
    private var hasMusic = false
    private var userMode: Int? = null
    private var night = false
    private var lastKey = ""
    private var lastBmp: Bitmap? = null
    private var artColors: IntArray? = null
    private val cur = IDLE.clone()
    private var anim: ValueAnimator? = null
    private var ticks = 0
    private var clockColor = 0xFFF6EFE6.toInt()

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        listener = ComponentName(this, MediaListener::class.java)
        buildUi()
        setContentView(root)

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || !hasMusic) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (abs(dx) > dp(90f) && abs(dx) > abs(dy) * 1.5f) {
                    val next = (effectiveMode() + if (dx < 0) 1 else -1).coerceIn(MODE_MUSIC, MODE_CLOCK)
                    userMode = next
                    applyLayout(true)
                    return true
                }
                return false
            }
        })
    }

    override fun onResume() {
        super.onResume()
        night = isNight()
        applyNight()
        try {
            msm.removeOnActiveSessionsChangedListener(sessionsListener)
            if (hasAccess()) msm.addOnActiveSessionsChangedListener(sessionsListener, listener)
        } catch (e: SecurityException) {
        }
        refreshSessions()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        try {
            msm.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) {
        }
        controller?.unregisterCallback(callback)
        controller = null
    }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    // ------------------------------------------------------------------ UI construction

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    private fun iconButton(res: Int, onClick: () -> Unit): ImageButton = ImageButton(this).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val tv = TypedValue()
        this@MainActivity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        setBackgroundResource(tv.resourceId)
        setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        root = FrameLayout(this)
        bg = GradientDrawable(GradientDrawable.Orientation.TR_BL, cur.clone())
        root.background = bg

        row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(28f), dp(16f), dp(28f), dp(16f))
        }
        root.addView(row, FrameLayout.LayoutParams(-1, -1))

        // ---- clock side
        clockPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        timeV = TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(clockColor)
        }
        dateV = TextClock(this).apply {
            format12Hour = "EEEE, d MMMM"
            format24Hour = "EEEE, d MMMM"
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(0xAAFFFFFF.toInt())
        }
        accessHint = TextView(this).apply {
            text = "Turn on Desk Dock in the list so it can see what's playing."
            setTextColor(0x99FFFFFF.toInt())
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        accessBtn = Button(this).apply {
            text = "Allow music access"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        accessBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(accessHint)
            addView(accessBtn)
        }
        clockPane.addView(timeV, LinearLayout.LayoutParams(-2, -2))
        clockPane.addView(dateV, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6f) })
        clockPane.addView(accessBox, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18f) })
        row.addView(clockPane, LinearLayout.LayoutParams(0, -1, 1f))

        // ---- music side
        musicPane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        artV = ImageView(this).apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.07f)
                }
            }
            elevation = dp(14f).toFloat()
        }
        tvTitle = TextView(this).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        tvArtist = TextView(this).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(0xBBFFFFFF.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
        }
        bar = ProgressLine(this)
        posText = TextView(this).apply { setTextColor(0x99FFFFFF.toInt()) }
        durText = TextView(this).apply { setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.END }
        timesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(posText, LinearLayout.LayoutParams(0, -2, 1f))
            addView(durText, LinearLayout.LayoutParams(0, -2, 1f))
        }
        btnPrev = iconButton(R.drawable.ic_prev) { controller?.transportControls?.skipToPrevious() }
        btnPlay = iconButton(R.drawable.ic_play) {
            val c = controller
            if (c != null) {
                if (state?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
                else c.transportControls.play()
            }
        }
        btnNext = iconButton(R.drawable.ic_next) { controller?.transportControls?.skipToNext() }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(btnPrev); addView(btnPlay); addView(btnNext)
        }
        musicPane.addView(artV, LinearLayout.LayoutParams(10, 10))
        musicPane.addView(tvTitle, LinearLayout.LayoutParams(-2, -2))
        musicPane.addView(tvArtist, LinearLayout.LayoutParams(-2, -2))
        musicPane.addView(bar, LinearLayout.LayoutParams(-2, -2))
        musicPane.addView(timesRow, LinearLayout.LayoutParams(-2, -2))
        musicPane.addView(controls, LinearLayout.LayoutParams(-2, -2))
        row.addView(musicPane, LinearLayout.LayoutParams(0, -1, 1f))

        showPlaceholderArt()
        musicPane.visibility = View.GONE

        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) applyLayout(false)
        }
    }

    // ------------------------------------------------------------------ layout modes

    private fun effectiveMode() = if (!hasMusic) MODE_CLOCK else (userMode ?: MODE_SPLIT)

    private fun applyLayout(animate: Boolean) {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        if (animate) TransitionManager.beginDelayedTransition(root)

        val m = effectiveMode()
        clockPane.visibility = if (m == MODE_MUSIC) View.GONE else View.VISIBLE
        musicPane.visibility = if (m == MODE_CLOCK) View.GONE else View.VISIBLE

        val usable = (w - dp(56f)).toFloat()
        val paneW = if (m == MODE_SPLIT) usable / 2f else usable

        val tSize = min(paneW * 0.36f, h * 0.62f)
        timeV.setTextSize(TypedValue.COMPLEX_UNIT_PX, tSize)
        dateV.setTextSize(TypedValue.COMPLEX_UNIT_PX, min(tSize * 0.16f, h * 0.06f))

        val artSize = min(paneW * 0.62f, h * (if (m == MODE_MUSIC) 0.56f else 0.46f)).toInt()
        artV.layoutParams = LinearLayout.LayoutParams(artSize, artSize)
        fun fit(v: View, topMargin: Int = 0) {
            v.layoutParams = LinearLayout.LayoutParams(artSize, ViewGroup.LayoutParams.WRAP_CONTENT).apply { this.topMargin = topMargin }
        }
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, h * 0.052f)
        tvArtist.setTextSize(TypedValue.COMPLEX_UNIT_PX, h * 0.04f)
        posText.setTextSize(TypedValue.COMPLEX_UNIT_PX, h * 0.03f)
        durText.setTextSize(TypedValue.COMPLEX_UNIT_PX, h * 0.03f)
        fit(tvTitle, (h * 0.035f).toInt())
        fit(tvArtist, (h * 0.004f).toInt())
        bar.layoutParams = LinearLayout.LayoutParams(artSize, dp(4f)).apply { topMargin = (h * 0.03f).toInt() }
        fit(timesRow, dp(6f))
        fit(controls, (h * 0.01f).toInt())

        val playSz = (artSize * 0.24f).toInt()
        val sideSz = (artSize * 0.17f).toInt()
        val gap = (artSize * 0.07f).toInt()
        btnPrev.layoutParams = LinearLayout.LayoutParams(sideSz, sideSz).apply { marginEnd = gap }
        btnPlay.layoutParams = LinearLayout.LayoutParams(playSz, playSz)
        btnNext.layoutParams = LinearLayout.LayoutParams(sideSz, sideSz).apply { marginStart = gap }
    }

    // ------------------------------------------------------------------ media sessions

    private fun hasAccess(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return s != null && s.contains(packageName)
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { refreshSessions() }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = updateMeta(metadata)
        override fun onPlaybackStateChanged(s: PlaybackState?) = updateState(s)
        override fun onSessionDestroyed() = refreshSessions()
    }

    private fun refreshSessions() {
        if (!hasAccess()) {
            setController(null)
            return
        }
        val list = try {
            msm.getActiveSessions(listener)
        } catch (e: SecurityException) {
            emptyList()
        }
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.sessionToken == controller?.sessionToken }
            ?: list.firstOrNull { it.metadata != null }
        setController(best)
    }

    private fun setController(c: MediaController?) {
        if (c != null && c.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(callback)
        controller = c
        if (c == null) {
            updateMeta(null)
            updateState(null)
        } else {
            c.registerCallback(callback)
            updateMeta(c.metadata)
            updateState(c.playbackState)
        }
    }

    private fun updateState(s: PlaybackState?) {
        state = s
        btnPlay.setImageResource(if (s?.state == PlaybackState.STATE_PLAYING) R.drawable.ic_pause else R.drawable.ic_play)
        updateProgress()
    }

    private fun updateMeta(m: MediaMetadata?) {
        val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        val artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val had = hasMusic
        hasMusic = !title.isNullOrBlank()
        duration = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

        if (!hasMusic) {
            lastKey = ""; lastBmp = null
            showPlaceholderArt()
            artColors = null
            refreshGradient()
            if (had) applyLayout(true)
            return
        }
        tvTitle.text = title
        tvArtist.text = artist

        val bmp = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val uri = m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: m?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        val key = "$title|$artist"

        if (bmp != null) {
            if (bmp !== lastBmp || key != lastKey) setArt(bmp)
        } else if (key != lastKey) {
            lastBmp = null
            showPlaceholderArt()
            artColors = null
            refreshGradient()
            if (uri != null) loadArtFromUri(uri, key)
        }
        lastKey = key
        if (!had) applyLayout(true)
        updateProgress()
    }

    private fun loadArtFromUri(uri: String, key: String) {
        Thread {
            try {
                val bmp = if (uri.startsWith("http")) {
                    URL(uri).openStream().use { BitmapFactory.decodeStream(it) }
                } else {
                    contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
                }
                if (bmp != null) runOnUiThread { if (key == lastKey) setArt(bmp) }
            } catch (e: Exception) {
            }
        }.start()
    }

    // ------------------------------------------------------------------ album art + colours

    private fun showPlaceholderArt() {
        artV.setImageResource(R.drawable.ic_note)
        artV.scaleType = ImageView.ScaleType.CENTER_INSIDE
        artV.setPadding(dp(40f), dp(40f), dp(40f), dp(40f))
        artV.alpha = 0.6f
        artV.background = ColorDrawable(0x22FFFFFF)
    }

    private fun setArt(bmp: Bitmap) {
        lastBmp = bmp
        artV.setPadding(0, 0, 0, 0)
        artV.alpha = 1f
        artV.background = null
        artV.scaleType = ImageView.ScaleType.CENTER_CROP
        artV.setImageBitmap(bmp)
        val (a, b) = extractColors(bmp)
        artColors = intArrayOf(tone(a, 0.62f), tone(b, 0.34f), tone(b, 0.10f))
        refreshGradient()
    }

    /** Two dominant, distinct colours from the art (weighted toward saturated pixels). */
    private fun extractColors(src: Bitmap): Pair<Int, Int> {
        val s = Bitmap.createScaledBitmap(src, 24, 24, true)
        val px = IntArray(24 * 24)
        s.getPixels(px, 0, 24, 0, 0, 24, 24)
        class Bucket(var w: Float = 0f, var r: Float = 0f, var g: Float = 0f, var b: Float = 0f)

        val map = HashMap<Int, Bucket>()
        val hsv = FloatArray(3)
        for (p in px) {
            Color.colorToHSV(p, hsv)
            if (hsv[2] < 0.12f) continue
            val wt = 0.15f + hsv[1] * hsv[2]
            val key = ((Color.red(p) shr 5) shl 6) or ((Color.green(p) shr 5) shl 3) or (Color.blue(p) shr 5)
            val bk = map.getOrPut(key) { Bucket() }
            bk.w += wt; bk.r += Color.red(p) * wt; bk.g += Color.green(p) * wt; bk.b += Color.blue(p) * wt
        }
        if (map.isEmpty()) return Pair(0xFF3A3A3A.toInt(), 0xFF1A1A1A.toInt())
        fun col(b: Bucket) = Color.rgb((b.r / b.w).toInt(), (b.g / b.w).toInt(), (b.b / b.w).toInt())
        fun dist(x: Int, y: Int): Float {
            val dr = Color.red(x) - Color.red(y)
            val dg = Color.green(x) - Color.green(y)
            val db = Color.blue(x) - Color.blue(y)
            return sqrt((dr * dr + dg * dg + db * db).toFloat())
        }
        val sorted = map.values.sortedByDescending { it.w }
        val a = col(sorted[0])
        val b = sorted.drop(1).map { col(it) }.firstOrNull { dist(a, it) > 90f } ?: run {
            val h = FloatArray(3)
            Color.colorToHSV(a, h)
            h[0] = (h[0] + 40f) % 360f
            Color.HSVToColor(h)
        }
        return Pair(a, b)
    }

    /** Keep the hue, set the brightness; make colourful art richer, leave greys grey. */
    private fun tone(c: Int, v: Float): Int {
        val h = FloatArray(3)
        Color.colorToHSV(c, h)
        if (h[1] > 0.12f) h[1] = h[1].coerceIn(0.45f, 0.9f)
        h[2] = v
        return Color.HSVToColor(h)
    }

    private fun refreshGradient() {
        val target = if (night) NIGHT else (artColors ?: IDLE)
        val from = cur.clone()
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            val ev = ArgbEvaluator()
            addUpdateListener {
                val f = it.animatedFraction
                for (i in 0..2) cur[i] = ev.evaluate(f, from[i], target[i]) as Int
                bg.setColors(cur.clone())
            }
            start()
        }
    }

    // ------------------------------------------------------------------ night mode, progress, ticker

    private fun isNight(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (NIGHT_FROM > NIGHT_TO) h >= NIGHT_FROM || h < NIGHT_TO else h in NIGHT_FROM until NIGHT_TO
    }

    private fun applyNight() {
        clockColor = if (night) 0xFFFF4A3D.toInt() else 0xFFF6EFE6.toInt()
        timeV.setTextColor(clockColor)
        row.alpha = if (night) 0.6f else 1f
        val lp = window.attributes
        lp.screenBrightness = if (night) 0.04f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        refreshGradient()
    }

    private fun fmt(ms: Long): String {
        val t = ms / 1000
        return "%d:%02d".format(t / 60, t % 60)
    }

    private fun updateProgress() {
        val s = state
        var pos = s?.position ?: 0L
        if (s != null && s.state == PlaybackState.STATE_PLAYING) {
            pos += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        }
        if (pos < 0) pos = 0
        if (duration > 0 && pos > duration) pos = duration
        bar.frac = if (duration > 0) pos.toFloat() / duration else 0f
        posText.text = fmt(pos)
        durText.text = if (duration > 0) fmt(duration) else ""
    }

    private val ticker = object : Runnable {
        override fun run() {
            ticks++
            if (ticks % 6 == 0) refreshSessions()
            if (ticks % 120 == 0) {
                val n = isNight()
                if (n != night) { night = n; applyNight() }
                // Nudge everything a few pixels each minute to protect OLED screens from burn-in.
                row.translationX = Random.nextInt(-6, 7) * resources.displayMetrics.density
                row.translationY = Random.nextInt(-6, 7) * resources.displayMetrics.density
            }
            updateProgress()
            accessBox.visibility = if (hasAccess()) View.GONE else View.VISIBLE
            handler.postDelayed(this, 500)
        }
    }
}

/** Thin rounded progress line. */
class ProgressLine(context: Context) : View(context) {
    var frac = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, track)
        if (frac > 0f) canvas.drawRoundRect(0f, 0f, width * frac, height.toFloat(), r, r, fill)
    }
}
