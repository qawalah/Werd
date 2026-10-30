package com.werd.app.ui.reminders

import android.annotation.SuppressLint
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.werd.app.R
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/** OpenStreetMap picker: tap to drop the pin, circle shows the trigger radius. No API key needed. */
@SuppressLint("ClickableViewAccessibility", "MissingPermission")
@Composable
fun LocationPicker(lat: Double?, lng: Double?, radius: Float, hasPermission: Boolean, onPick: (Double, Double) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val map = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            controller.setZoom(if (lat != null) 16.0 else 5.0)
            controller.setCenter(if (lat != null && lng != null) GeoPoint(lat, lng) else GeoPoint(23.8859, 45.0792))
            setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_MOVE) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }
    }
    val marker = remember { Marker(map).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) } }
    val circle = remember {
        Polygon(map).apply {
            fillPaint.color = 0x330E6B4E
            outlinePaint.color = 0xFF0E6B4E.toInt()
            outlinePaint.strokeWidth = 3f
        }
    }
    val tapOverlay = remember {
        MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { onPick(p.latitude, p.longitude); return true }
            override fun longPressHelper(p: GeoPoint): Boolean { onPick(p.latitude, p.longitude); return true }
        })
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, ev ->
            when (ev) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        map.onResume()
        onDispose { lifecycle.removeObserver(obs); map.onPause(); map.onDetach() }
    }

    fun locate() {
        if (!hasPermission) return
        val client = LocationServices.getFusedLocationProviderClient(context)
        runCatching {
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { loc ->
                    if (loc != null) {
                        onPick(loc.latitude, loc.longitude)
                        map.controller.animateTo(GeoPoint(loc.latitude, loc.longitude), 17.0, 600L)
                    }
                }
        }
    }

    Column {
        Box(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(18.dp))) {
            AndroidView(factory = { map }, modifier = Modifier.fillMaxWidth().height(280.dp), update = { m ->
                m.overlays.clear()
                m.overlays.add(tapOverlay)
                if (lat != null && lng != null) {
                    val p = GeoPoint(lat, lng)
                    circle.points = Polygon.pointsAsCircle(p, radius.toDouble())
                    m.overlays.add(circle)
                    marker.position = p
                    m.overlays.add(marker)
                }
                m.invalidate()
            })
            if (lat == null) {
                Text(stringResource(R.string.tap_map), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.88f))
                        .padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        FilledTonalButton(onClick = ::locate, enabled = hasPermission, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.Rounded.MyLocation, null, Modifier.size(18.dp))
            androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.use_my_location))
        }
    }
}
