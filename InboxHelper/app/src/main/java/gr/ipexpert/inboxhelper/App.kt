package gr.ipexpert.inboxhelper

import android.app.Application
import gr.ipexpert.inboxhelper.data.Repo
import gr.ipexpert.inboxhelper.data.Settings
import gr.ipexpert.inboxhelper.index.Index
import gr.ipexpert.inboxhelper.m365.M365Config
import gr.ipexpert.inboxhelper.m365.SyncEngine
import gr.ipexpert.inboxhelper.work.Analyzer
import gr.ipexpert.inboxhelper.work.MonitorWorker
import gr.ipexpert.inboxhelper.work.Notifier

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        M365Config.init(this)
        Index.init(this)
        Repo.init(this)
        Analyzer.appContext = this
        SyncEngine.appContext = this
        Notifier.createChannels(this)
        MonitorWorker.schedule(this)
        Analyzer.sweep()
    }
}
