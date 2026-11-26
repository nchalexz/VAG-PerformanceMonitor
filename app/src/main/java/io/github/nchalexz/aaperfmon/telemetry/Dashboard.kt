package io.github.nchalexz.aaperfmon.telemetry

/** Тип виджета на приборной панели. */
enum class WidgetKind { DIAL, TILE, BAR }

/**
 * Описание одного виджета.
 *
 * Диапазон и порог предупреждения здесь, а не в сигнале: один и тот же
 * сигнал на разных экранах может иметь разные шкалы. Значения по умолчанию
 * берутся из каталога.
 */
data class WidgetSpec(
    val id: String,
    val kind: WidgetKind,
    val signalId: String,
    val title: String,
    val min: Double,
    val max: Double,
    val warnFrom: Double? = null,
)

/** Приборная панель — упорядоченный набор виджетов. */
data class DashboardSpec(
    val id: String,
    val title: String,
    val widgets: List<WidgetSpec>,
) {
    val signalIds: List<String> get() = widgets.map { it.signalId }.distinct()
}

object Dashboards {

    private fun widget(
        signalId: String,
        kind: WidgetKind,
        min: Double? = null,
        max: Double? = null,
        warnFrom: Double? = null,
        title: String? = null,
    ): WidgetSpec {
        val definition = SignalCatalog.get(signalId)
            ?: error("В каталоге нет сигнала $signalId")
        return WidgetSpec(
            id = "${kind.name.lowercase()}.$signalId",
            kind = kind,
            signalId = signalId,
            title = title ?: definition.nameRu,
            min = min ?: definition.minValue ?: 0.0,
            max = max ?: definition.maxValue ?: 100.0,
            warnFrom = warnFrom,
        )
    }

    /** Экран для движения: три колодца и пять плиток из технического задания. */
    val driving = DashboardSpec(
        id = "driving",
        title = "Движение",
        widgets = listOf(
            widget(SignalCatalog.Ids.COOLANT, WidgetKind.DIAL, 40.0, 120.0, 110.0),
            widget(SignalCatalog.Ids.BOOST, WidgetKind.DIAL, -100.0, 250.0, 200.0),
            widget(SignalCatalog.Ids.OIL_TEMP, WidgetKind.DIAL, 40.0, 160.0, 140.0),
            widget(SignalCatalog.Ids.SPEED, WidgetKind.TILE),
            widget(SignalCatalog.Ids.RPM, WidgetKind.TILE),
            widget(SignalCatalog.Ids.GEAR, WidgetKind.TILE),
            widget(SignalCatalog.Ids.FUEL_RATE, WidgetKind.TILE, title = "Расход"),
            widget(SignalCatalog.Ids.FUEL_LEVEL, WidgetKind.TILE, title = "Топливо"),
        ),
    )

    /** Динамика: наддув, мощность, момент и топливо. */
    val performance = DashboardSpec(
        id = "performance",
        title = "Динамика",
        widgets = listOf(
            widget(SignalCatalog.Ids.BOOST, WidgetKind.DIAL, -100.0, 250.0, 200.0),
            widget(SignalCatalog.Ids.POWER_KW, WidgetKind.DIAL, 0.0, 300.0, 240.0),
            widget(SignalCatalog.Ids.TORQUE_NM, WidgetKind.DIAL, 0.0, 500.0, 450.0),
            widget(SignalCatalog.Ids.RPM, WidgetKind.TILE, title = "Обороты"),
            widget(SignalCatalog.Ids.LOAD, WidgetKind.TILE, title = "Нагрузка"),
            widget(SignalCatalog.Ids.RAIL_PRESSURE, WidgetKind.TILE, title = "Топливо"),
            widget(SignalCatalog.Ids.INTAKE_AIR, WidgetKind.TILE, title = "Впуск"),
            widget(SignalCatalog.Ids.COOLANT, WidgetKind.TILE, title = "ОЖ"),
        ),
    )

    val all: List<DashboardSpec> = listOf(driving, performance)
}
