package app.brootlite.tv

import android.app.Application
import android.graphics.Bitmap
import android.os.Build
import app.brootlite.tv.data.AppPrefs
import app.brootlite.tv.data.MultipassSyncClient
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class BrootLiteApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        val procName = currentProcessName()
        // CRITICAL: Never initialize main UI caches or Python inside :pyaddon or :potoken here.
        if (procName.endsWith(":pyaddon") || procName.endsWith(":potoken")) {
            return
        }

        // Purge any leftover sparse torrent cache from an unclean power-off
        Thread {
            runCatching {
                File(cacheDir, "torrents").deleteRecursively()
            }
            runCatching {
                AppPrefs.tryAutoImportStremioAuth(this@BrootLiteApp)
                MultipassSyncClient.tryAutoImportMultipassFile(this@BrootLiteApp)
                AppPrefs.tryImportKodiFavoritesOnce(this@BrootLiteApp)
            }
        }.start()

        Coil.setImageLoader {
            ImageLoader.Builder(this)
                .bitmapConfig(Bitmap.Config.RGB_565)
                .allowHardware(false)
                .allowRgb565(true)
                .crossfade(false)
                .okHttpClient { buildImageHttpClient() }
                .memoryCache {
                    MemoryCache.Builder(this)
                        .maxSizePercent(0.08) // Strictly capped at 8% Dalvik heap
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(File(cacheDir, "image_cache"))
                        .maxSizeBytes(40L * 1024L * 1024L)
                        .build()
                }
                .build()
        }
    }

    private fun currentProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return getProcessName() ?: packageName
        }
        return runCatching {
            File("/proc/self/cmdline").readBytes()
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .toString(Charsets.UTF_8)
                .trim()
        }.getOrDefault(packageName)
    }

    companion object {
        val DEFAULT_STREMIO_UA: String
            get() = "Mozilla/5.0 (Linux; Android 11; ${android.os.Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) Stremio/1.12.1 Chrome/120.0.0.0 Safari/537.36"

        @Volatile
        lateinit var instance: BrootLiteApp
            private set

        private val defaultUserAgentInterceptor = Interceptor { chain ->
            val orig = chain.request()
            val req = if (orig.header("User-Agent").isNullOrBlank()) {
                orig.newBuilder()
                    .header("User-Agent", DEFAULT_STREMIO_UA)
                    .build()
            } else {
                orig
            }
            chain.proceed(req)
        }

        val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .addInterceptor(defaultUserAgentInterceptor)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }

        val sharedPermissiveHttpClient: OkHttpClient by lazy {
            buildPermissiveHttpClient(22L)
        }

        fun buildPermissiveHttpClient(timeoutSec: Long = 20L): OkHttpClient {
            return runCatching {
                val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                })
                val sslCtx = SSLContext.getInstance("TLS")
                sslCtx.init(null, trustAll, SecureRandom())
                OkHttpClient.Builder()
                    .sslSocketFactory(sslCtx.socketFactory, trustAll[0] as X509TrustManager)
                    .hostnameVerifier { _, _ -> true }
                    .addInterceptor(defaultUserAgentInterceptor)
                    .connectTimeout(timeoutSec, TimeUnit.SECONDS)
                    .readTimeout(timeoutSec, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()
            }.getOrDefault(sharedHttpClient)
        }

        private fun buildImageHttpClient(): OkHttpClient {
            return buildPermissiveHttpClient(12L)
        }
    }
}
