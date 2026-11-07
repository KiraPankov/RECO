"""WER на вручную размеченных контрольных наборах open_stt (качество разметки 99%).

    python openstt_eval.py --out results/openstt [--sets asr_calls_2_val,public_youtube700_val]
                           [--max-utts 2000] [--models DIR] [--no-llm]

Каждая запись в этих наборах — одна фраза одного говорящего, поэтому здесь проверяются
распознавание (Parakeet) и пунктуация LLM; диаризацию проверяет final_eval.py.
Обработка — как при повторном распознавании фрагмента в приложении: предобработка,
нарезка на куски ≤ 28 с, Parakeet, затем LLM с проверкой слов.

WER считается дважды: по тексту распознавателя и по итоговому тексту после LLM.
Они обязаны совпасть — LLM не может менять слова.
"""
from __future__ import annotations

import argparse
import csv
import json
import random
import re
import sys
import time
from pathlib import Path

import soundfile as sf

sys.path.insert(0, str(Path(__file__).parent))
import openstt_utils as U  # noqa: E402
import pipeline as P  # noqa: E402

DEFAULT_SETS = "asr_calls_2_val,public_youtube700_val,buriy_audiobooks_2_val"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--sets", default=DEFAULT_SETS)
    ap.add_argument("--max-utts", type=int, default=0, help="0 — все записи набора")
    ap.add_argument("--models", default=str(P.DEFAULT_MODELS))
    ap.add_argument("--data", default="/tmp/openstt")
    ap.add_argument("--no-llm", action="store_true")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    paths = P.model_paths(args.models)
    asr = P.create_asr(paths)
    llm = None
    if not args.no_llm:
        from llm_punctuation import LlmPunctuator

        llm = LlmPunctuator(str(paths["llm"]), threads=P.threads())

    rows = []
    for name in [s.strip() for s in args.sets.split(",") if s.strip()]:
        print(f"== {name}: загрузка", flush=True)
        entries = U.stream_subset(U.archive_url(name), f"{args.data}/{name}", min_duration=0.1, max_duration=600.0)
        if args.max_utts and len(entries) > args.max_utts:
            entries = random.Random(args.seed).sample(entries, args.max_utts)
        audio_sec = sum(e["duration"] for e in entries)
        refs, asr_hyps, final_hyps = [], [], []
        stats = {"changed": 0, "unchanged": 0, "rejected": 0, "skipped": 0}
        t_asr = t_llm = 0.0
        for i, e in enumerate(entries):
            raw, sr = sf.read(e["audio_filepath"], dtype="float32")
            a = P.preprocess(raw)
            t0 = time.time()
            pieces = P.split_long(0.0, len(a) / P.SR, 0, a)
            text = " ".join(t for t in (P.decode(asr, P.slc(a, st, en, pad=0.0)) for st, en, _ in pieces) if t).strip()
            t_asr += time.time() - t0
            final = text
            if llm is not None and text:
                t0 = time.time()
                final, outcome = llm.punctuate(text)
                t_llm += time.time() - t0
                stats[outcome] += 1
            refs.append(e["text"])
            asr_hyps.append(text)
            final_hyps.append(final)
            if (i + 1) % 500 == 0:
                print(f"  {i + 1}/{len(entries)}", flush=True)

        wer_asr = U.corpus_wer(refs, asr_hyps)
        wer_final = U.corpus_wer(refs, final_hyps)
        no_digits = [i for i, h in enumerate(asr_hyps) if not re.search(r"\d", h)]
        row = dict(
            set=name, utterances=len(entries), audio_h=round(audio_sec / 3600, 2),
            wer_asr_pct=round(wer_asr * 100, 2), wer_final_pct=round(wer_final * 100, 2),
            wer_no_digit_utts_pct=round(U.corpus_wer([refs[i] for i in no_digits], [asr_hyps[i] for i in no_digits]) * 100, 2),
            utts_with_digits=len(entries) - len(no_digits),
            punct_rate_asr_pct=round(U.punctuation_rate(asr_hyps) * 100, 1),
            punct_rate_final_pct=round(U.punctuation_rate(final_hyps) * 100, 1),
            llm_changed=stats["changed"], llm_unchanged=stats["unchanged"],
            llm_rejected=stats["rejected"], llm_skipped=stats["skipped"],
            rtf_asr=round(t_asr / max(audio_sec, 1e-6), 3), rtf_llm=round(t_llm / max(audio_sec, 1e-6), 3),
        )
        rows.append(row)
        print(row, flush=True)
        if abs(wer_asr - wer_final) > 1e-12:
            print("ОШИБКА: LLM изменила WER — проверка слов не сработала", flush=True)
            sys.exit(1)
        with open(out / f"{name}.jsonl", "w", encoding="utf-8") as f:
            for e, h1, h2 in zip(entries, asr_hyps, final_hyps):
                f.write(json.dumps({"audio": Path(e["audio_filepath"]).name, "ref": e["text"], "asr": h1, "final": h2},
                                   ensure_ascii=False) + "\n")

    if llm:
        llm.close()
    with open(out / "results.csv", "w", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    # Краткая сводка в Markdown (для отчёта GitHub Actions)
    with open(out / "summary.md", "w", encoding="utf-8") as f:
        f.write("| Набор | Фраз | Часов | WER распознавания | WER итог (после LLM) | WER без фраз с цифрами | "
                "Пунктуация до / после LLM | LLM: исправлено / без изменений / отклонено |\n|---|---|---|---|---|---|---|---|\n")
        for r in rows:
            f.write(f"| {r['set']} | {r['utterances']} | {r['audio_h']} | {r['wer_asr_pct']}% | {r['wer_final_pct']}% | "
                    f"{r['wer_no_digit_utts_pct']}% | {r['punct_rate_asr_pct']}% / {r['punct_rate_final_pct']}% | "
                    f"{r['llm_changed']} / {r['llm_unchanged']} / {r['llm_rejected']} |\n")
    print((out / "summary.md").read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
