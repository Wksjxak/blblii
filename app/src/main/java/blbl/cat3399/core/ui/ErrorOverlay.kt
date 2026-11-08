package blbl.cat3399.core.ui

import android.view.View
import android.widget.TextView
import blbl.cat3399.R

/**
 * 列表加载失败时的居中占位：TV 上按确认键即可重试，替代一直转圈或只弹 toast。
 * 布局里需要一个 id 为 error_view 的 TextView（默认 gone）。
 */
object ErrorOverlay {
    fun show(root: View, onRetry: () -> Unit) {
        val view = root.findViewById<TextView>(R.id.error_view) ?: return
        view.setOnClickListener { onRetry() }
        view.visibility = View.VISIBLE
        view.requestFocus()
    }

    fun hide(root: View) {
        val view = root.findViewById<TextView>(R.id.error_view) ?: return
        view.setOnClickListener(null)
        view.visibility = View.GONE
    }
}
