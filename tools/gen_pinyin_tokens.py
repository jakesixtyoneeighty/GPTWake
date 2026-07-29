#!/usr/bin/env python3
"""生成 汉字 -> KWS token 词典，与 sherpa-onnx-cli text2token --tokens-type phone+ppinyin 一致。

    pip install pypinyin
    python3 gen_pinyin_tokens.py <tokens.txt> <out.txt>
"""
import sys
from pypinyin import lazy_pinyin, Style
from pypinyin.contrib.tone_convert import to_initials, to_finals_tone

tokens_path, out_path = sys.argv[1], sys.argv[2]
valid = set()
for line in open(tokens_path, encoding="utf-8"):
    parts = line.rsplit(" ", 1)
    if len(parts) == 2:
        valid.add(parts[0])

ok = rejected = 0
with open(out_path, "w", encoding="utf-8") as f:
    for cp in list(range(0x4E00, 0xA000)) + list(range(0x3400, 0x4DC0)):
        ch = chr(cp)
        py = lazy_pinyin(ch, style=Style.TONE, errors=lambda x: None)
        if not py or not py[0] or py[0] == ch:
            continue
        syl = py[0]
        toks = [t for t in (to_initials(syl, strict=False),
                            to_finals_tone(syl, strict=False)) if t]
        if toks and all(t in valid for t in toks):
            f.write(f"{ch}\t{' '.join(toks)}\t{syl}\n")
            ok += 1
        else:
            rejected += 1
print(f"mapped={ok} rejected={rejected} -> {out_path}")
