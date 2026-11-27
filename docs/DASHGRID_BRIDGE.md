# Мост телеметрии для DashGrid

В 0.5.0 у VAG Performance Monitor появился read-only мост: DashGrid рисует
нативный кокпит и читает уже собранные значения VPM, не открывая второе
OBD-соединение. ELM327 и VAG UDS остаются во владении VPM.

## Что отдаётся

| Что | Значение |
|---|---|
| Authority | `io.github.nchalexz.aaperfmon.telemetry` |
| Разрешение | `io.github.nchalexz.aaperfmon.permission.READ_TELEMETRY`, `normal` |
| Метод | `contentResolver.call(uri, "snapshot", null, null)` → `Bundle` или `null` |
| Версия | `version = 1` |
| Доступ | только чтение; `query`, `insert`, `update`, `delete` не поддерживаются, `getType` → `null` |

Провайдер `io.github.nchalexz.aaperfmon.bridge.TelemetryProvider` объявлен
`exported="true"` с `android:readPermission` на это разрешение. В `call`
разрешение проверяется ещё раз, до разбора имени метода: защита от
OEM-прошивок, которые меняют поведение `readPermission`.

## Bundle

| Ключ | Тип | Смысл |
|---|---|---|
| `version` | `Int` | версия контракта, сейчас `1` |
| `connection` | `String` | `demo`, `connecting`, `connected`, `disconnected`, `error` |
| `status` | `String` | строка состояния VPM; к `connectionLabel` добавляется `message`, если он непустой |
| `source` | `String` | адаптер/транспорт при живом подключении, иначе пустая строка |
| `updatedAt` | `Long` | `timestampMs` самого свежего из отданных сигналов; `0`, если данных не было |
| `signals` | `String` | JSON-массив, не больше 128 элементов |

## Сигнал

```json
{
  "id": "engine.boost_kpa",
  "label": "Boost",
  "value": "0,62",
  "unit": "bar",
  "raw": 0.62,
  "stale": false,
  "warning": false,
  "category": "engine",
  "status": "valid"
}
```

- `value` — готовая строка как на плитке VPM (запятая в дробях); `null`,
  если значения нет. Ноль вместо отсутствия не подставляется.
- `unit` и `label` — в языке самой приборки VPM (настройка «язык» или
  системная локаль).
- `raw` — то же значение числом в единицах показа: наддув в `bar`, а не в
  `kPa`; по нему DashGrid ставит стрелку и пороги.
- `stale` — значение устарело или его не было.
- `status` — `valid`, `stale` или `never_read` (добавлено в 0.6.0;
  старые потребители могут его игнорировать). `never_read` означает, что
  за сессию не было ни одного успешного чтения — на экране VPM это `N/A`;
  `stale` — значение было, но старше 5 с (P0/P1) или 30 с (P2).
- `warning` — порог `MetricSpec` VPM, тот же, что на её экране.
- `category` — `engine`, `temperatures`, `fuel`, `gearbox`, `turbo`,
  `diagnostics`.

Устаревшее значение по-прежнему приходит вместе с `raw` и флагом `stale`;
`value` и `raw` равны `null` только когда числа не было вовсе (`never_read`
или вычисляемый показатель не определён, например расход на стоянке).

Порядок: категории в порядке экрана VPM, внутри категории — порядок
приборки. Сигнал, который VPM показывает в двух вкладках (наддув, MAP,
BARO), отдаётся по разу в каждой категории; пара `(id, category)` не
повторяется.

## Пример

```sh
adb shell content call \
  --uri content://io.github.nchalexz.aaperfmon.telemetry \
  --method snapshot
```

На устройстве без нужного разрешения Android вернёт `SecurityException`.
Обычное разрешение выдаётся при установке приложению, которое его
объявляет; после обновления VPM с 0.4.1 на 0.5.0 DashGrid достаточно
переустановить или обновить один раз.

## Что мост не делает

- Не вызывает `startService()`, `connect()` и не опрашивает PID:
  чтение снимка — не повод открывать OBD. Автоподключение вынесено из
  `Telemetry.init` в `Telemetry.autoConnectOnce()`, его запускают экран,
  приборка и служба, но не провайдер.
- Не отдаёт историю, настройки, VIN, путь к адаптеру и записи.
- Не экспортирует `CockpitActivity` и не меняет AA-сборку.
- Не отдаёт `demo` за машину: в демо-режиме `connection = "demo"`, и
  DashGrid показывает плашку DEMO.

## Безопасность

`normal`-разрешение означает, что текущие показания датчиков может
прочитать любое приложение, которое его объявит. Это осознанный
компромисс: в снимке нет истории, VIN и управления адаптером, а запись
через провайдер невозможна. Подпись APK и сертификат описаны в
[SECURITY.md](../SECURITY.md).

## Связанные документы

- [AARE_DASHGRID.md](AARE_DASHGRID.md) — что нужно на стороне DashGrid и
  почему выбран `ContentProvider`.
- [../CHANGELOG.md](../CHANGELOG.md) — версия 0.5.0.
- Контракт DashGrid: `net.prototux.aare.vpm` в репозитории DashGrid.
