package com.vendetta.xposed

import android.content.res.AssetManager
import android.content.res.XModuleResources
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URL

@Serializable
data class CustomLoadUrl(
    val enabled: Boolean,
    val url: String
)
@Serializable
data class LoaderConfig(
    val customLoadUrl: CustomLoadUrl,
    val loadReactDevTools: Boolean
)

class Main : IXposedHookZygoteInit, IXposedHookLoadPackage {
    private lateinit var modResources: XModuleResources

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modResources = XModuleResources.createInstance(startupParam.modulePath, null)
    }

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {
        val themeModule = param.classLoader.loadClass("com.discord.theme.ThemeModule")
        val darkTheme = param.classLoader.loadClass("com.discord.theme.DarkTheme")

        XposedBridge.hookMethod(
            themeModule.getDeclaredMethod("updateTheme", String::class.java),
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[0] == "dark" || param.args[0] == "light") {
                        return
                    }

                    val json = try { 
                        Json.decodeFromString<Map<String, UInt>>(param.args[0] as String)
                    } catch (_: Exception) {
                        XposedBridge.log("Failed to parse JSON")
                        return
                    }

                    // iterate the object
                    for ((key, value) in json) {
                        val method = "get" + key.split("_").joinToString("") { it.toLowerCase().replaceFirstChar { it.uppercase() } };

                        XposedBridge.log("Hooking method $method -> $value")
                        XposedBridge.hookMethod(
                            darkTheme.getDeclaredMethod(method),
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    param.result = value.toInt()
                                }
                            }
                        )
                    }

                    param.args[0] = "dark"
                }
            }
        )

        // XposedBridge.hookMethod(
        //         darkTheme.getDeclaredMethod(convertSnakeToCamel("TEXT_NORMAL")),
        //         object : XC_MethodHook() {
        //             override fun beforeHookedMethod(param: MethodHookParam) {
        //                 param.result = 0xFFFF0000.toInt()
        //             }
        //         }
        // )

        val catalystInstanceImpl = param.classLoader.loadClass("com.facebook.react.bridge.CatalystInstanceImpl")

        val loadScriptFromAssets = catalystInstanceImpl.getDeclaredMethod(
            "jniLoadScriptFromAssets",
            AssetManager::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        )

        val loadScriptFromFile = catalystInstanceImpl.getDeclaredMethod(
            "jniLoadScriptFromFile",
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        ).apply { isAccessible = true }

        val cache = File(param.appInfo.dataDir, "cache").also { it.mkdirs() }
        val vendetta = File(cache, "vendetta.js")

        lateinit var config: LoaderConfig
        val files = File(param.appInfo.dataDir, "files").also { it.mkdirs() }
        val configFile = File(files, "vendetta_loader.json")
        try {
            config = Json.decodeFromString(configFile.readText())
        } catch (_: Exception) {
            config = LoaderConfig(
                customLoadUrl = CustomLoadUrl(
                    enabled = false,
                    url = "http://localhost:4040/vendetta.js"
                ),
                loadReactDevTools = false
            )
            configFile.writeText(Json.encodeToString(config))
        }

        val patch = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val url = if (config.customLoadUrl.enabled) config.customLoadUrl.url else "https://raw.githubusercontent.com/vendetta-mod/builds/master/vendetta.js"
                try {
                    URL(url).openStream().use { input ->
                        vendetta.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (_: Exception) {
                    Log.i("Vendetta", "Failed to download Vendetta from $url")
                }

                XposedBridge.invokeOriginalMethod(loadScriptFromAssets, param.thisObject, arrayOf(modResources.assets, "assets://js/modules.js", true))
                XposedBridge.invokeOriginalMethod(loadScriptFromAssets, param.thisObject, arrayOf(modResources.assets, "assets://js/identity.js", true))
                if (config.loadReactDevTools)
                    XposedBridge.invokeOriginalMethod(loadScriptFromAssets, param.thisObject, arrayOf(modResources.assets, "assets://js/devtools.js", true))
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    XposedBridge.invokeOriginalMethod(loadScriptFromFile, param.thisObject, arrayOf(vendetta.absolutePath, vendetta.absolutePath, param.args[2]))
                } catch (_: Exception) {}
            }
        }

        XposedBridge.hookMethod(loadScriptFromAssets, patch)
        XposedBridge.hookMethod(loadScriptFromFile, patch)
    }
}
