package com.dchat.app.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import com.dchat.app.ui.lang.Lang
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** 一次定位结果：经纬度 + 反查出来的地址（反查失败时 title 用坐标兜底） */
data class GeoPlace(
    val lat: Double,
    val lng: Double,
    val title: String,
    val detail: String
)

/**
 * 只依赖系统 LocationManager + Geocoder 的定位（不引入任何第三方地图 SDK）。
 *
 * 流程与产品要求一致：
 * 1. 先取 getLastKnownLocation（立即返回，可能为空 / 较旧）；
 * 2. 再 requestSingleUpdate 要一次更精确的当前位置，最多等 8 秒；
 * 3. 超时或失败就用最后已知位置；
 * 4. 拿到坐标后 Geocoder.getFromLocation 反查地址，失败只显示坐标。
 *
 * 调用方必须先确保已获得 ACCESS_FINE_LOCATION 或 ACCESS_COARSE_LOCATION。
 */
object LocationPicker {

    private const val TIMEOUT_MS = 8_000L

    suspend fun locate(ctx: Context): Result<GeoPlace> = withContext(Dispatchers.IO) {
        val L = Lang.current
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return@withContext Result.failure(
                    IllegalStateException(L.misc.locServiceUnavailable)
                )

            val providers = enabledProviders(lm)
            val last = bestLastKnown(lm, providers)
            val fresh = if (providers.isEmpty()) {
                null
            } else {
                withTimeoutOrNull(TIMEOUT_MS) { requestSingle(lm, providers) }
            }
            val loc = fresh ?: last
                ?: return@withContext Result.failure(
                    IllegalStateException(L.misc.locSwitchOff)
                )

            val address = reverseGeocode(ctx, loc.latitude, loc.longitude)
            Result.success(
                GeoPlace(
                    lat = loc.latitude,
                    lng = loc.longitude,
                    title = address?.first?.takeIf { it.isNotBlank() }
                        ?: formatCoord(loc.latitude, loc.longitude),
                    detail = address?.second?.takeIf { it.isNotBlank() }
                        ?: L.misc.locGotCurrent
                )
            )
        } catch (e: SecurityException) {
            Result.failure(IllegalStateException(L.misc.locNoPermission))
        } catch (e: Exception) {
            Result.failure(IllegalStateException(e.message ?: L.misc.locFailed))
        }
    }

    private fun enabledProviders(lm: LocationManager): List<String> {
        val all = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        return all.filter { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(lm: LocationManager, providers: List<String>): Location? {
        var best: Location? = null
        for (p in providers) {
            val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull() ?: continue
            val cur = best
            if (cur == null || loc.time > cur.time) best = loc
        }
        return best
    }

    /**
     * 要一次当前位置。优先 GPS（更精确），没有 GPS 时用网络定位；
     * 拿到第一条就 removeUpdates 并返回。超时由调用方 withTimeoutOrNull 取消，
     * 取消时会通过 invokeOnCancellation 把监听移除。
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun requestSingle(
        lm: LocationManager,
        providers: List<String>
    ): Location? = suspendCancellableCoroutine { cont ->
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!cont.isActive) return
                runCatching { lm.removeUpdates(this) }
                cont.resume(location)
            }

            @Deprecated("Deprecated in API 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit
        }
        cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }

        val preferred = when {
            providers.contains(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            providers.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> providers.firstOrNull()
        }
        if (preferred == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        val requested = try {
            lm.requestSingleUpdate(preferred, listener, Looper.getMainLooper())
            true
        } catch (_: Exception) {
            false
        }
        if (requested) return@suspendCancellableCoroutine

        // 个别 ROM 的 requestSingleUpdate 不可用：退化成持续监听，拿到第一条后由 listener 自行移除
        try {
            providers.forEach {
                lm.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper())
            }
        } catch (_: Exception) {
            runCatching { lm.removeUpdates(listener) }
            if (cont.isActive) cont.resume(null)
        }
    }

    /** 反查地址；失败 / 超时 / 没有 Geocoder 都返回 null（由调用方显示坐标） */
    @Suppress("DEPRECATION")
    private fun reverseGeocode(ctx: Context, lat: Double, lng: Double): Pair<String, String>? =
        runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            val geocoder = Geocoder(ctx, Locale.getDefault())
            val list = geocoder.getFromLocation(lat, lng, 1)
            val a = list?.firstOrNull() ?: return@runCatching null

            // 主标题：市 + 区（QQ 位置卡片就是「成都市武侯区…」这种粒度）
            val city = listOfNotNull(a.locality, a.subAdminArea, a.adminArea)
                .firstOrNull { it.isNotBlank() }
            val district = a.subLocality?.takeIf { it.isNotBlank() }
            val title = buildString {
                if (!city.isNullOrBlank()) append(city)
                if (!district.isNullOrBlank() && district != city) append(district)
            }.ifBlank { a.thoroughfare.orEmpty() }

            // 副标题：完整地址行；没有就退化成坐标
            val detail = a.getAddressLine(0)?.takeIf { it.isNotBlank() }
                ?: formatCoord(a.latitude, a.longitude)

            if (title.isBlank() && detail.isBlank()) null else {
                Pair(title.ifBlank { detail }, detail.ifBlank { title })
            }
        }.getOrNull()

    private fun formatCoord(lat: Double, lng: Double): String =
        String.format(Locale.US, "%.5f, %.5f", lat, lng)
}
