"""Загрузка контрольных наборов open_stt и метрики качества (WER, доля фраз с пунктуацией).

Используется openstt_eval.py и final_eval.py; проверяется test_openstt_utils.py.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import tarfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Iterable

OPENSTT_BASE = "https://azureopendatastorage.blob.core.windows.net/openstt/ru_open_stt_opus/archives"

# Имена архивов в open_stt не всегда совпадают с названиями подмножеств
ARCHIVE_NAMES = {
    "radio_v4": "radio_v4_manifest",
    "public_speech": "public_speech_manifest",
    "radio_v4_add": "radio_v4_add_manifest",
    "audiobook_2": "private_buriy_audiobooks_2",
    "tts_russian_addresses": "tts_russian_addresses_rhvoice_4voices",
}


def archive_url(subset: str) -> str:
    return f"{OPENSTT_BASE}/{ARCHIVE_NAMES.get(subset, subset)}.tar.gz"


# ---------------------------------------------------------------- нормализация и метрики

_NON_WORD = re.compile(r"[^0-9a-zа-я]+")


def normalize(text: str) -> str:
    """Нормализация для подсчёта WER: нижний регистр, ё→е, без пунктуации и дефисов."""
    t = text.lower().replace("ё", "е")
    return " ".join(_NON_WORD.sub(" ", t).split())


def edit_distance(a: list, b: list) -> int:
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (a[i - 1] != b[j - 1]))
        prev = cur
    return prev[-1]


def corpus_wer(refs: Iterable[str], hyps: Iterable[str]) -> float:
    errors = words = 0
    for r, h in zip(refs, hyps):
        rw, hw = normalize(r).split(), normalize(h).split()
        errors += edit_distance(rw, hw)
        words += len(rw)
    return errors / max(words, 1)


def cer(ref: str, hyp: str) -> float:
    r, h = normalize(ref), normalize(hyp)
    return edit_distance(list(r), list(h)) / max(len(r), 1)


def punctuation_rate(texts: Iterable[str]) -> float:
    """Доля непустых текстов, где есть хотя бы один знак препинания."""
    texts = [t for t in texts if t.strip()]
    return sum(bool(re.search(r"[.,!?;:…]", t)) for t in texts) / max(len(texts), 1)


# ---------------------------------------------------------------- загрузка open_stt


def _duration(path: str) -> float:
    try:
        import soundfile as sf

        return float(sf.info(path).duration)
    except Exception:
        out = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
            capture_output=True, text=True,
        )
        return float(out.stdout.strip() or 0)


def _to_flac(src: str, dst: str) -> bool:
    r = subprocess.run(
        ["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-i", src, "-ac", "1", "-ar", "16000", dst],
        capture_output=True,
    )
    os.remove(src)
    return r.returncode == 0 and os.path.getsize(dst) > 0


def stream_subset(
    url_or_file: str,
    out_dir: str,
    max_hours: float | None = None,
    min_duration: float = 0.5,
    max_duration: float = 20.0,
    workers: int | None = None,
    log_every: int = 2000,
) -> list[dict]:
    """Скачивает архив open_stt потоком и останавливается, набрав max_hours аудио.

    Архив целиком на диск не сохраняется. Аудио конвертируется в FLAC 16 кГц моно.
    Возвращает записи манифеста NeMo: audio_filepath, duration, text.
    """
    out = Path(out_dir)
    tmp = out / "_tmp"
    tmp.mkdir(parents=True, exist_ok=True)
    limit = max_hours * 3600 if max_hours else float("inf")

    if url_or_file.startswith("http"):
        import requests

        resp = requests.get(url_or_file, stream=True, timeout=60)
        resp.raise_for_status()
        fileobj = resp.raw
    else:
        resp = None
        fileobj = open(url_or_file, "rb")

    pending_audio: dict[str, tuple[str, float]] = {}
    pending_text: dict[str, str] = {}
    entries: list[dict] = []
    futures = []
    total = 0.0
    skipped = 0
    pool = ThreadPoolExecutor(max_workers=workers or os.cpu_count() or 4)

    def emit(stem: str, audio_tmp: str, dur: float, text: str):
        nonlocal total
        dst = str(out / (stem.replace("/", "_") + ".flac"))
        entry = {"audio_filepath": dst, "duration": round(dur, 3), "text": text}
        futures.append((pool.submit(_to_flac, audio_tmp, dst), entry))
        total += dur

    try:
        with tarfile.open(fileobj=fileobj, mode="r|gz") as tf:
            for m in tf:
                if total >= limit:
                    break
                if not m.isfile():
                    continue
                stem, ext = os.path.splitext(m.name)
                ext = ext.lower()
                if ext == ".txt":
                    text = tf.extractfile(m).read().decode("utf-8", errors="ignore").strip()
                    if stem in pending_audio:
                        a, d = pending_audio.pop(stem)
                        if text:
                            emit(stem, a, d, text)
                        else:
                            os.remove(a)
                    else:
                        pending_text[stem] = text
                elif ext in (".opus", ".wav", ".ogg", ".mp3", ".flac"):
                    a = str(tmp / (stem.replace("/", "_") + ext))
                    with open(a, "wb") as f:
                        f.write(tf.extractfile(m).read())
                    d = _duration(a)
                    if not (min_duration <= d <= max_duration):
                        os.remove(a)
                        pending_text.pop(stem, None)
                        skipped += 1
                        continue
                    if stem in pending_text:
                        text = pending_text.pop(stem)
                        if text:
                            emit(stem, a, d, text)
                        else:
                            os.remove(a)
                    else:
                        pending_audio[stem] = (a, d)
                n = len(futures)
                if log_every and n and n % log_every == 0 and ext == ".txt":
                    print(f"  {n} записей, {total / 3600:.1f} ч", flush=True)
    finally:
        if resp is not None:
            resp.close()
        else:
            fileobj.close()

    for a, _ in pending_audio.values():
        if os.path.exists(a):
            os.remove(a)
    for fut, entry in futures:
        if fut.result():
            entries.append(entry)
    pool.shutdown()
    try:
        tmp.rmdir()
    except OSError:
        pass
    print(f"  готово: {len(entries)} записей, {sum(e['duration'] for e in entries) / 3600:.2f} ч "
          f"(пропущено по длительности: {skipped})")
    return entries


# ---------------------------------------------------------------- манифесты


def write_manifest(entries: list[dict], path: str) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for e in entries:
            f.write(json.dumps(e, ensure_ascii=False) + "\n")


def read_manifest(path: str) -> list[dict]:
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]
