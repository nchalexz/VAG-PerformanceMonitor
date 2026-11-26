package io.github.nchalexz.aaperfmon

import android.app.Application
import io.github.nchalexz.aaperfmon.telemetry.Telemetry

/**
 * Точка инициализации общего репозитория телеметрии.
 *
 * Движок стартует в демонстрационном режиме и живёт независимо от
 * Activity: экран приборов, виджет и автомобильный экран читают одно
 * состояние. Автоподключение здесь не выполняется: процесс может быть
 * поднят чтением read-only провайдера DashGrid, и это не должно открывать
 * OBD. Его запускает экран, приборка или служба через
 * [Telemetry.autoConnectOnce].
 */
class AapmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Telemetry.init(this)
    }
}
