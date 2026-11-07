"""Пунктуация локальной LLM — так же, как в приложении (LlmPunctuator.kt + PunctuationGuard.kt).

Тот же движок (LiteRT-LM 0.19.1), та же модель, тот же промпт и шаблон чата из
src/mobile/android/app/src/main/assets/llm, та же проверка слов. Текст передаётся списком
частей, как это делает Kotlin API.
"""
from __future__ import annotations

import os
import re
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LLM_ASSETS = ROOT / "src/mobile/android/app/src/main/assets/llm"
GUARD_CASES = ROOT / "src/mobile/android/app/src/test/resources/punctuation_guard_cases.json"

MAX_CHARS = 600          # как LlmPunctuator.MAX_CHARS
MAX_OUTPUT_TOKENS = 448  # как LlmPunctuator.MAX_OUTPUT_TOKENS

# ---------------------------------------------------------------- проверка слов (PunctuationGuard)

EDGE_PUNCT = ".,!?;:…«»\"“”„‘’'()[]{}—–‒-*"
_THINK = re.compile(r"<think>.*?</think>", re.S)
_WS = re.compile(r"\s+")
_QUOTES = [("«", "»"), ('"', '"'), ("“", "”"), ("„", "“")]


def clean(llm_output: str) -> str:
    t = _THINK.sub(" ", llm_output).replace("<think>", " ").replace("</think>", " ").strip()
    for o, c in _QUOTES:
        if len(t) >= 2 and t.startswith(o) and t.endswith(c):
            t = t[len(o):len(t) - len(c)].strip()
            break
    return _WS.sub(" ", t).strip()


def words(text: str) -> list[str]:
    out = []
    for tok in _WS.split(text):
        w = tok.strip(EDGE_PUNCT).lower()
        if w:
            out.append(w)
    return out


def same_words(original: str, candidate: str) -> bool:
    a = words(original)
    return bool(a) and a == words(candidate)


def choose(original: str, llm_output: str) -> tuple[str, bool]:
    cleaned = clean(llm_output)
    if cleaned and same_words(original, cleaned):
        return cleaned, True
    return original, False


# ---------------------------------------------------------------- LLM


class LlmPunctuator:
    def __init__(self, model_path: str, threads: int | None = None):
        import litert_lm

        self._lm = litert_lm
        self.system_prompt = (LLM_ASSETS / "system_prompt.txt").read_text(encoding="utf-8").strip()
        self.chat_template = (LLM_ASSETS / "qwen3_chat_template.jinja").read_text(encoding="utf-8")
        self.engine = litert_lm.Engine(
            model_path,
            backend=litert_lm.Backend.CPU(thread_count=threads or os.cpu_count()),
            # Кэш — во временной папке: рядом с моделью он попал бы в models-assets, а оттуда в APK
            cache_dir=os.path.join(tempfile.gettempdir(), "litertlm-cache"),
        )

    def punctuate(self, text: str) -> tuple[str, str]:
        """Возвращает (итоговый текст, исход): changed / unchanged / rejected / skipped."""
        src = text.strip()
        if not any(c.isalpha() for c in src) or len(src) > MAX_CHARS:
            return src, "skipped"
        conv = self.engine.create_conversation(
            system_message=self.system_prompt,
            sampler_config=self._lm.SamplerConfig(top_k=1, top_p=1.0, temperature=0.0),
            max_output_tokens=MAX_OUTPUT_TOKENS,
            chat_template=self.chat_template,
        )
        try:
            raw = str(conv.send_message({"role": "user", "content": [{"type": "text", "text": src}]}))
        finally:
            conv.close()
        out, ok = choose(src, raw)
        if not ok:
            return src, "rejected"
        return out, ("changed" if out != src else "unchanged")

    def close(self):
        self.engine.close()


if __name__ == "__main__":
    # Самопроверка на общих с Kotlin примерах
    import json

    cases = json.loads(GUARD_CASES.read_text(encoding="utf-8"))
    for c in cases:
        text, ok = choose(c["original"], c["llm"])
        assert ok == c["accept"] and text == c["result"], (c["name"], text, ok)
    print(f"PunctuationGuard: {len(cases)} примеров совпадают с Kotlin")
