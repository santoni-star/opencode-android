# Збірка та CI

## Вимоги

- JDK 17 (Gradle wrapper використовує Gradle 8.11.1).
- Android SDK з `platforms;android-35` і `build-tools;35.0.0`.
- Для реальної перевірки застосунку — сумісний пристрій/емулятор arm64-v8a та доступ до мережі.

Версії Android Gradle Plugin і Kotlin Gradle plugin відповідно 8.5.2 і 1.9.24; залежності застосунку вказані у `app/build.gradle.kts`.

## Локальна debug-збірка

З кореня репозиторію:

```sh
./gradlew :app:assembleDebug
```

Артефакт: `app/build/outputs/apk/debug/app-debug.apk`. Для Windows доступний `gradlew.bat`. Ця команда збирає debug APK, але сама по собі не перевіряє поведінку на пристрої, завантаження OpenCode чи наявність `fake-chroot-ng`.

## GitHub Actions

Workflow `.github/workflows/build-apk.yml` має три тригери: ручний `workflow_dispatch`, push до `main` і `pull_request`. Він checkout-ить репозиторій, встановлює Temurin JDK 17 та Android SDK компоненти, запускає `:app:assembleDebug` і завантажує APK через `actions/upload-artifact@v4` (артефакт `opencode-android-debug`). Доступи workflow обмежені `contents: read`; збірка не публікує реліз і не вивантажує APK у магазин.

Gradle-кешування налаштоване через `actions/setup-java` з кешем `gradle`; кеш визначається Gradle wrapper і файлами Gradle-конфігурації. Pull request не потребує write-дозволу репозиторію для цих кроків.

## Налаштування застосунку для ручного тесту

Для успішного проходження bootstrap потрібно додати arm64-сумісний `fake-chroot-ng` як `app/src/main/jniLibs/arm64-v8a/libchroot_ng.so` або забезпечити шлях `files/env/bin/chroot-ng` у середовищі застосунку. Без цього перевірка backend завершиться помилкою до завантаження CLI. Маніфест запитує `INTERNET`; застосунок завантажує архів CLI під час першого запуску.

Збірка не є тестом сумісності SELinux/W^X, запуску на різних пристроях чи автентичності завантаженого архіву. Релізне підписування і публікація в репозиторії не налаштовані.

The CI workflow also builds `fake-chroot-ng` for AArch64 and packages it as an executable helper. The APK's current app UI uses OpenCode's non-interactive `run` command; it does not provide a PTY-backed full-screen interface.
