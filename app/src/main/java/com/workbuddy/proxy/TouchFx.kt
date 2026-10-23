package com.workbuddy.proxy

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/**
 * 统一的触感反馈：按下缩到 96%，抬手弹回。
 *
 * 不消费事件（onTouch 返回 false），所以点击、长按、选中文字都照常工作——
 * 只是在原有行为上叠了一层缩放。
 */
object TouchFx {

    fun attach(vararg views: View) {
        for (v in views) attachOne(v)
    }

    fun attachOne(v: View, scale: Float = 0.96f) {
        v.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().cancel()
                    view.animate().scaleX(scale).scaleY(scale).setDuration(90).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().cancel()
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                }
            }
            false
        }
    }

    /** 递归给整棵视图树里可点击的控件挂上反馈。 */
    fun applyTo(view: View) {
        if (view.isClickable) attachOne(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyTo(view.getChildAt(i))
        }
    }

    /** 列表项入场：淡入 + 轻微上移。 */
    fun enter(view: View, delayMs: Long = 0L) {
        view.alpha = 0f
        view.translationY = 14f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delayMs)
            .setDuration(220)
            .start()
    }

    /** 页面切换：淡入 + 从下方 10dp 滑入。 */
    fun pageIn(view: View) {
        view.alpha = 0f
        view.translationY = 10f
        view.animate().alpha(1f).translationY(0f).setDuration(180).start()
    }
}
