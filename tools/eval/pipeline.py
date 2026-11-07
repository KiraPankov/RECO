"""Конвейер приложения на Python (sherpa-onnx 2.0.1 + LiteRT-LM 0.19.1) — для тестов на ПК и в GitHub Actions.

Повторяет TranscriptionEngine.kt с теми же моделями и параметрами. Модели берутся из каталога,
который собирает для APK Gradle-задача fetchModels: src/mobile/android/app/models-assets/models.
"""
from __future__ import annotations

import os
from pathlib import Path

import numpy as np

SR = 16000
ROOT = Path(__file__).resolve().parents[2]
DEFAULT_MODELS = ROOT / "src/mobile/android/app/models-assets/models"

# Параметры как в приложении
CLUSTER_THRESHOLD = 0.7      # TranscriptionEngine.CLUSTER_THRESHOLD
REFINE_MIN_SEG = 1.0         # SpeakerRefiner.MIN_SEG_SEC
REFINE_MERGE = 0.75          # SpeakerRefiner.MERGE_SIMILARITY
REFINE_MIN_EMBED = 0.5       # SpeakerRefiner.MIN_EMBED_SEC
VAD_THRESHOLD, VAD_MIN_SILENCE, VAD_MIN_SPEECH, VAD_MAX_SPEECH, VAD_WINDOW = 0.7, 0.2, 0.1, 20.0, 512
SPLIT_MIN_PIECE, SPLIT_SHORT, GLUE = 0.2, 1.0, 0.05   # PauseSplitter
MAX_PIECE, MIN_SPLIT = 28.0, 18.0
MERGE_GAP, JOIN_GAP, MIN_SEG, PAD = 0.8, 1.5, 0.3, 0.15


def model_paths(models_dir: str | os.PathLike = DEFAULT_MODELS) -> dict:
    m = Path(models_dir)
    return {
        "asr": m / "asr-parakeet",
        "seg": m / "segmentation/model.int8.onnx",
        "emb": m / "embedding/3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx",
        "llm": m / "llm-qwen3/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm",
        "vad": m / "vad/silero_vad.onnx",
    }


def preprocess(x: np.ndarray) -> np.ndarray:
    """Как Preprocessor.kt: убрать постоянную составляющую, нормализовать громкость."""
    y = x - np.convolve(x, np.ones(1601) / 1601, "same")
    p = np.percentile(np.abs(y), 99.9) if len(y) else 0.0
    return np.clip(y * min(0.9 / max(p, 1e-6), 30.0), -1, 1).astype(np.float32)


def threads() -> int:
    return max(1, min(4, (os.cpu_count() or 2)))


def create_asr(paths):
    import sherpa_onnx as so

    d = paths["asr"]
    return so.OfflineRecognizer.from_transducer(
        encoder=str(d / "encoder.int8.onnx"), decoder=str(d / "decoder.int8.onnx"),
        joiner=str(d / "joiner.int8.onnx"), tokens=str(d / "tokens.txt"),
        model_type="nemo_transducer", num_threads=threads())


def decode(rec, x: np.ndarray) -> str:
    if len(x) == 0:
        return ""
    s = rec.create_stream()
    s.accept_waveform(SR, x)
    rec.decode_stream(s)
    return s.result.text.strip()


def detect_speech(a: np.ndarray, paths) -> list[tuple[float, float]]:
    """Участки речи по Silero VAD (TranscriptionEngine.detectSpeech)."""
    import sherpa_onnx as so

    cfg = so.VadModelConfig(
        silero_vad=so.SileroVadModelConfig(
            model=str(paths["vad"]), threshold=VAD_THRESHOLD, min_silence_duration=VAD_MIN_SILENCE,
            min_speech_duration=VAD_MIN_SPEECH, max_speech_duration=VAD_MAX_SPEECH, window_size=VAD_WINDOW),
        sample_rate=SR, num_threads=1)
    vad = so.VoiceActivityDetector(cfg, buffer_size_in_seconds=60)
    out = []

    def drain():
        while not vad.empty():
            s = vad.front
            out.append((s.start / SR, (s.start + len(s.samples)) / SR))
            vad.pop()

    for i in range(0, len(a), VAD_WINDOW):
        vad.accept_waveform(a[i:i + VAD_WINDOW])
        drain()
    vad.flush()
    drain()
    return out


def split_at_pauses(segs, speech):
    """PauseSplitter.split: разрез сегментов посередине пауз VAD, короткие обрывки — к соседу."""
    pauses = [(speech[i][1], speech[i + 1][0]) for i in range(len(speech) - 1)]
    out = []  # [start, end, speaker, cut_start, cut_end]
    for st, en, spk in segs:
        cur, cut = st, False
        for gs, ge in pauses:
            if gs <= st or ge >= en or ge <= cur:
                continue
            mid = (gs + ge) / 2
            if mid - cur >= SPLIT_MIN_PIECE:
                out.append([cur, mid, spk, cut, True])
                cur, cut = mid, True
        if en - cur >= SPLIT_MIN_PIECE:
            out.append([cur, en, spk, cut, False])
    changed = True
    while changed:
        changed = False
        for i, p in enumerate(out):
            if p[1] - p[0] >= SPLIT_SHORT:
                continue
            nb = []
            if i > 0 and not p[3] and abs(out[i - 1][1] - p[0]) < GLUE:
                nb.append(out[i - 1])
            if i + 1 < len(out) and not p[4] and abs(out[i + 1][0] - p[1]) < GLUE:
                nb.append(out[i + 1])
            if not nb:
                continue
            best = max(nb, key=lambda q: q[1] - q[0])  # при равенстве — первый, как maxByOrNull
            if best[2] != p[2]:
                p[2] = best[2]
                changed = True
    m = []
    for p in out:
        if m and m[-1][2] == p[2] and p[0] - m[-1][1] < GLUE and not p[3]:
            m[-1][1], m[-1][4] = p[1], p[4]
        else:
            m.append(list(p))
    return [[p[0], p[1], p[2]] for p in m]


def refine(segs, E):
    """SpeakerRefiner.refine."""
    if all(e is None for e in E):
        return segs
    lab = [s[2] for s in segs]

    def cents(lab):
        c = {}
        for (st, en, _), e, l in zip(segs, E, lab):
            if e is not None and en - st >= REFINE_MIN_SEG:
                c[l] = c.get(l, 0) + e * (en - st)
        return {k: v / (np.linalg.norm(v) + 1e-9) for k, v in c.items()}

    for _ in range(5):
        c = cents(lab)
        if not c:
            return segs
        ks = sorted(c)
        rm = {k: k for k in ks}
        for i, k1 in enumerate(ks):
            for k2 in ks[i + 1:]:
                if rm[k2] == k2 and rm[k1] == k1 and c[k1] @ c[k2] > REFINE_MERGE:
                    rm[k2] = k1
        lab = [rm.get(l, l) for l in lab]
        c = cents(lab)
        new = []
        for (st, en, _), e, l in zip(segs, E, lab):
            if e is None or (en - st < REFINE_MIN_SEG and l in c):
                new.append(l)
            else:
                new.append(max(c, key=lambda k: c[k] @ e))
        if new == lab:
            break
        lab = new
    return [[s[0], s[1], l] for s, l in zip(segs, lab)]


def diarize(a: np.ndarray, paths, threshold: float = CLUSTER_THRESHOLD) -> list[list]:
    """pyannote + CAM++ + кластеризация, разрез по паузам VAD, второй проход (TranscriptionEngine.diarize)."""
    import sherpa_onnx as so

    cfg = so.OfflineSpeakerDiarizationConfig(
        segmentation=so.OfflineSpeakerSegmentationModelConfig(
            pyannote=so.OfflineSpeakerSegmentationPyannoteModelConfig(model=str(paths["seg"])), num_threads=threads()),
        embedding=so.SpeakerEmbeddingExtractorConfig(model=str(paths["emb"]), num_threads=threads()),
        clustering=so.FastClusteringConfig(num_clusters=-1, threshold=threshold),
        min_duration_on=0.2, min_duration_off=0.3)
    segs = [[s.start, s.end, s.speaker] for s in so.OfflineSpeakerDiarization(cfg).process(a).sort_by_start_time()]
    if not segs:
        return segs
    segs = split_at_pauses(segs, detect_speech(a, paths))
    ext = so.SpeakerEmbeddingExtractor(so.SpeakerEmbeddingExtractorConfig(model=str(paths["emb"]), num_threads=threads()))
    E = []
    for st, en, _ in segs:
        if en - st < REFINE_MIN_EMBED:
            E.append(None)
            continue
        s = ext.create_stream()
        s.accept_waveform(SR, a[int(st * SR):int(min(en, st + 30) * SR)])
        s.input_finished()
        v = np.array(ext.compute(s))
        E.append(v / (np.linalg.norm(v) + 1e-9))
    return refine(segs, E)


def split_long(st, en, spk, a):
    """Segments.splitLong: куски ≤ 28 с, разрез в самой тихой точке после 18-й секунды."""
    out, win = [], int(0.2 * SR)
    while en - st > MAX_PIECE:
        lo, hi = int((st + MIN_SPLIT) * SR), min(int((st + MAX_PIECE) * SR), len(a))
        best, be = hi, 1e18
        for p in range(lo, hi - win + 1, win // 2):
            e = float(np.sum(a[p:p + win] ** 2))
            if e < be:
                be, best = e, p + win // 2
        cut = best / SR
        if cut <= st:
            break
        out.append([st, cut, spk])
        st = cut
    out.append([st, en, spk])
    return out


def pieces_of(segs, a):
    out = []
    for st, en, spk in sorted(segs):
        if out and st < out[-1][1]:
            st = out[-1][1]
        if en - st >= MIN_SEG:
            out.append([st, en, spk])
    m = []
    for st, en, spk in out:
        if m and m[-1][2] == spk and st - m[-1][1] < MERGE_GAP and en - m[-1][0] <= MAX_PIECE:
            m[-1][1] = en
        else:
            m.append([st, en, spk])
    return [q for p in m for q in split_long(*p, a)]


def slc(a, st, en, pad=PAD):
    return a[max(0, int((st - pad) * SR)):min(len(a), int((en + pad) * SR))]


def finalize(utts):
    """Segments.finalize: нумерация спикеров по появлению, склейка соседних реплик одного спикера."""
    order, fin = {}, []
    for st, en, spk, txt, by in sorted(utts, key=lambda u: u[0]):
        k = order.setdefault(spk, len(order))
        if fin and fin[-1][2] == k and st - fin[-1][1] < JOIN_GAP:
            fin[-1][1] = en
            fin[-1][3] += " " + txt
            fin[-1][4] |= by
        else:
            fin.append([st, en, k, txt, by])
    return fin


def transcribe(raw: np.ndarray, paths, llm=None) -> tuple[list, dict]:
    """Полный конвейер. Возвращает реплики [start, end, speaker, text, by_llm] и счётчики LLM."""
    a = preprocess(raw)
    pieces = pieces_of(diarize(a, paths), a)
    asr = create_asr(paths)
    utts = []
    for st, en, spk in pieces:
        txt = decode(asr, slc(a, st, en))
        if txt:
            utts.append([st, en, spk, txt, False])
    del asr
    stats = {"changed": 0, "unchanged": 0, "rejected": 0, "skipped": 0}
    if llm is not None:
        for u in utts:
            u[3], outcome = llm.punctuate(u[3])
            stats[outcome] += 1
            u[4] = outcome == "changed"
    return finalize(utts), stats


def to_json(fin) -> dict:
    """Формат задания."""
    return {"transcript": [{"speaker_id": f"speaker_{k + 1}", "start_ms": int(round(st * 1000)),
                            "end_ms": int(round(en * 1000)), "text": t} for st, en, k, t, _ in fin]}
