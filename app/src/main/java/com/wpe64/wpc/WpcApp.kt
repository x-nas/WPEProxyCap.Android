package com.wpe64.wpc

import android.app.Application
import com.wpe64.wpc.service.ProxyService

/**
 * 进程级单例：ProxyService 挂在 Application 上，界面（MainActivity）与 VPN 服务（WpcVpnService）共用同一份。
 * 界面被划掉之后连接照样在跑（VPN 在前台服务里），再打开界面时 getState 直接看到「已连接」。
 */
class WpcApp : Application() {

    lateinit var service: ProxyService
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        service = ProxyService(this)
    }

    companion object {
        lateinit var instance: WpcApp
            private set
    }
}
