package com.arny.mlscanner.data.security

import android.os.Build
import java.io.File

object RootChecker {
    private const val BINARY_SU = "su"
    private val dangerousPackages = listOf(
        "com.koushikdutta.superuser",
        "com.thirdparty.superuser",
        "com.koushikdutta.rommanager",
        "com.koushikdutta.rommanager.license",
        "com.dimonvideo.luckypatcher",
        "com.chelpus.lackypatch",
        "com.ramdroid.appquarantine",
        "com.ramdroid.appquarantinepro",
        "com.android.vending.billing.InAppBillingService.COIN",
        "com.android.vending.billing.InAppBillingService.LUCK",
        "com.chelpus.luckypatcher"
    )

    fun isDeviceRooted(): Boolean {
        return checkTestKeys() ||
                checkSuperUserApk() ||
                checkSuBinary() ||
                checkBusyBox() ||
                checkMagisk()
    }

    private fun checkTestKeys(): Boolean {
        val buildTags = Build.TAGS
        return buildTags != null && buildTags.contains("test-keys")
    }

    private fun checkSuperUserApk(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("which su")
            val result = process.waitFor()
            result == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun checkSuBinary(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su",
            "/su/bin/su"
        )
        return paths.any { File(it).exists() }
    }

    private fun checkBusyBox(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "busybox"))
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun checkMagisk(): Boolean {
        val magiskPaths = listOf(
            "/sbin/.magisk",
            "/dev/.magisk.unblock",
            "/system/bin/magisk"
        )
        return magiskPaths.any { File(it).exists() }
    }

    /** Проверка наличия отладчика */
    fun isDebuggerConnected(): Boolean {
        return android.os.Debug.isDebuggerConnected()
    }
}
