# Артефакты тестирования

| Путь | Содержимое |
|---|---|
| `audio/clean.wav`, `audio/noisy_snr5.wav`, `audio/quiet_dc.wav` | Тестовые записи (16 кГц, моно) |
| `audio/reference.json` | Эталон: говорящий, начало и конец реплики (с), текст |
| `output/*.json` | Результат конвейера в формате задания (`transcript` / `speaker_id` / `start_ms` / `end_ms` / `text`) |
| `results.csv` | Метрики по каждой записи: WER, точность диаризации, число говорящих, ошибка границ, RTF, работа LLM (исправлено, без изменений, отклонено) |

Как получены результаты: `tools/eval/README.md`. Методика: `docs/TEST_RESULTS.md`.
