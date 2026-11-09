package blbl.cat3399.core.ui.popup

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.HorizontalScrollView

/**
 * 弹窗里包一排按钮的 HSV。
 * 默认 HSV 会在 ACTION_DOWN 就把事件吃掉用来判断是否要滚动，
 * 导致鼠标/触屏点不到里面的按钮。这里改成：只在真的发生横向拖动时才拦截，
 * 普通点按直接放行给按钮。
 */
internal class ClickPassHorizontalScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : HorizontalScrollView(context, attrs, defStyle) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var intercepting = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                intercepting = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(ev.x - downX) > touchSlop) {
                    intercepting = true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                intercepting = false
            }
        }
        return intercepting && super.onInterceptTouchEvent(ev)
    }
}
