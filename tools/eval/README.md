# Тесты качества

Скрипты повторяют конвейер приложения на Python:
- те же модели, что в APK;
- sherpa-onnx 2.0.1 для распознавания и диаризации;
- LiteRT-LM 0.19.1 для LLM;
- тот же промпт, шаблон чата и проверка слов.

Проще всего запускать их через GitHub Actions: *Actions → Тесты качества → Run workflow* (`.github/workflows/eval.yml`).

| Файл | Назначение |
|---|---|
| `pipeline.py` | Конвейер: предобработка, диаризация pyannote + CAM++, разрез по паузам Silero VAD, второй проход, нарезка, Parakeet, сборка реплик |
| `llm_punctuation.py` | Пунктуация LLM и проверка слов (как `LlmPunctuator.kt` и `PunctuationGuard.kt`). Запуск без аргументов сверяет проверку слов с общими примерами |
| `final_eval.py` | Тестовые диалоги `artifacts/test-results`: WER, диаризация, границы реплик, LLM |
| `openstt_eval.py` | Контрольные наборы open_stt с ручной разметкой: WER до и после LLM, пунктуация, работа LLM |
| `openstt_utils.py` | Потоковая загрузка open_stt и метрики |
| `test_openstt_utils.py` | Тесты загрузки и метрик |
| `make_dialog.py` | Синтез тестового диалога (sherpa-onnx 1.x со встроенным espeak и голоса piper) |

## Локальный запуск

```bash
pip install sherpa-onnx==2.0.1 litert-lm-api==0.19.1 soundfile numpy requests   # и ffmpeg в системе
(cd ../../src/mobile/android && ./gradlew :app:fetchModels)                      # модели — как для APK
python final_eval.py ../../artifacts/test-results            # --no-llm — без LLM
python openstt_eval.py --out ../../eval-out/openstt --max-utts 2000
```

`litert-lm-api` выпускается только для Linux x86_64. На Windows запускайте через WSL или GitHub Actions.
