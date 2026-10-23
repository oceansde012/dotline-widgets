package io.github.oceansde012.dotline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint

/**
 * Dot-matrix pictures for the weather / buds widgets. The weather icons are the dot centres of
 * widgets_v2/dots.py build() (wxo_*), the earbuds are drawn the same way (rings + stems); all in shares
 * of a square icon box. Rendered to a small ARGB bitmap (<= 128 px, 64 KB) like the player art.
 */
object WeatherArt {
    const val WX_DOT = 0.026f     // dots.py DOT is 0.024 of the box; a touch bolder at widget size
    const val BUDS_DOT = 0.036f
    private const val MAX_PX = 128

    val SUN = floatArrayOf(0.700f, 0.500f, 0.688f, 0.567f, 0.655f, 0.627f, 0.603f, 0.671f, 0.540f, 0.696f, 0.472f, 0.698f, 0.407f, 0.677f, 0.353f, 0.635f, 0.316f, 0.578f, 0.300f, 0.511f, 0.308f, 0.444f, 0.338f, 0.382f, 0.387f, 0.335f, 0.449f, 0.307f, 0.517f, 0.301f, 0.583f, 0.318f, 0.639f, 0.357f, 0.679f, 0.412f, 0.500f, 0.190f, 0.500f, 0.070f, 0.719f, 0.281f, 0.804f, 0.196f, 0.810f, 0.500f, 0.930f, 0.500f, 0.719f, 0.719f, 0.804f, 0.804f, 0.500f, 0.810f, 0.500f, 0.930f, 0.281f, 0.719f, 0.196f, 0.804f, 0.190f, 0.500f, 0.070f, 0.500f, 0.281f, 0.281f, 0.196f, 0.196f)
    val MOON = floatArrayOf(0.828f, 0.590f, 0.803f, 0.654f, 0.767f, 0.711f, 0.720f, 0.760f, 0.664f, 0.798f, 0.601f, 0.825f, 0.535f, 0.838f, 0.467f, 0.838f, 0.400f, 0.825f, 0.338f, 0.799f, 0.281f, 0.760f, 0.234f, 0.712f, 0.197f, 0.655f, 0.173f, 0.592f, 0.161f, 0.525f, 0.163f, 0.457f, 0.178f, 0.391f, 0.206f, 0.329f, 0.246f, 0.274f, 0.296f, 0.228f, 0.354f, 0.193f, 0.418f, 0.170f, 0.660f, 0.640f, 0.592f, 0.632f, 0.528f, 0.607f, 0.471f, 0.567f, 0.427f, 0.515f, 0.396f, 0.453f, 0.381f, 0.386f, 0.383f, 0.317f, 0.402f, 0.251f, 0.437f, 0.191f)
    val CLOUD = floatArrayOf(0.249f, 0.720f, 0.196f, 0.678f, 0.171f, 0.616f, 0.180f, 0.549f, 0.222f, 0.496f, 0.284f, 0.471f, 0.317f, 0.428f, 0.348f, 0.366f, 0.399f, 0.319f, 0.464f, 0.293f, 0.533f, 0.293f, 0.598f, 0.317f, 0.650f, 0.363f, 0.682f, 0.424f, 0.709f, 0.470f, 0.774f, 0.488f, 0.818f, 0.538f, 0.829f, 0.605f, 0.803f, 0.666f, 0.747f, 0.704f, 0.717f, 0.710f, 0.317f, 0.720f, 0.386f, 0.720f, 0.454f, 0.720f, 0.522f, 0.720f, 0.590f, 0.720f, 0.658f, 0.720f)
    val RAIN = floatArrayOf(0.249f, 0.600f, 0.196f, 0.558f, 0.171f, 0.496f, 0.180f, 0.429f, 0.222f, 0.376f, 0.284f, 0.351f, 0.317f, 0.308f, 0.348f, 0.246f, 0.399f, 0.199f, 0.464f, 0.173f, 0.533f, 0.173f, 0.598f, 0.197f, 0.650f, 0.243f, 0.682f, 0.304f, 0.709f, 0.350f, 0.774f, 0.368f, 0.818f, 0.418f, 0.829f, 0.485f, 0.803f, 0.546f, 0.747f, 0.584f, 0.717f, 0.590f, 0.317f, 0.600f, 0.386f, 0.600f, 0.454f, 0.600f, 0.522f, 0.600f, 0.590f, 0.600f, 0.658f, 0.600f, 0.360f, 0.720f, 0.320f, 0.795f, 0.520f, 0.720f, 0.480f, 0.795f, 0.680f, 0.720f, 0.640f, 0.795f)
    val SNOW = floatArrayOf(0.249f, 0.600f, 0.196f, 0.558f, 0.171f, 0.496f, 0.180f, 0.429f, 0.222f, 0.376f, 0.284f, 0.351f, 0.317f, 0.308f, 0.348f, 0.246f, 0.399f, 0.199f, 0.464f, 0.173f, 0.533f, 0.173f, 0.598f, 0.197f, 0.650f, 0.243f, 0.682f, 0.304f, 0.709f, 0.350f, 0.774f, 0.368f, 0.818f, 0.418f, 0.829f, 0.485f, 0.803f, 0.546f, 0.747f, 0.584f, 0.717f, 0.590f, 0.317f, 0.600f, 0.386f, 0.600f, 0.454f, 0.600f, 0.522f, 0.600f, 0.590f, 0.600f, 0.658f, 0.600f, 0.360f, 0.740f, 0.520f, 0.800f, 0.680f, 0.740f, 0.440f, 0.880f, 0.600f, 0.880f)
    val STORM = floatArrayOf(0.249f, 0.600f, 0.196f, 0.558f, 0.171f, 0.496f, 0.180f, 0.429f, 0.222f, 0.376f, 0.284f, 0.351f, 0.317f, 0.308f, 0.348f, 0.246f, 0.399f, 0.199f, 0.464f, 0.173f, 0.533f, 0.173f, 0.598f, 0.197f, 0.650f, 0.243f, 0.682f, 0.304f, 0.709f, 0.350f, 0.774f, 0.368f, 0.818f, 0.418f, 0.829f, 0.485f, 0.803f, 0.546f, 0.747f, 0.584f, 0.717f, 0.590f, 0.317f, 0.600f, 0.386f, 0.600f, 0.454f, 0.600f, 0.522f, 0.600f, 0.590f, 0.600f, 0.658f, 0.600f, 0.560f, 0.660f, 0.510f, 0.730f, 0.460f, 0.800f, 0.530f, 0.800f, 0.600f, 0.800f, 0.550f, 0.870f, 0.500f, 0.940f)
    val FOG = floatArrayOf(0.180f, 0.360f, 0.249f, 0.360f, 0.317f, 0.360f, 0.386f, 0.360f, 0.454f, 0.360f, 0.523f, 0.360f, 0.591f, 0.360f, 0.660f, 0.360f, 0.729f, 0.360f, 0.797f, 0.360f, 0.260f, 0.500f, 0.329f, 0.500f, 0.397f, 0.500f, 0.466f, 0.500f, 0.534f, 0.500f, 0.603f, 0.500f, 0.671f, 0.500f, 0.740f, 0.500f, 0.809f, 0.500f, 0.180f, 0.640f, 0.249f, 0.640f, 0.317f, 0.640f, 0.386f, 0.640f, 0.454f, 0.640f, 0.523f, 0.640f, 0.591f, 0.640f, 0.660f, 0.640f, 0.729f, 0.640f)
    val PARTLY = floatArrayOf(0.227f, 0.753f, 0.176f, 0.709f, 0.160f, 0.643f, 0.185f, 0.581f, 0.241f, 0.544f, 0.283f, 0.511f, 0.313f, 0.450f, 0.365f, 0.406f, 0.432f, 0.388f, 0.499f, 0.399f, 0.556f, 0.437f, 0.592f, 0.495f, 0.621f, 0.541f, 0.684f, 0.564f, 0.718f, 0.622f, 0.709f, 0.689f, 0.661f, 0.735f, 0.625f, 0.744f, 0.297f, 0.753f, 0.367f, 0.753f, 0.437f, 0.753f, 0.506f, 0.753f, 0.576f, 0.753f, 0.790f, 0.340f, 0.530f, 0.340f, 0.547f, 0.275f, 0.595f, 0.227f, 0.660f, 0.210f, 0.725f, 0.227f, 0.773f, 0.275f, 0.660f, 0.140f, 0.660f, 0.070f, 0.801f, 0.199f, 0.851f, 0.149f, 0.860f, 0.340f, 0.930f, 0.340f, 0.801f, 0.481f, 0.851f, 0.531f, 0.460f, 0.340f, 0.390f, 0.340f, 0.519f, 0.199f, 0.469f, 0.149f)
    val BUDS = floatArrayOf(0.420f, 0.340f, 0.407f, 0.405f, 0.370f, 0.460f, 0.315f, 0.497f, 0.250f, 0.510f, 0.185f, 0.497f, 0.130f, 0.460f, 0.093f, 0.405f, 0.080f, 0.340f, 0.093f, 0.275f, 0.130f, 0.220f, 0.185f, 0.183f, 0.250f, 0.170f, 0.315f, 0.183f, 0.370f, 0.220f, 0.407f, 0.275f, 0.420f, 0.520f, 0.320f, 0.520f, 0.420f, 0.600f, 0.320f, 0.600f, 0.420f, 0.680f, 0.320f, 0.680f, 0.420f, 0.760f, 0.320f, 0.760f, 0.420f, 0.840f, 0.320f, 0.840f, 0.920f, 0.340f, 0.907f, 0.405f, 0.870f, 0.460f, 0.815f, 0.497f, 0.750f, 0.510f, 0.685f, 0.497f, 0.630f, 0.460f, 0.593f, 0.405f, 0.580f, 0.340f, 0.593f, 0.275f, 0.630f, 0.220f, 0.685f, 0.183f, 0.750f, 0.170f, 0.815f, 0.183f, 0.870f, 0.220f, 0.907f, 0.275f, 0.580f, 0.520f, 0.680f, 0.520f, 0.580f, 0.600f, 0.680f, 0.600f, 0.580f, 0.680f, 0.680f, 0.680f, 0.580f, 0.760f, 0.680f, 0.760f, 0.580f, 0.840f, 0.680f, 0.840f)

    enum class Kind { SUN, MOON, PARTLY, CLOUD, RAIN, SNOW, STORM, FOG }

    /** WMO weather_code (+ is_day) to icon; there is no night variant of the partly-cloudy sun */
    fun kind(code: Int, isDay: Boolean): Kind = when (code) {
        0, 1 -> if (isDay) Kind.SUN else Kind.MOON
        2 -> if (isDay) Kind.PARTLY else Kind.CLOUD
        3 -> Kind.CLOUD
        45, 48 -> Kind.FOG
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> Kind.RAIN
        71, 73, 75, 77, 85, 86 -> Kind.SNOW
        95, 96, 99 -> Kind.STORM
        else -> Kind.CLOUD
    }

    private fun pts(k: Kind): FloatArray = when (k) {
        Kind.SUN -> SUN
        Kind.MOON -> MOON
        Kind.PARTLY -> PARTLY
        Kind.CLOUD -> CLOUD
        Kind.RAIN -> RAIN
        Kind.SNOW -> SNOW
        Kind.STORM -> STORM
        Kind.FOG -> FOG
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cache = HashMap<List<Any>, Bitmap>()

    /** device px for an icon of [dp] (capped at 128 px so the RemoteViews parcel stays small) */
    fun px(dp: Float, density: Float): Int = (dp * density).toInt().coerceIn(32, MAX_PX)

    fun weather(k: Kind, px: Int, color: Int): Bitmap = dots(pts(k), WX_DOT, px, color)

    fun buds(px: Int, color: Int): Bitmap = dots(BUDS, BUDS_DOT, px, color)

    private fun dots(p: FloatArray, dot: Float, px: Int, color: Int): Bitmap {
        val key = listOf(p, px, color)
        cache[key]?.let { return it }
        if (cache.size > 12) cache.clear()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        paint.color = color
        val r = dot * px
        var i = 0
        while (i + 1 < p.size) {
            c.drawCircle(p[i] * px, p[i + 1] * px, r, paint)
            i += 2
        }
        cache[key] = bmp   // not recycled: RemoteViews may still be parceling an older copy
        return bmp
    }

    /** [n] dots in one row, the first [lit] in [litColor] (the one at [accentIndex] in accent), the rest dot_off */
    fun bar(n: Int, lit: Int, litColor: Int, accentIndex: Int): Bitmap {
        val pitch = 24
        val bmp = Bitmap.createBitmap(n * pitch, pitch, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = pitch * 0.30f
        for (i in 0 until n) {
            paint.color = when {
                i == accentIndex -> Nd.ACCENT
                i < lit -> litColor
                else -> Nd.OFF
            }
            c.drawCircle((i + 0.5f) * pitch, pitch / 2f, r, paint)
        }
        return bmp
    }
}
