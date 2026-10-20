package com.motogps.android

import com.motogps.android.ble.MotoBLEManager
import com.motogps.android.jni.GeoPointE6
import com.motogps.android.jni.MotoBLEDeviceCommand
import com.motogps.android.map.RoadSegment
import com.motogps.android.viewmodel.NavigationViewModel
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class NavigationViewModelTest {

    private lateinit var bleManager: MotoBLEManager
    private lateinit var viewModel: NavigationViewModel
    private var capturedCallback: ((MotoBLEDeviceCommand) -> Unit)? = null

    @Suppress("UNCHECKED_CAST")
    @Before
    fun setUp() {
        bleManager = mock(MotoBLEManager::class.java)
        doAnswer { invocation ->
            capturedCallback = invocation.getArgument(0) as? ((MotoBLEDeviceCommand) -> Unit)
            null
        }.`when`(bleManager).onDeviceCommandReceived = any()
        `when`(bleManager.onDeviceCommandReceived).thenAnswer { capturedCallback }

        viewModel = NavigationViewModel(bleManager)
    }

    @Test
    fun testDeviceCommandPageSelectedUpdatesCurrentDevicePage() {
        assertEquals(0, viewModel.currentDevicePage.value)

        val cmd = MotoBLEDeviceCommand(
            sequence = 10,
            ackReq = false,
            kind = 0, // PageSelected
            commandId = 1,
            page = 2, // Heading / Compass
            x = 0,
            y = 0,
            eventTimeMs = System.currentTimeMillis()
        )

        capturedCallback?.invoke(cmd)

        assertEquals(2, viewModel.currentDevicePage.value)
    }

    @Test
    fun testFindNearestRoadSpeedLimitMatching() {
        val segMilan = RoadSegment(
            roadClass = 1,
            points = listOf(
                GeoPointE6(45464000, 9190000),
                GeoPointE6(45465000, 9191000)
            ),
            maxSpeedKph = 70
        )

        val segRome = RoadSegment(
            roadClass = 2,
            points = listOf(
                GeoPointE6(41902800, 12496400),
                GeoPointE6(41903000, 12497000)
            ),
            maxSpeedKph = 50
        )

        val segments = listOf(segMilan, segRome)

        // Location right near Milan segment -> should return 70
        val speedMilan = viewModel.findNearestRoadSpeedLimit(segments, 45.4641, 9.1901, 50.0)
        assertEquals(70, speedMilan)

        // Location far away from all segments -> should return 0
        val speedFar = viewModel.findNearestRoadSpeedLimit(segments, 48.8566, 2.3522, 50.0)
        assertEquals(0, speedFar)
    }
}
