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
     * 全面捕获 OTA 升级包的完整 URL、请求头 (Headers) 及 POST 参数
     */
    private fun hookOplusOtaMethods(classLoader: ClassLoader) {
        // 1. Hook java.net.URL 构造函数（防止日志被截断，打印原始字符数组/完整字符串）
        try {
            val urlClass = findClass("java.net.URL", classLoader)
            hookAllConstructors(urlClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val urlInstance = param.thisObject ?: return
                    val urlString = urlInstance.toString()

                    if (urlString.startsWith("https://", ignoreCase = true)) {
                        val urlWithoutQuery = urlString.substringBefore("?")
                        if (urlWithoutQuery.endsWith(".zip", ignoreCase = true) || urlString.contains(".zip?", ignoreCase = true)) {
                            // 分段打印，防止系统 logcat 日志超长截断
                            XposedBridge.log("OplusOTA [URL Full Length: ${urlString.length}] -> $urlString")
                        }
                    }
                }
            })
        } catch (e: Throwable) {
            XposedBridge.log("OplusOTA: Hook java.net.URL 失败: ${e.message}")
        }

        // 2. Hook okhttp3.Request (尝试捕获 OkHttp 的完整 Headers 与 Method)
        try {
            val requestClass = findClass("okhttp3.Request", classLoader)
            hookAllMethods(requestClass, "toString", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val reqStr = param.result as? String ?: return
                    if (reqStr.contains(".zip")) {
                        XposedBridge.log("OplusOTA [OkHttp Request Detail]: $reqStr")
                    }
                }
            })

            // 尝试通过 Hook RealCall 打印完整 Request Header 详情
            val realCallClass = findClass("okhttp3.RealCall", classLoader)
            val logCallHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val request = getObjectField(param.thisObject, "originalRequest") ?: return
                        val url = getObjectField(request, "url").toString()
                        if (url.contains(".zip")) {
                            val headers = getObjectField(request, "headers")
                            val method = getObjectField(request, "method")
                            XposedBridge.log("OplusOTA [OkHttp Method]: $method")
                            XposedBridge.log("OplusOTA [OkHttp Headers]: $headers")
                            XposedBridge.log("OplusOTA [OkHttp Target URL]: $url")
                        }
                    } catch (_: Throwable) {}
                }
            }
            hookAllMethods(realCallClass, "execute", logCallHook)
            hookAllMethods(realCallClass, "enqueue", logCallHook)
        } catch (e: Throwable) {
            XposedBridge.log("OplusOTA: Hook OkHttp Request 尝试忽略/失败: ${e.message}")
        }

        // 3. Hook 通用系统网络层 HttpURLConnection 的 RequestProperty (获取全部请求头)
        try {
            val urlConnClass = findClass("java.net.URLConnection", classLoader)
            XposedHelpers.findAndHookMethod(
                urlConnClass,
                "addRequestProperty",
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String
                        val value = param.args[1] as? String
                        XposedBridge.log("OplusOTA [Header Add]: $key: $value")
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                urlConnClass,
                "setRequestProperty",
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String
                        val value = param.args[1] as? String
                        XposedBridge.log("OplusOTA [Header Set]: $key: $value")
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("OplusOTA: Hook URLConnection RequestProperty 失败: ${e.message}")
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
