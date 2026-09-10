package io.github.auag0.hidemocklocation

import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XC_MethodReplacement.returnConstant
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedBridge.hookAllConstructors
import de.robv.android.xposed.XposedBridge.hookAllMethods
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XposedHelpers.findClass
import de.robv.android.xposed.XposedHelpers.getIntField
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.getStaticIntField
import de.robv.android.xposed.XposedHelpers.setIntField
import de.robv.android.xposed.XposedHelpers.setObjectField
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.auag0.hidemocklocation.XposedUtils.invokeOriginalMethod

class Main : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookLocationMethods(lpparam.classLoader)
        if (lpparam.packageName != "android") {
            hookSettingsMethods(lpparam.classLoader)
        }

        if (lpparam.packageName == "com.oplus.engineernetwork") {
            hookOplusNetworkMethods(lpparam.classLoader)
        }

        // 针对 com.oplus.ota 应用注入
        if (lpparam.packageName == "com.oplus.ota") {
            hookOplusOtaMethods(lpparam.classLoader)
        }
    }

    /**
     * 防混淆捕获 OTA URL：
     * 直接 Hook 系统底层 java.net.URL 的构造函数，并精准过滤 .zip 升级包链接
     */
    private fun hookOplusOtaMethods(classLoader: ClassLoader) {
        try {
            val urlClass = findClass("java.net.URL", classLoader)
            hookAllConstructors(urlClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val urlInstance = param.thisObject ?: return
                    val urlString = urlInstance.toString()

                    // 1. 必须是 HTTPS 协议
                    if (urlString.startsWith("https://", ignoreCase = true)) {
                        // 剥离 URL 结尾可能携带的参数 (例如 ?token=xxx&verify=yyy)
                        val urlWithoutQuery = urlString.substringBefore("?")

                        // 2. 判断路径结尾是否为 .zip 或 URL 中明确包含 .zip?
                        if (urlWithoutQuery.endsWith(".zip", ignoreCase = true) || urlString.contains(".zip?", ignoreCase = true)) {
                            XposedBridge.log("OplusOTA [OTA Zip Captured]: 捕获到升级包下载 URL -> $urlString")
                        }
                    }
                }
            })
            XposedBridge.log("OplusOTA: 成功 Hook java.net.URL 构造函数")
        } catch (e: Throwable) {
            XposedBridge.log("OplusOTA: Hook java.net.URL 失败: ${e.message}")
        }
    }

    private fun hookOplusNetworkMethods(classLoader: ClassLoader) {
        // 1. Hook Companion.getMIsEncrypt (Getter 拦截)
        try {
            XposedHelpers.findAndHookMethod(
                "com.oplus.engineernetwork.HostApplication\$Companion",
                classLoader,
                "getMIsEncrypt",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = false
                    }
                }
            )
        } catch (e: Throwable) { XposedBridge.log("Hook getMIsEncrypt 失败: ${e.message}") }

        // 2. Hook MainActivity.setDecryptionResult (状态修正)
        try {
            XposedHelpers.findAndHookMethod(
                "com.oplus.engineernetwork.MainActivity",
                classLoader,
                "setDecryptionResult",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        XposedHelpers.setBooleanField(param.thisObject, "mIsEncrypt", false)
                    }
                }
            )
        } catch (e: Throwable) { XposedBridge.log("Hook setDecryptionResult 失败: ${e.message}") }

        // 3. Hook HostApplication.onCreate (初始化抢占)
        try {
            XposedHelpers.findAndHookMethod(
                "com.oplus.engineernetwork.HostApplication",
                classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val companionClass = XposedHelpers.findClass("com.oplus.engineernetwork.HostApplication\$Companion", classLoader)
                        val companionInstance = XposedHelpers.getStaticObjectField(companionClass, "INSTANCE")
                        XposedHelpers.callMethod(companionInstance, "setMIsEncrypt", false)
                        XposedBridge.log("OplusEngineerNetworkHook: onCreate 抢占式状态重置完成")
                    }
                }
            )
        } catch (e: Throwable) { XposedBridge.log("Hook onCreate 失败: ${e.message}") }
    }

    private fun hookLocationMethods(classLoader: ClassLoader) {
        val locationClass = findClass(
            "android.location.Location",
            classLoader
        )
        // Hooked android.location.Location isFromMockProvider()
        hookAllMethods(locationClass, "isFromMockProvider", returnConstant(false))
        // Hooked android.location.Location isMock()
        hookAllMethods(locationClass, "isMock", returnConstant(false))
        // Hooked android.location.Location setIsFromMockProvider()
        hookAllMethods(locationClass, "setIsFromMockProvider", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val isFromMockProvider = param.args[0] as Boolean?
                if (isFromMockProvider == true) {
                    param.args[0] = false
                }
            }
        })
        // Hooked android.location.Location setMock()
        hookAllMethods(locationClass, "setMock", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val mock = param.args[0] as Boolean?
                if (mock == true) {
                    param.args[0] = false
                }
            }
        })
        // Hooked android.location.Location getExtras()
        hookAllMethods(locationClass, "getExtras", object : XC_MethodReplacement() {
            override fun replaceHookedMethod(param: MethodHookParam): Bundle? {
                var extras: Bundle? = param.invokeOriginalMethod() as Bundle?
                extras = getPatchedBundle(extras)
                return extras
            }
        })
        // Hooked android.location.Location setExtras()
        hookAllMethods(locationClass, "setExtras", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val extras = param.args[0] as Bundle?
                param.args[0] = getPatchedBundle(extras)
            }
        })
        // Hooked android.location.Location set()
        hookAllMethods(locationClass, "set", object : XC_MethodHook() {
            val HAS_MOCK_PROVIDER_MASK = getStaticIntField(locationClass, "HAS_MOCK_PROVIDER_MASK")
            override fun afterHookedMethod(param: MethodHookParam) {
                var mFieldsMask = getIntField(param.thisObject, "mFieldsMask")
                mFieldsMask = mFieldsMask and HAS_MOCK_PROVIDER_MASK.inv()
                setIntField(param.thisObject, "mFieldsMask", mFieldsMask)

                var mExtras = getObjectField(param.thisObject, "mExtras") as Bundle?
                mExtras = getPatchedBundle(mExtras)
                setObjectField(param.thisObject, "mExtras", mExtras)
            }
        })
    }

    private fun hookSettingsMethods(classLoader: ClassLoader) {
        // Hooked android.provider.Settings.* getStringForUser()
        val settingsClassNames = arrayOf(
            "android.provider.Settings.Secure",
            "android.provider.Settings.System",
            "android.provider.Settings.Global",
            "android.provider.Settings.NameValueCache"
        )
        settingsClassNames.forEach {
            val clazz = findClass(it, classLoader)
            hookAllMethods(clazz, "getStringForUser", object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: MethodHookParam): Any? {
                    val name: String? = param.args[1] as? String?
                    return when (name) {
                        "mock_location" -> "0"
                        else -> try {
                            param.invokeOriginalMethod()
                        } catch (e: Throwable) {
                            param.throwable = e
                            null
                        }
                    }
                }
            })
        }
    }

    /**
     * if "mockLocation" containsKey in the given bundle, set it to false
     *
     * @param origBundle original Bundle object
     * @return Bundle with "mockLocation" set to false
     */
    private fun getPatchedBundle(origBundle: Bundle?): Bundle? {
        if (origBundle?.containsKey("mockLocation") == true) {
            origBundle.putBoolean("mockLocation", false)
        }
        return origBundle
    }
}
