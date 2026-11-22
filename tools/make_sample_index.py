#!/usr/bin/env python3
"""Build a tiny Eco index in the exact format the app reads.

Format (SQLite):
  chunks(id INTEGER PRIMARY KEY, title TEXT, text TEXT, emb BLOB, scale REAL)
      emb   = 384 signed int8 values; the real vector is emb * scale
      the vector is L2-normalised *before* quantising
  meta(key TEXT PRIMARY KEY, value TEXT)
      embed_model=bge-small-en-v1.5, dim=384, quant=int8, version=...

Embeddings: bge-small-en-v1.5, CLS pooling, L2-normalised, no query/passage prefix.
Choose one embedder:
  --llama-embedding PATH --gguf PATH   llama.cpp's llama-embedding tool (same code path as the app)
  --sentence-transformers              pip install sentence-transformers (downloads BAAI/bge-small-en-v1.5)

Example:
  python3 tools/make_sample_index.py out.sqlite \\
      --llama-embedding llama.cpp/build/bin/llama-embedding \\
      --gguf app/src/main/assets/bge-small-en-v1.5-q8_0.gguf
"""
import argparse
import json
import math
import os
import sqlite3
import subprocess
import sys

DIM = 384

SAMPLE = [
    ("Ocean acidification",
     "Ocean acidification is the ongoing drop in the pH of seawater caused by the ocean absorbing "
     "carbon dioxide from the atmosphere. About a quarter of the CO2 released by burning fossil fuels "
     "dissolves into the sea, where it forms carbonic acid. This makes it harder for corals, oysters "
     "and some plankton to build their shells and skeletons from calcium carbonate."),
    ("Coral bleaching",
     "Coral bleaching happens when water that is too warm makes corals expel the algae living in their "
     "tissues. The algae give corals their colour and most of their food, so bleached corals turn white "
     "and can starve if high temperatures last for weeks."),
    ("Peatlands",
     "Peatlands cover only about three percent of the world's land but store roughly twice as much "
     "carbon as all the world's forests. When peat is drained for farming it dries out and releases "
     "that carbon as carbon dioxide. Rewetting drained peat stops these emissions."),
    ("Pollinators",
     "Bees, butterflies, hoverflies and other pollinators carry pollen between flowers, which most "
     "wild plants and many food crops need to set seed and fruit. Pollinators are declining because of "
     "habitat loss, pesticides, disease and climate change. Planting native flowers helps them."),
    ("Heatwaves",
     "Heatwaves are becoming more frequent and more intense as the climate warms. Older people, young "
     "children and people working outdoors are most at risk. Drinking water, staying in the shade and "
     "checking on neighbours who live alone can save lives."),
    ("Plastic pollution",
     "Millions of tonnes of plastic reach the ocean every year, mostly from poorly managed waste on "
     "land. Large pieces entangle and choke wildlife, while microplastics are eaten by plankton and "
     "fish. Reducing single-use plastics and improving waste collection cut the flow at its source."),
]


def embed_llama(binary, gguf, texts):
    sep = "<#eco-sep#>"
    out = subprocess.run(
        [binary, "-m", gguf, "--pooling", "cls", "--embd-normalize", "2",
         "--embd-output-format", "json", "--embd-separator", sep, "-p", sep.join(texts)],
        check=True, capture_output=True, text=True).stdout
    data = sorted(json.loads(out)["data"], key=lambda d: d["index"])
    return [d["embedding"] for d in data]


def embed_st(texts):
    from sentence_transformers import SentenceTransformer  # pylint: disable=import-outside-toplevel
    model = SentenceTransformer("BAAI/bge-small-en-v1.5")  # CLS pooling per its config
    return model.encode(texts, normalize_embeddings=True).tolist()


def quantise(vec):
    if len(vec) != DIM:
        sys.exit(f"embedding has {len(vec)} dims, expected {DIM}")
    norm = math.sqrt(sum(v * v for v in vec)) or 1.0
    vec = [v / norm for v in vec]
    scale = max(abs(v) for v in vec) / 127.0 or 1.0
    q = [max(-127, min(127, round(v / scale))) for v in vec]
    return bytes((x + 256) % 256 for x in q), scale


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("out")
    ap.add_argument("--llama-embedding")
    ap.add_argument("--gguf")
    ap.add_argument("--sentence-transformers", action="store_true")
    ap.add_argument("--version", default="sample-1")
    args = ap.parse_args()

    texts = [t for _, t in SAMPLE]
    if args.sentence_transformers:
        vecs = embed_st(texts)
    elif args.llama_embedding and args.gguf:
        vecs = embed_llama(args.llama_embedding, args.gguf, texts)
    else:
        ap.error("choose --llama-embedding PATH --gguf PATH, or --sentence-transformers")

    if os.path.exists(args.out):
        os.remove(args.out)
    db = sqlite3.connect(args.out)
    db.execute("CREATE TABLE chunks(id INTEGER PRIMARY KEY, title TEXT, text TEXT, emb BLOB, scale REAL)")
    db.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
    for i, ((title, text), vec) in enumerate(zip(SAMPLE, vecs), start=1):
        emb, scale = quantise(vec)
        db.execute("INSERT INTO chunks VALUES (?,?,?,?,?)", (i, title, text, emb, scale))
    db.executemany("INSERT INTO meta VALUES (?,?)", [
        ("embed_model", "bge-small-en-v1.5"), ("dim", str(DIM)), ("quant", "int8"), ("version", args.version)])
    db.commit()
    db.close()
    print(f"wrote {len(SAMPLE)} chunks to {args.out}")


if __name__ == "__main__":
    main()
