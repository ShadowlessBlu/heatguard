package gh.knust.echowear.ui

import android.preference.PreferenceManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * OpenStreetMap view.
 *
 * osmdroid is used rather than Google Maps because it needs no API key and no
 * Play Services, which matters for a device that has to be buildable and
 * deployable by a community health team without a billing account.
 */
@Composable
fun OsmMap(lat: Double, lon: Double, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    val mapView = remember {
        Configuration.getInstance().load(
            context, PreferenceManager.getDefaultSharedPreferences(context),
        )
        // osmtools requires a distinctive user agent or tiles are refused.
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(17.0)
        }
    }

    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            val point = GeoPoint(lat, lon)
            map.controller.setCenter(point)
            map.overlays.removeAll { it is Marker }
            map.overlays.add(
                Marker(map).apply {
                    position = point
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Wearer's last known position"
                }
            )
            map.invalidate()
        },
    )
}
