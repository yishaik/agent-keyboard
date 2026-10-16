package helium314.keyboard.agent

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ChecksumCalculator
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.protectedPrefs
import java.io.File

/**
 * Glide typing on by default. When the APK was built with -Pagent.bundleGlide=true, it carries
 * Google's gesture library in assets/glide/<abi>/; on first start we put it where HeliBoard's
 * "Load gesture typing library" would (filesDir/libjni_latinime.so) and record its checksum,
 * so JniUtils loads it. Builds without the asset behave exactly like HeliBoard.
 */
object GlideInstaller {
    private const val TAG = "GlideInstaller"

    fun installBundled(context: Context) {
        runCatching {
            val target = File(context.filesDir, JniUtils.JNI_LIB_IMPORT_FILE_NAME)
            if (target.isFile) return
            val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return
            val asset = "glide/$abi/libjni_latinimegoogle.so"
            val input = runCatching { context.assets.open(asset) }.getOrNull() ?: return
            val tmp = File(context.filesDir, "glide.tmp")
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val checksum = ChecksumCalculator.checksum(tmp) ?: return
            // checksum goes to the same prefs JniUtils reads (device-protected storage)
            context.protectedPrefs().edit(commit = true) { putString(Settings.PREF_LIBRARY_CHECKSUM, checksum) }
            tmp.renameTo(target)
            target.setReadOnly() // Android 14 safer dynamic code loading
            Log.i(TAG, "installed bundled glide library for $abi")
        }.onFailure { Log.w(TAG, "could not install glide library", it) }
    }
}
