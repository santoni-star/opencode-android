# opencode-android

Мінімальний Android-застосунок-завантажувач для перевірки та запуску окремого CLI OpenCode на пристроях **arm64-v8a (AArch64)**. Під час запуску застосунок перевіряє наявність і працездатність `fake-chroot-ng`, завантажує архів CLI для `linux-arm64-musl`, витягає виконуваний файл і запускає його з аргументом `--version`.

> Це прототип, а не готовий до продуктивного використання термінал. Завантаження CLI поки не перевіряється криптографічним хешем чи підписом; інтерактивний PTY і повноцінний rootfs не реалізовані. Див. [обмеження безпеки](docs/SECURITY.md).

## Можливості та межі

- Android-модуль `app`, Kotlin; `minSdk 26`, `targetSdk`/`compileSdk 35`.
- Єдина цільова ABI — `arm64-v8a`.
- Використовується інтерфейс `RuntimeBackend` і його реалізація `FakeChrootNgBackend`; альтернативний `proot` не підключений.
- Очікуваний бінарник середовища — `libchroot_ng.so` у каталозі нативних бібліотек застосунку або файл `files/env/bin/chroot-ng`.
- OpenCode завантажується з URL `latest` (Linux ARM64 musl), а не з зафіксованого релізу.
- Поточний екран показує журнали та результат одноразового виклику `--version`; це не інтерактивна оболонка.

## Збірка

Потрібні JDK 17, Android SDK, платформа Android 35 і Build Tools 35.0.0. З кореня репозиторію:

```sh
./gradlew :app:assembleDebug
```

Debug APK очікується за адресою `app/build/outputs/apk/debug/app-debug.apk`. CI-workflow `.github/workflows/build-apk.yml` запускається вручну, при push до `main` і для `pull_request`; він збирає debug APK та завантажує його як артефакт. Це не підписаний релізний процес. Додаткові деталі — у [інструкції зі збірки](docs/BUILD.md).

## Додавання backend-бінарника

Покладіть сумісний arm64 бінарник як `app/src/main/jniLibs/arm64-v8a/libchroot_ng.so`, щоб його можна було знайти через `nativeLibraryDir`, або передбачте його у `files/env/bin/chroot-ng`. Маніфест сам по собі не додає цей файл; без нього застосунок завершить bootstrap помилкою. Деталі виконання описані в [архітектурі](docs/ARCHITECTURE.md).

## Документація

- [Збірка та CI](docs/BUILD.md)
- [Архітектура й потік виконання](docs/ARCHITECTURE.md)
- [Безпека та відомі ризики](docs/SECURITY.md)
