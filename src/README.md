# Исходный код

Платформа: `mobile/android` — Android-приложение (Kotlin, Jetpack Compose).

## Сборка через GitHub Actions (рекомендуется)

Сценарий `.github/workflows/android.yml` собирает APK со всеми моделями на серверах GitHub.

- **Проверка сборки.** Запускается при каждом push в `main`/`master` и при pull request. Также можно запустить вручную: *Actions → Android APK → Run workflow*.
- **Публикация в Releases.** Создайте тег версии, и сценарий выложит `RECO-<тег>.apk` на страницу Releases:
  ```bash
  git tag v1.0.0
  git push origin v1.0.0
  ```

Модели скачиваются при первой сборке и дальше берутся из кэша Actions. В git они не хранятся: файлы больше 100 МБ туда не загрузить.

## Локальная сборка

**Что нужно**
- Android Studio Otter 3 Feature Drop (2025.2.3) или новее, Android SDK 35, JDK 17–25.
- Стек сборки: Gradle 9.3.1, Android Gradle Plugin 9.0.1 (Kotlin встроен), Kotlin 2.3.0.
- ~4 ГБ свободного места на диске.
- Интернет для первой сборки. Само приложение работает без интернета.

**Шаги**
1. Откройте папку `src/mobile/android` в Android Studio и дождитесь Gradle Sync.
2. Соберите APK: *Build → Generate App Bundles or APKs → Generate APKs* или `./gradlew assembleRelease`.
   - Перед первой сборкой задача `fetchModels` (`app/models.gradle.kts`) один раз скачает модели (~1 ГБ) в `app/models-assets/`. Оттуда они попадают в APK как assets.
   - Повторные сборки ничего не скачивают. Модели, которых нет в списке (например, от прошлых версий), удаляются.
3. Готовый APK: `app/build/outputs/apk/release/app-release.apk`. Для тестовых сборок он подписан debug-ключом.

**Юнит-тесты:** `./gradlew test` (`app/src/test`).

## Модели в APK

| Каталог в assets | Модель | Назначение | Источник | Размер |
|---|---|---|---|---|
| `models/asr-parakeet` | NVIDIA Parakeet TDT 0.6B v3 (int8) | Распознавание речи (RU, EN и ещё 23 языка) | GitHub, k2-fsa/sherpa-onnx | 670 МБ |
| `models/llm-qwen3` | Qwen3-0.6B (int4, LiteRT-LM, без режима размышлений) | Пунктуация и заглавные буквы | Hugging Face, litert-community/Qwen3-0.6B-int4 | ~330 МБ |
| `models/segmentation` | pyannote segmentation 3.0 (int8) | Поиск участков речи и смены говорящего | GitHub, k2-fsa/sherpa-onnx | 1,5 МБ |
| `models/embedding` | 3D-Speaker CAM++ (zh/en, common advanced) | «Отпечаток голоса» для различения говорящих | GitHub, k2-fsa/sherpa-onnx | 28 МБ |
| `models/vad` | Silero VAD | Паузы между фразами: разрез склеенных реплик разных говорящих | GitHub, k2-fsa/sherpa-onnx | 0,6 МБ |

Промпт и шаблон чата LLM лежат в `app/src/main/assets/llm/`. Тот же промпт и шаблон используют тесты качества в `tools/eval`.

## Библиотеки

- `app/libs/sherpa-onnx-2.0.1.aar` — распознавание и диаризация (k2-fsa/sherpa-onnx, Apache 2.0), лежит в репозитории.
- Шрифт IBM Plex Mono (`app/src/main/res/font`, SIL Open Font License 1.1, текст лицензии — `docs/licenses/IBM-Plex-Mono-OFL.txt`).
- `com.google.ai.edge.litertlm:litertlm-android:0.19.1` — движок LLM (Google LiteRT-LM, Apache 2.0), подключается из Google Maven.
