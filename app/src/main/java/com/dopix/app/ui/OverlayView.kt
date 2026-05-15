package com.dopix.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.dopix.app.DopixState
import com.dopix.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The floating overlay widget view.
 * Draggable, nearly transparent when idle, glows when active.
 */
class OverlayView(context: Context) : FrameLayout(context) {

    interface OverlayInteractionListener {
        fun onOverlayMoved(x: Int, y: Int)
        fun onOverlayTapped()
    }

    private val overlayIcon: ImageView
    private val pulseRing: View
    private var listener: OverlayInteractionListener? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateJob: Job? = null
    private var pulseAnimator: ValueAnimator? = null

    // Touch tracking for drag vs tap
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchStartRawX = 0f
    private var touchStartRawY = 0f
    private var isDragging = false

    // Window position (updated externally by OverlayService)
    var windowX: Int = 16
    var windowY: Int = 200

    companion object {
        private const val TAP_SLOP_PX = 15
        private const val IDLE_ALPHA = 0.3f
        private const val ACTIVE_ALPHA = 1.0f
        private const val PULSE_DURATION_MS = 900L
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.overlay_widget, this, true)
        overlayIcon = findViewById(R.id.overlayIcon)
        pulseRing = findViewById(R.id.pulseRing)

        setupTouchListener()
        observeState()
        setIdleAppearance()
    }

    fun setListener(l: OverlayInteractionListener) {
        listener = l
    }

    // -------------------------------------------------------------------------
    // Appearance
    // -------------------------------------------------------------------------

    private fun setIdleAppearance() {
        overlayIcon.setImageResource(R.drawable.ic_dopix)
        overlayIcon.alpha = IDLE_ALPHA
        overlayIcon.setBackgroundResource(R.drawable.bg_overlay_idle)
        stopPulse()
        pulseRing.visibility = View.INVISIBLE
    }

    private fun setActiveAppearance() {
        overlayIcon.setImageResource(R.drawable.ic_mic_active)
        overlayIcon.alpha = ACTIVE_ALPHA
        overlayIcon.setBackgroundResource(R.drawable.bg_overlay_active)
        pulseRing.visibility = View.VISIBLE
        startPulse()
    }

    private fun setSleepingAppearance() {
        overlayIcon.setImageResource(R.drawable.ic_dopix)
        overlayIcon.alpha = IDLE_ALPHA * 0.7f
        overlayIcon.setBackgroundResource(R.drawable.bg_overlay_idle)
        stopPulse()
        pulseRing.visibility = View.INVISIBLE
    }

    private fun setConnectingAppearance() {
        overlayIcon.setImageResource(R.drawable.ic_dopix)
        overlayIcon.alpha = 0.6f
        overlayIcon.setBackgroundResource(R.drawable.bg_overlay_idle)
        pulseRing.visibility = View.VISIBLE
        startSlowPulse()
    }

    // -------------------------------------------------------------------------
    // Pulse animation
    // -------------------------------------------------------------------------

    private fun startPulse() {
        stopPulse()
        pulseAnimator = ValueAnimator.ofFloat(0.3f, 1.0f, 0.3f).apply {
            duration = PULSE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                pulseRing.alpha = anim.animatedValue as Float
                val scale = 1.0f + (anim.animatedValue as Float) * 0.3f
                pulseRing.scaleX = scale
                pulseRing.scaleY = scale
            }
            start()
        }
    }

    private fun startSlowPulse() {
        stopPulse()
        pulseAnimator = ObjectAnimator.ofFloat(overlayIcon, "alpha", IDLE_ALPHA, 0.8f).apply {
            duration = 1200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        } as ValueAnimator
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseRing.alpha = 0f
        pulseRing.scaleX = 1f
        pulseRing.scaleY = 1f
    }

    // -------------------------------------------------------------------------
    // State observation
    // -------------------------------------------------------------------------

    private fun observeState() {
        stateJob = scope.launch {
            DopixState.mode.collectLatest { mode ->
                when (mode) {
                    DopixState.Mode.ACTIVE -> setActiveAppearance()
                    DopixState.Mode.SLEEPING -> setSleepingAppearance()
                    DopixState.Mode.CONNECTING -> setConnectingAppearance()
                    DopixState.Mode.STOPPED -> setIdleAppearance()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Touch handling (drag + tap)
    // -------------------------------------------------------------------------

    private fun setupTouchListener() {
        setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    touchStartRawX = event.rawX
                    touchStartRawY = event.rawY
                    isDragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchStartRawX
                    val dy = event.rawY - touchStartRawY

                    if (!isDragging && (Math.abs(dx) > TAP_SLOP_PX || Math.abs(dy) > TAP_SLOP_PX)) {
                        isDragging = true
                    }

                    if (isDragging) {
                        windowX = (windowX + dx.toInt())
                        windowY = (windowY + dy.toInt())
                        touchStartRawX = event.rawX
                        touchStartRawY = event.rawY
                        listener?.onOverlayMoved(windowX, windowY)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        listener?.onOverlayTapped()
                        performClick()
                    }
                    isDragging = false
                    true
                }

                else -> false
            }
        }
    }

    fun destroy() {
        stateJob?.cancel()
        stopPulse()
    }
}
