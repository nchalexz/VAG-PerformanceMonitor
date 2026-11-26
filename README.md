# AA Performance Monitor

Приборная панель для автомобилей VAG: телеметрия через обычный ELM327 и собственные экраны
в стиле штатных цифровых приборов Volkswagen/Porsche.

Приложение самостоятельное: свой `applicationId`, имя и иконка, ставится рядом с VAG Diag и
AARE. Только чтение — никакого кодирования, адаптаций и записи в блоки.

## Статус

Ранняя альфа. Работает:

- транспорты ELM327 из базового проекта (Bluetooth SPP, USB-серийники, Wi-Fi);
- диагностическая UI-часть VAG Diag (подключение, блоки, ошибки, live data);
- **демо-экран приборов**: три колодца и пять плиток в стиле MIB, данные — демонстрационный цикл.

Демо-экран открывается кнопкой «Приборы» в верхней панели. К реальной телеметрии он ещё не
подключён: это следующий шаг (каталог сигналов + движок телеметрии).

## Сборка

Нужны JDK 17+, Android SDK с platform 36 и build-tools 36.0.0.

```sh
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Лицензия

MIT. База — Android-приложение [VAG Diag](https://github.com/gdm0991/vagdiag) (MIT, commit
`c2d565f`); подробности в [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
