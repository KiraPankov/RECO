import json
import numpy as np
import sherpa_onnx
import soundfile as sf

VOICES = {
    "A": "ru_RU-irina-medium",
    "B": "ru_RU-dmitri-medium",
    "C": "ru_RU-ruslan-medium",
    "E": "en_US-lessac-medium",
}

LINES = [
    ("A", "Добрый день! Давайте начнём совещание. Сегодня обсуждаем запуск нового приложения."),
    ("B", "Здравствуйте. Я подготовил отчёт по тестированию, там нашлось три критических ошибки."),
    ("A", "Хорошо. Какие сроки на исправление?"),
    ("B", "Думаю, до пятницы успеем."),
    ("C", "Я бы добавил, что дизайн тоже нужно доработать. Экран настроек выглядит перегруженным, а кнопки на главном экране слишком мелкие. Пользователи в опросе жаловались, что не могут найти экспорт. Предлагаю вынести экспорт в отдельное меню и упростить навигацию. Ещё стоит пересмотреть цветовую схему тёмной темы, потому что контраст текста сейчас недостаточный. И последнее: нужно проверить, как приложение работает на старых телефонах с небольшим объёмом памяти."),
    ("E", "Sorry to interrupt, can we also discuss the English localization before the release?"),
    ("A", "Да, конечно. Давайте обсудим локализацию в конце встречи."),
    ("C", "Согласен."),
]


def tts(voice):
    d = f"vits-piper-{voice}"
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=f"{d}/{voice}.onnx", tokens=f"{d}/tokens.txt", data_dir=f"{d}/espeak-ng-data"
            ),
            num_threads=4,
        )
    )
    return sherpa_onnx.OfflineTts(cfg)


def resample(x, sr, to=16000):
    n = int(len(x) * to / sr)
    return np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)


engines = {k: tts(v) for k, v in VOICES.items()}
rng = np.random.default_rng(1)
out, truth, t = [], [], 0.0
for spk, text in LINES:
    a = engines[spk].generate(text, sid=0, speed=1.0)
    s = resample(np.array(a.samples), a.sample_rate)
    pause = np.zeros(int(16000 * rng.uniform(0.35, 0.8)), np.float32)
    truth.append({"spk": spk, "start": round(t, 2), "end": round(t + len(s) / 16000, 2), "text": text})
    out += [s, pause]
    t += (len(s) + len(pause)) / 16000
audio = np.concatenate(out)
audio += rng.normal(0, 0.003, len(audio)).astype(np.float32)  # light noise
sf.write("dialog.wav", audio, 16000)
json.dump(truth, open("dialog_truth.json", "w"), ensure_ascii=False, indent=1)
print(f"{len(audio)/16000:.1f}s")
