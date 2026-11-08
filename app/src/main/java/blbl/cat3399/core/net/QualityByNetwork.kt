package blbl.cat3399.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities


/**
 * 移动数据下自动把清晰度压到 720P（qn=64），Wi-Fi/有线保持用户设置。
 * 用户手动在播放器里选过更高清晰度后由播放器自己处理，这里只影响"起始清晰度"。
 */
object QualityByNetwork {
    private const val CELLULAR_QN = 64

    fun isOnCellular(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    fun apply(context: Context, baseQn: Int): Int {
        if (!BiliClient.prefs.playerLowDataQualityEnabled) return baseQn
        if (!isOnCellular(context)) return baseQn
        return minOf(baseQn, CELLULAR_QN)
    }
}
