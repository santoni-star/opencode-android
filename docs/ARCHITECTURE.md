# Архітектура

## Склад

- `MainActivity` задає `activity_main`, показує статус/журнал і запускає bootstrap у coroutine. Файлові та процесні операції виконуються через `Dispatchers.IO`.
- `BootstrapManager` готує каталоги `files/env/bin` і `files/env/rootfs`, знаходить backend, перевіряє його, за потреби завантажує та витягає OpenCode.
- `RuntimeBackend` визначає `probe()` і `run(rootfs, command, env)`.
- `FakeChrootNgBackend` реалізує інтерфейс: `probe()` запускає бінарник з `--probe`; `run()` передає йому шлях rootfs і команду через `ProcessBuilder`.

## Потік запуску

1. Після створення Activity відображаються `Installing...` і журнал.
2. `BootstrapManager` шукає `libchroot_ng.so` у `applicationInfo.nativeLibraryDir`; якщо його немає, шукає `files/env/bin/chroot-ng`. Якщо обидва шляхи відсутні, bootstrap завершується помилкою.
3. Backend запускається з `--probe`. Ненульовий код завершення також зупиняє процес.
4. Якщо `files/env/bin/opencode` відсутній, застосунок завантажує архів за URL `https://opencode.ai/update/api/latest/cli/opencode/opencode-linux-arm64-musl.tar.gz`, витягає запис `opencode` з tar.gz, видаляє архів і надає файлу прапорець executable. Наявний файл повторно не перевіряється.
5. `MainActivity` викликає `launchOpencode(listOf("--version"))`, читає об'єднаний stdout/stderr, чекає на процес і показує статус `Ready` незалежно від коду завершення дочірнього процесу.

## Шляхи та межі реалізації

`rootfs` створюється як `files/env/rootfs`, але код не наповнює його системними файлами. Команда передається backend як абсолютний шлях до `files/env/bin/opencode`; код не налаштовує явне зіставлення шляху цього файла всередині rootfs. Отже, наявність rootfs-каталогу не доводить, що запуск у ньому працює на пристрої.

Застосунок поки не реалізує інтерактивний PTY, керування процесом/сесією, встановлення rootfs, резервний backend або явне середовище PATH/CA. У `FakeChrootNgBackend` передаються тільки задані змінні; решта середовища `ProcessBuilder` успадковується. ABI фільтр обмежує APK на `arm64-v8a`.

## Залежності

AndroidX Core KTX, AppCompat і Kotlin Coroutines Android оголошені в `app/build.gradle.kts`. Репозиторії залежностей — Google і Maven Central; plugin repositories також містять Gradle Plugin Portal.
