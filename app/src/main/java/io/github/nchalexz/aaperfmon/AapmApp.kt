package io.github.nchalexz.aaperfmon

import android.app.Application
import io.github.nchalexz.aaperfmon.telemetry.Telemetry

/**
 * Точка инициализации общего репозитория телеметрии.
 *
 * Движок стартует в демонстрационном режиме и живёт независимо от
 * Activity: экран приборов, виджет и автомобильный экран читают одно
 * состояние. Если сохранён адаптер и разрешено автоподключение,
 * подключение поднимется само.
 */
class AapmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Telemetry.init(this)
    }
}
