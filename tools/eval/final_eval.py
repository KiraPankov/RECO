"""Тест конвейера на тестовых диалогах artifacts/test-results: WER, диаризация, границы реплик, LLM.

    python final_eval.py ../../artifacts/test-results [--models DIR] [--no-llm]

Модели по умолчанию — из src/mobile/android/app/models-assets/models (их собирает ./gradlew :app:fetchModels).
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import time
from pathlib import Path

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).parent))
import pipeline as P  # noqa: E402
from openstt_utils import corpus_wer, normalize, punctuation_rate  # noqa: E402


def metrics(fin, truth):
    ref = " ".join(t["text"] for t in truth)
    hyp = " ".join(u[3] for u in fin)
    w = corpus_wer([ref], [hyp])
    n = int(max(t["end"] for t in truth) * 100) + 1
    speakers = sorted({t["spk"] for t in truth})
    rl = np.full(n, -1)
    hl = np.full(n, -1)
    for t in truth:
        rl[int(t["start"] * 100):int(t["end"] * 100)] = speakers.index(t["spk"])
    for u in fin:
        hl[int(u[0] * 100):min(n, int(u[1] * 100))] = u[2]
    m = rl >= 0
    acc, used, mp = 0, set(), {}
    pairs = ((np.sum((rl == r) & (hl == h)), r, h) for r in range(len(speakers)) for h in set(hl[m]) - {-1})
    for c, r, h in sorted(pairs, reverse=True):
        if r not in mp and h not in used:
            mp[r] = h
            used.add(h)
            acc += c
    se = np.mean([min(abs(u[0] - t["start"]) for u in fin) for t in truth]) * 1000 if fin else float("nan")
    ee = np.mean([min(abs(u[1] - t["end"]) for u in fin) for t in truth]) * 1000 if fin else float("nan")
    return w, acc / m.sum(), se, ee, len(set(hl[m]) - {-1}), len(speakers)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base", help="каталог artifacts/test-results")
    ap.add_argument("--models", default=str(P.DEFAULT_MODELS))
    ap.add_argument("--no-llm", action="store_true", help="без пунктуации LLM")
    args = ap.parse_args()

    base = Path(args.base)
    truth = json.load(open(base / "audio/reference.json", encoding="utf-8"))
    paths = P.model_paths(args.models)
    llm = None
    if not args.no_llm:
        from llm_punctuation import LlmPunctuator

        llm = LlmPunctuator(str(paths["llm"]), threads=P.threads())

    (base / "output").mkdir(exist_ok=True)
    rows = []
    for name in ["clean", "noisy_snr5", "quiet_dc"]:
        raw, sr = sf.read(base / f"audio/{name}.wav", dtype="float32")
        assert sr == P.SR
        t0 = time.time()
        fin, st = P.transcribe(raw, paths, llm)
        el = time.time() - t0
        w, acc, se, ee, k, k_ref = metrics(fin, truth)
        json.dump(P.to_json(fin), open(base / f"output/{name}.json", "w", encoding="utf-8"), ensure_ascii=False, indent=2)
        rows.append(dict(
            test_id=name, file=f"audio/{name}.wav", duration_s=round(len(raw) / P.SR, 1), processing_s=round(el, 1),
            rtf=round(el / (len(raw) / P.SR), 3), wer_pct=round(w * 100, 1), diarization_acc_pct=round(float(acc) * 100, 1),
            speakers_found=k, speakers_reference=k_ref, start_err_ms=round(float(se)), end_err_ms=round(float(ee)),
            utterances=len(fin), reference_utterances=len(truth),
            punct_rate_pct=round(punctuation_rate([u[3] for u in fin]) * 100, 1),
            llm_changed=st["changed"], llm_unchanged=st["unchanged"], llm_rejected=st["rejected"], llm_skipped=st["skipped"],
        ))
        print(rows[-1], flush=True)
    if llm:
        llm.close()
    with open(base / "results.csv", "w", newline="") as fh:
        wr = csv.DictWriter(fh, fieldnames=list(rows[0]))
        wr.writeheader()
        wr.writerows(rows)


if __name__ == "__main__":
    main()
