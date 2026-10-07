package com.kei.pulse

import android.content.Context
import com.kei.pulse.data.BundledProfileProvider
import com.kei.pulse.data.CpuPolicyDetector
import com.kei.pulse.data.GovernorController
import com.kei.pulse.data.GpuFreqDetector
import com.kei.pulse.data.PerAppConfigStorage
import com.kei.pulse.data.PerformanceRepository
import com.kei.pulse.data.ProfileStorage
import com.kei.pulse.data.SettingsStorage
import com.kei.pulse.root.PerformanceCommandBuilder
import com.kei.pulse.root.PServerSysfsReader
import com.kei.pulse.root.RootCommandRunner

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsStorage: SettingsStorage by lazy {
        SettingsStorage(appContext)
    }

    val perAppConfigStorage: PerAppConfigStorage by lazy {
        PerAppConfigStorage(appContext)
    }

    val repository: PerformanceRepository by lazy {
        PerformanceRepository(
            detector = CpuPolicyDetector(
                privilegedReader = PServerSysfsReader(appContext),
                gpuDetector = GpuFreqDetector(
                    privilegedReader = PServerSysfsReader(appContext),
                ),
            ),
            bundledProfileProvider = BundledProfileProvider(appContext),
            profileStorage = ProfileStorage(appContext),
            commandBuilder = PerformanceCommandBuilder(),
            rootCommandRunner = RootCommandRunner(appContext),
            governorController = GovernorController(),
        )
    }
}
