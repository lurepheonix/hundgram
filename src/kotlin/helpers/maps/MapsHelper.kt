package desu.inugram.helpers.maps

import android.content.Context
import desu.inugram.InuConfig
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.IMapsProvider
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R

object MapsHelper {
    @JvmStatic
    fun isHybridAvailable(): Boolean {
        return InuConfig.MAP_PROVIDER.value != InuConfig.MapProviderItem.OSM
    }

    @JvmStatic
    // MessagesController.mapProvider values:
    // -1 = disabled
    // 1 = yandex, direct
    // 2 = telegram, via inputWebFileGeoPointLocation
    // 3 = yandex, via webFile proxy
    // 4 = google, via webFile proxy
    // (any other) = google, direct
    fun overrideMapProvider(stock: Int): Int = when (InuConfig.MAP_PREVIEW_PROVIDER.value) {
        InuConfig.MapPreviewProviderItem.DEFAULT -> stock
        InuConfig.MapPreviewProviderItem.TELEGRAM -> 2
        InuConfig.MapPreviewProviderItem.GOOGLE -> 101 // override to 101 to disambiguate with server-pushed google in syncMapProvider
        InuConfig.MapPreviewProviderItem.YANDEX -> 1
        InuConfig.MapPreviewProviderItem.DISABLED -> -1
        else -> stock
    }

    fun syncMapProvider(messagesController: MessagesController) {
        messagesController.mapProvider = overrideMapProvider(messagesController.mainSettings.getInt("mapProvider", 0));
        if (messagesController.mapProvider == 101) {
            messagesController.mapKey = "AIzaSyB0y3zA4LbA04ZPaHKsr_Xt5ZQWbMftj8I" // google maps api key from TL_config in official apps
        }
    }

    // effective dark state for the current MAP_THEME override; stockDark is the caller's
    // app-theme signal (LocationActivity.currentMapStyleDark), used when on Auto.
    @JvmStatic
    fun isDarkEffective(stockDark: Boolean): Boolean = when (InuConfig.MAP_THEME.value) {
        InuConfig.MapThemeItem.LIGHT -> false
        InuConfig.MapThemeItem.DARK -> true
        else -> stockDark
    }

    // live-apply the theme override to an open map (in-map button); returns the effective
    // dark state so the caller can keep stock's currentMapStyleDark tracking in sync.
    @JvmStatic
    fun refreshMapTheme(map: IMapsProvider.IMap, context: Context, stockDark: Boolean): Boolean {
        val dark = isDarkEffective(stockDark)
        val ml = map as? MlIMap
        if (ml != null) {
            ml.setMapStyle(if (dark) MlStyleOptions else null)
        } else {
            map.setMapStyle(if (dark) {
                ApplicationLoader.getMapsProvider().loadRawResourceStyle(context, R.raw.mapstyle_night)
            } else {
                null
            })
        }
        return dark
    }
}