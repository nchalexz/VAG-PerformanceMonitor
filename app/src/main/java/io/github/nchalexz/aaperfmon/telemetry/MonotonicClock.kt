package io.github.nchalexz.aaperfmon.telemetry

/**
 * Монотонные часы приложения.
 *
 * Возраст данных считается по монотонному времени, а не по календарному:
 * перевод часов или переход на летнее время не должен превращать свежее
 * значение в устаревшее. В Android это `SystemClock.elapsedRealtime()`.
 *
 * JVM-тесты и инструменты без Android используют `System.nanoTime()`;
 * приложение подменяет источник при инициализации. Отдельный объект нужен
 * потому, что одни и те же значения читают приборка, виджет, мост DashGrid
 * и служба — у всех должен быть один и тот же отсчёт.
 */
object MonotonicClock {

    @Volatile
    private var source: () -> Long = { System.nanoTime() / 1_000_000L }

    fun now(): Long = source()

    /** Устанавливает платформенный источник времени. Вызывается один раз. */
    fun install(clock: () -> Long) {
        source = clock
    }
}
