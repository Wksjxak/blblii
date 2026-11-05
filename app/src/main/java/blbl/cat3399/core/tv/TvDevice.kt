package blbl.cat3399.core.tv

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.input.InputManager
import android.view.InputDevice

fun Context.isTvDevice(): Boolean {
    val appContext = applicationContext
    val packageManager = appContext.packageManager
    if (packageManager.hasSystemFeature(FEATURE_TELEVISION)) return true
    if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return true

    val uiModeManager = appContext.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
}

/**
 * 是否连接了鼠标 / 触摸板这类指向设备。
 *
 * TV 设备默认关闭触摸手势，只靠遥控器焦点操作；接了鼠标后左键点击应当能
 * 唤出播放器控制栏，因此需要把「指向设备存在」纳入手势启用判断。
 */
fun Context.hasPointingDeviceInput(): Boolean {
    val inputManager =
        applicationContext.getSystemService(Context.INPUT_SERVICE) as? InputManager
            ?: return false
    for (id in inputManager.inputDeviceIds) {
        val device = inputManager.getInputDevice(id) ?: continue
        val sources = device.sources
        if ((sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) return true
        if ((sources and InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD) return true
    }
    return false
}

private const val FEATURE_TELEVISION = "android.hardware.type.television"
