import io
import os
import subprocess
import sys
import tarfile
import tempfile
import threading
from functools import partial
from http.server import HTTPServer, SimpleHTTPRequestHandler

sys.path.insert(0, os.path.dirname(__file__))
import openstt_utils as U  # noqa: E402


def test_normalize_and_wer():
    assert U.normalize("Добрый день! Всё — кто-то.") == "добрый день все кто то"
    assert U.corpus_wer(["привет как дела"], ["Привет, как дела?"]) == 0
    assert abs(U.corpus_wer(["раз два три"], ["раз три"]) - 1 / 3) < 1e-9
    assert U.punctuation_rate(["а, б", "в г", ""]) == 0.5


def _make_archive(path, wav, n=6, order="audio_first"):
    """Архив в стиле open_stt: <subset>/<d>/<dd>/<id>.opus + .txt рядом."""
    with tarfile.open(path, "w:gz") as tf:
        for k in range(n):
            stem = f"subset/0/{k:02d}/utt{k}"
            opus = os.path.join(os.path.dirname(path), f"x{k}.opus")
            dur = 1.0 + k  # 1..6 c
            subprocess.run(["ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-ss", "0", "-t", str(dur), "-i", wav,
                            "-ar", "16000", "-ac", "1", "-c:a", "libopus", opus], check=True)
            txt = f"фраза номер {k}".encode()
            members = [("audio", opus), ("text", txt)]
            if order == "text_first" and k % 2:
                members.reverse()
            for kind, obj in members:
                if kind == "audio":
                    tf.add(obj, arcname=stem + ".opus")
                else:
                    ti = tarfile.TarInfo(stem + ".txt")
                    ti.size = len(obj)
                    tf.addfile(ti, io.BytesIO(obj))
            os.remove(opus)


def test_stream_subset_local_and_http(wav=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "artifacts", "test-results", "audio", "clean.wav")):
    with tempfile.TemporaryDirectory() as d:
        arc = os.path.join(d, "subset.tar.gz")
        _make_archive(arc, wav, order="text_first")
        # Локальный файл, ограничение по длительности 1.5..4.5 c → записи 2,3,4 c
        e = U.stream_subset(arc, os.path.join(d, "out1"), min_duration=1.5, max_duration=4.5)
        assert sorted(round(x["duration"]) for x in e) == [2, 3, 4], e
        assert all(os.path.getsize(x["audio_filepath"]) > 0 and x["audio_filepath"].endswith(".flac") for x in e)
        assert {x["text"] for x in e} == {"фраза номер 1", "фраза номер 2", "фраза номер 3"}
        import soundfile as sf
        info = sf.info(e[0]["audio_filepath"])
        assert info.samplerate == 16000 and info.channels == 1
        assert not os.path.exists(os.path.join(d, "out1", "_tmp"))

        # Через HTTP с лимитом часов: набрать ~5 c → должен остановиться раньше конца
        srv = HTTPServer(("127.0.0.1", 0), partial(SimpleHTTPRequestHandler, directory=d))
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        url = f"http://127.0.0.1:{srv.server_port}/subset.tar.gz"
        e2 = U.stream_subset(url, os.path.join(d, "out2"), max_hours=5 / 3600, min_duration=0.5)
        srv.shutdown()
        total = sum(x["duration"] for x in e2)
        assert 5 <= total < 21 and len(e2) < 6, (total, len(e2))

        U.write_manifest(e2, os.path.join(d, "m.jsonl"))
        assert U.read_manifest(os.path.join(d, "m.jsonl")) == e2


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            fn()
            print("PASS", name)
    print("ALL OK")
