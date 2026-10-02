package ahut.wifiauth.android.ui

import android.view.View
import android.view.Window
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.max

/** Edge-to-edge window setup and IME helpers for the main screen. */
object WindowUi {
    /**
     * Enables edge-to-edge drawing and applies safe insets to [root].
     * The root's existing padding is treated as the content's baseline spacing.
     */
    fun enableEdgeToEdge(window: Window, root: View) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safeBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(
                baseLeft + safeBars.left,
                baseTop + safeBars.top,
                baseRight + safeBars.right,
                baseBottom + max(safeBars.bottom, ime.bottom)
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** Scrolls [target] into the visible area, for example on focus while the IME is open. */
    fun scrollIntoView(scrollView: ScrollView, target: View) {
        target.post {
            val targetLocation = IntArray(2)
            val scrollLocation = IntArray(2)
            target.getLocationInWindow(targetLocation)
            scrollView.getLocationInWindow(scrollLocation)
            val top = targetLocation[1] - scrollLocation[1]
            val bottom = top + target.height
            val viewportBottom = scrollView.height - scrollView.paddingBottom
            when {
                bottom > viewportBottom -> scrollView.smoothScrollBy(0, bottom - viewportBottom)
                top < scrollView.paddingTop -> scrollView.smoothScrollBy(0, top - scrollView.paddingTop)
            }
        }
    }

    /** Hides the software keyboard after a submit or screen transition when needed. */
    fun hideKeyboard(view: View) {
        val inputMethodManager =
            view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        inputMethodManager?.hideSoftInputFromWindow(view.windowToken, 0)
    }
}
