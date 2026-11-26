# AARE / DashGrid: что нужно на стороне хоста

Приложение телеметрии не реализует собственный межпроцессный протокол
для виджета: используется стандартный Android AppWidget. Это требование
ТЗ и единственный способ не дублировать логику.

## Со стороны телеметрии уже есть

- `TelemetryWidgetProvider` с `APPWIDGET_UPDATE`.
- `telemetry_widget_info.xml`: min 180×110 dp, resize, `targetCellWidth/Height`,
  `previewLayout`, `configure`.
- `WidgetConfigActivity` для выбора показателей.
- `RemoteViews` с обновлением из `TelemetryService`.
- Один ELM327 и один `Telemetry` на процесс; виджет не открывает
  вторую OBD-сессию.

## Что должен сделать DashGrid (вне этого репозитория)

1. Создать `AppWidgetHost` и вызвать `allocateAppWidgetId()`.
2. Получить список провайдеров (`AppWidgetManager.installedProviders`).
3. Вызвать `bindAppWidgetIdIfAllowed(id, provider)`; при отказе —
   показать системный диалог/настройки.
4. Создать `AppWidgetHostView`, передать id и `AppWidgetProviderInfo`.
5. Добавить view в ячейку рабочего стола (Compose — `AndroidView`).
6. Вызывать `updateAppWidgetOptions(id, bundle)` с размерами ячейки
   в dp при изменении раскладки/разрешения.
7. Обрабатывать `onAppWidgetRemoved`, `deleteAppWidgetId` при удалении,
   `startListening`/`stopListening` при показе/скрытии рабочих столов.
8. Не фильтровать провайдера по имени пакета: список widgets должен
   приходить из системы.
9. Пробрасывать касания штатно; `PendingIntent` открывает Activity
   телеметрии на телефоне, если хост это поддерживает.

## Ограничения, которые нужно проверить на DashGrid

- `AppWidgetHostView` на виртуальном дисплее AARE.
- Маршрутизация касаний и `PendingIntent` из виртуального дисплея.
- Размеры/DPI: `updateAppWidgetOptions` должен получать реальные dp,
  иначе виджет выберет компактную раскладку.
- EMUI 14+ и ограничения фонового запуска Activity.
- Одновременная работа навигации, музыки и виджета.

## Полноэкранная приборка в AARE

`CockpitActivity` — обычная Activity, её можно запускать из AARE как
любое приложение. Отдельный `CarAppService` для AARE не нужен.
Проверка на живом головном устройстве не выполнялась.
