package tech.illusion.spacewhiteboard.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import tech.illusion.spacewhiteboard.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
