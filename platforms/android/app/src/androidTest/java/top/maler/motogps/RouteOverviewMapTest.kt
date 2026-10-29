package top.maler.motogps

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import top.maler.motogps.model.GCJ02Point
import top.maler.motogps.nav.JinanDemoFixture
import top.maler.motogps.nav.RoutePreviewCandidate
import top.maler.motogps.ui.MotoTheme
import top.maler.motogps.ui.RouteOverviewMap

/** Draws two candidate routes and checks the selected route is painted in the accent colour. */
@RunWith(AndroidJUnit4::class)
class RouteOverviewMapTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun selectedRouteIsDrawnAboveTheAlternative() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = context.assets.open(JinanDemoFixture.ASSET_NAME).bufferedReader().use { JinanDemoFixture.parse(it.readText()) }
        val primary = fixture.fallbackRoute(false, 1)
        val alternate = primary.copy(
            routeId = "alternate",
            polyline = primary.polyline.mapIndexed { index, point ->
                GCJ02Point(point.longitudeDeg + 0.0012 * kotlin.math.sin(index / 5.0), point.latitudeDeg)
            },
        )
        val candidates = listOf(RoutePreviewCandidate(0, primary), RoutePreviewCandidate(1, alternate))
        compose.setContent {
            MotoTheme {
                RouteOverviewMap(
                    candidates = candidates,
                    selectedId = candidates[0].id,
                    origin = fixture.requestedOriginWgs84,
                    destination = fixture.requestedDestinationWgs84,
                    description = "test",
                    modifier = Modifier.fillMaxWidth().height(260.dp).testTag("map"),
                )
            }
        }
        val bitmap = compose.onNodeWithTag("map").captureToImage().asAndroidBitmap()
        File(context.cacheDir, "route-overview.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }

        var accent = 0
        var alternateGray = 0
        for (x in 0 until bitmap.width step 2) {
            for (y in 0 until bitmap.height step 2) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.blue(pixel) > 200 && Color.red(pixel) < 40 && Color.green(pixel) in 90..150) accent += 1
                if (Color.red(pixel) in 140..170 && Color.green(pixel) in 155..175 && Color.blue(pixel) in 165..185) alternateGray += 1
            }
        }
        assertTrue("selected route pixels: $accent", accent > 200)
        assertTrue("alternate route pixels: $alternateGray", alternateGray > 50)
    }
}
