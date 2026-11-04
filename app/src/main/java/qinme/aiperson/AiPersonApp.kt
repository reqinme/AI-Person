package qinme.aiperson

import android.app.Application

/**
 * 进程级初始化。
 * WorkManager 在后台拉起进程时不会经过 MainActivity，所以依赖必须在这里准备好，
 * 否则主动消息和延迟消息会在冷启动时因为 AppGraph/BehaviorSettings 未初始化而失败。
 */
class AiPersonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        UiPrefs.init(this)
        BehaviorSettings.init(this)
        Notifier.ensureChannels(this)
    }
}
