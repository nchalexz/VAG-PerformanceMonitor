# AARE / DashGrid: виджет и мост телеметрии

Состояние на 0.5.0. Для **виджета** используется стандартный Android
AppWidget. Для **нативной карточки** DashGrid добавлен отдельный read-only
мост — его нет в опубликованном 0.4.1, но он есть в 0.5.0.

## Что уже есть со стороны телеметрии

- `TelemetryWidgetProvider` с `APPWIDGET_UPDATE`, `android:exported="true"`.
- `telemetry_widget_info.xml`: min 180×110 dp, resize, `targetCellWidth/Height`,
  `previewLayout`, `configure`.
- `WidgetConfigActivity` для выбора показателей.
- `RemoteViews` с обновлением из `TelemetryService`.
- Один ELM327 и один `Telemetry` на процесс; виджет не открывает
  вторую OBD-сессию.
- Процесс-репозиторий `Telemetry.state: StateFlow<TelemetryState>`.

Этого достаточно, чтобы DashGrid разместил **стандартный AppWidget**
через `AppWidgetHost`. Но из `RemoteViews` нельзя получить значения:
это готовый UI, а не данные.

## Готовность провайдера для нативной карточки: реализовано в 0.5.0

В 0.5.0 есть всё, что читает DashGrid:

- `ContentProvider` `io.github.nchalexz.aaperfmon.bridge.TelemetryProvider`
  с authority `io.github.nchalexz.aaperfmon.telemetry`;
- permission `io.github.nchalexz.aaperfmon.permission.READ_TELEMETRY`
  с `protectionLevel="normal"`;
- `call("snapshot")` с Bundle: `version`, `connection`, `status`,
  `source`, `updatedAt`, `signals`;
- значения в единицах приборки: наддув в бар, запятая в дробях,
  категория одного из шести экранов;
- read-only: `query`/`insert`/`update`/`delete` не поддерживаются.

Чтение не запускает службу и не открывает OBD:

- `Telemetry.init` больше не подключает сохранённый адаптер;
- автоподключение вынесено в `Telemetry.autoConnectOnce()` и вызывается
  экраном, приборкой или службой, а не `ContentProvider`;
- `TelemetryService` и `CockpitActivity` остаются `exported="false"`;
- если процесс поднят только чтением снимка, VPM отдаёт ДЕМО-значения
  из той же модели, которую показывает приборка, но не стартует сервис.

В опубликованном 0.4.1 этого моста нет, поэтому нативная карточка
DashGrid на нём показывает «мост недоступен». На 0.5.0 она читает снимок. Проверено на эмуляторе Android 15 с DashGrid
1.2.0; на живом головном устройстве — нет.

## Контракт, который ожидает DashGrid (v1)

Клиент DashGrid читает снимок так:

```text
content://io.github.nchalexz.aaperfmon.telemetry
  call("snapshot") -> Bundle
  readPermission: io.github.nchalexz.aaperfmon.permission.READ_TELEMETRY (normal)
  version = 1
```

Bundle:

| Ключ | Тип | Обязателен | Смысл |
|---|---|---|---|
| `version` | Int | да | `1` |
| `connection` | String | да | `demo`, `connecting`, `connected`, `disconnected`, `error` |
| `status` | String | да | человеческая строка состояния |
| `source` | String | нет | транспорт или адаптер |
| `updatedAt` | Long | да | время последнего обновления значений, не время вызова |
| `signals` | String | да | JSON-массив, не больше 128 элементов |

Сигнал в JSON:

```json
{
  "id": "engine.coolant_c",
  "label": "Охлаждающая жидкость",
  "value": "86",
  "unit": "°C",
  "raw": 86.0,
  "stale": false,
  "warning": false,
  "category": "temperatures"
}
```

- `value` — уже отформатированная строка, как в приборке VPM; для
  отсутствующего значения `null`, а не `"0"`.
- `raw` — число в тех же единицах, что `unit` (для наддува — бар),
  чтобы DashGrid поставил стрелку и порог.
- `category` — одна из шести: `engine`, `temperatures`, `fuel`,
  `gearbox`, `turbo`, `diagnostics`.
- Массив сгруппирован по категориям в порядке экранов VPM. Сигнал,
  который VPM показывает в двух вкладках (наддув — Engine и Turbo),
  встречается по одному разу на вкладку с соответствующей `category`.
  Главный экран берёт первое вхождение нужного `id`.
- Только чтение: `query`/`insert`/`update`/`delete` не поддерживаются.
- Провайдер не запускает сервис, не подключает адаптер и не опрашивает
  PID: он отдаёт только то, что уже лежит в `Telemetry.state.value`.

Проверка после реализации:

```sh
adb shell content call \
  --uri content://io.github.nchalexz.aaperfmon.telemetry \
  --method snapshot
```

В 0.4.1 эта команда завершается ошибкой: провайдера с таким authority нет.
В 0.5.0 `adb shell` без разрешения получает `SecurityException` — это
ожидаемо. Проверять нужно из приложения, объявившего `READ_TELEMETRY`
(например, DashGrid), или из процесса VPM.

## Что должен сделать DashGrid

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

На 1.1.1 DashGrid заявляет `AppWidgetHost` и стандартные AppWidget;
размещение именно виджета VAG там не проверялось. Нативная карточка
дополнительно требует клиент контракта v1 и разрешения
`READ_TELEMETRY`.

## Ограничения, которые нужно проверить на DashGrid

- `AppWidgetHostView` на виртуальном дисплее AARE.
- Маршрутизация касаний и `PendingIntent` из виртуального дисплея.
- Размеры/DPI: `updateAppWidgetOptions` должен получать реальные dp,
  иначе виджет выберет компактную раскладку.
- Оболочки производителя и ограничения фонового запуска Activity.
- Одновременная работа навигации, музыки и виджета.

## Полноэкранная приборка в AARE

`CockpitActivity` — обычная Activity, отдельный `CarAppService` для AARE
не нужен. Но она объявлена `exported="false"`: запуск зависит от прав
хоста, и DashGrid не может стартовать её напрямую. Для нативной карточки
это и не требуется — она читает провайдер. Проверка на живом головном
устройстве не выполнялась.

## Что проверено

- 59 JVM-тестов, 0 падений; из них 9 — на снимок моста: единицы приборки,
  `stale`/`warning`, ДЕМО без запущенной службы, группировка по категориям,
  JSON-контракт и отсутствие дублей `(id, category)`.
- Эмулятор Android 15, DashGrid 1.2.0: рабочий стол «Автомобиль» читает
  снимок VPM, главный экран показывает три прибора и пять плиток, нажатие
  на прибор открывает Temperatures; значения помечены ДЕМО.
- `adb shell content call` от root возвращает Bundle `version=1` со 128
  сигналами; shell без `READ_TELEMETRY` получает `SecurityException`.
- В logcat при чтении провайдера нет `ELM327`/OBD-соединения.
- Не проверено: живое головное устройство, реальный телефон, живой адаптер
  и живая машина.
