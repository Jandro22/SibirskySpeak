"""Audit reader-text coverage against the deck.

For every word in the bundled reader texts, check whether it resolves to a deck
note via the same surface forms the app recognizes (russian, lemma, declension
values, stored verb forms, regular present/past, and the closed-class paradigms).
Prints only unresolved word *types* and counts -- never full text passages.
"""
import json
import re
import unicodedata
from pathlib import Path
from collections import Counter

ASSETS = Path(__file__).resolve().parents[2] / "app/src/main/assets"
NOTES = ASSETS / "bootstrap_notes.jsonl"
TEXTS = ASSETS / "bootstrap_reader_texts.jsonl"

WORD_RE = re.compile(r"[^\W\d_]+", re.UNICODE)


def normalize(s):
    s = s.strip().lower().replace("ё", "е")  # ё -> е
    s = unicodedata.normalize("NFD", s)
    return s.replace("́", "").replace("̈", "")


# Closed-class paradigms mirrored from RussianForms.kt
CLOSED = {
    "я": "я меня мне мной мною",
    "ты": "ты тебя тебе тобой тобою",
    "он": "он его него ему нему им ним нём",
    "оно": "оно его него ему нему им ним нём",
    "она": "она её неё ей ней ею нею",
    "мы": "мы нас нам нами",
    "вы": "вы вас вам вами",
    "они": "они их них им ним ими ними",
    "мой": "мой моя моё мои моего моей моих моему моим моём мою моими",
    "твой": "твой твоя твоё твои твоего твоей твоих твоему твоим твоём твою твоими",
    "свой": "свой своя своё свои своего своей своих своему своим своём свою своими",
    "наш": "наш наша наше наши нашего нашей наших нашему нашим нашем нашу нашими",
    "ваш": "ваш ваша ваше ваши вашего вашей ваших вашему вашим вашем вашу вашими",
    "этот": "этот эта это эти этого этой этих этому этим этом эту этими",
    "тот": "тот та то те того той тех тому тем том ту теми",
    "весь": "весь вся всё все всего всей всех всему всем всём всю всеми",
    "сам": "сам сама само сами самого самой самих самому самим самом саму самими",
    "кто": "кто кого кому кем ком",
    "что": "что чего чему чем чём",
    "быть": "быть есть буду будешь будет будем будете будут был была было были будь будьте будучи",
    "мочь": "мочь могу можешь может можем можете могут мог могла могло могли",
    "хотеть": "хотеть хочу хочешь хочет хотим хотите хотят хотел хотела хотело хотели",
    "идти": "идти иду идёшь идёт идём идёте идут шёл шла шло шли иди идите",
    "который": "который которого которому которым котором которая которой которую которое которые которых которыми",
    "человек": "человек человека человеку человеком человеке люди людей людям людьми людях",
    "себя": "себя себе собой собою",
    "самый": "самый самого самому самым самом самая самой самую самое самые самых самыми",
    "время": "время времени временем времена времён временам временами временах",
    "о": "о об обо",
    "один": "один одна одно одни одного одной одному одним одном одну одними одних",
    "два": "два две двух двум двумя",
    "восемьдесят": "восемьдесят восьмидесяти восьмьюдесятью",
}

ADJ_ENDINGS = "ый ий ой ого его ому ему ым им ом ем ая яя ой ей ую юю ое ее ые ие ых их ыми ими".split()
IRREG_COMP = {
    "большой": "больше", "хороший": "лучше", "плохой": "хуже", "маленький": "меньше",
    "высокий": "выше", "низкий": "ниже", "старый": "старше", "молодой": "младше",
    "далёкий": "дальше", "близкий": "ближе", "дорогой": "дороже", "дешёвый": "дешевле",
    "широкий": "шире", "узкий": "уже", "долгий": "дольше", "ранний": "раньше",
}


NOUN_ENDINGS = ["", "а", "я", "у", "ю", "ом", "ем", "е", "и", "ы", "ой", "ей",
                "ь", "ью", "ам", "ям", "ами", "ями", "ах", "ях", "ов", "ев", "ей"]


def noun_forms(lemma):
    raw = lemma.strip().lower()
    if len(raw) < 2:
        return set()
    last = raw[-1]
    stems = {raw[:-1] if last in "аяоейь" else raw}
    if last not in "аяоеийуыюёь" and len(raw) >= 3 and raw[-2] in "ое":
        stems.add(raw[:-2] + last)
    out = {raw}
    for stem in stems:
        for e in NOUN_ENDINGS:
            out.add(stem + e)
    return out


def adjective_forms(lemma):
    raw = lemma.strip().lower()
    if len(raw) < 4 or raw[-2:] not in ("ый", "ий", "ой"):
        return set()
    stem = raw[:-2]
    out = {stem + e for e in ADJ_ENDINGS} | {stem + "ее", stem + "ей", raw}
    if raw in IRREG_COMP:
        out.add(IRREG_COMP[raw])
    return out

PRES_A = ["ю", "ешь", "ет", "ем", "ете", "ют"]
PRES_I = ["ю", "ишь", "ит", "им", "ите", "ят"]
PAST = ["л", "ла", "ло", "ли"]


def regular_forms(lemma):
    out = set()
    if lemma.endswith("ть"):
        stem = lemma[:-2]
        for suf in PAST:
            out.add(stem + suf)
        if lemma.endswith(("ать", "ять", "еть")):
            for suf in PRES_A:
                out.add(stem + suf)
        elif lemma.endswith("ить"):
            for suf in PRES_I:
                out.add(stem + suf)
    return out


def build_forms():
    forms = set()
    for line in NOTES.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        n = json.loads(line)
        if n.get("translation") == "lookup pending":
            continue
        lemma = normalize(n.get("lemma", ""))
        forms.add(normalize(n.get("russian", "")))
        forms.add(lemma)
        closed = {normalize(k): v for k, v in CLOSED.items()}
        if lemma in closed:
            for f in closed[lemma].split():
                forms.add(normalize(f))
        pos = (n.get("pos") or n.get("partOfSpeech") or "").lower()
        if pos == "verb":
            forms |= {normalize(f) for f in regular_forms(lemma)}
        if pos in ("adj", "adjective"):
            forms |= {normalize(f) for f in adjective_forms(n.get("lemma", ""))}
        if pos == "noun":
            forms |= {normalize(f) for f in noun_forms(n.get("lemma", ""))}
        decl = n.get("declensionJson")
        if isinstance(decl, dict):
            blocks = [decl.get("cases", decl), decl.get("verbForms", {})]
            for b in blocks:
                if isinstance(b, dict):
                    for v in b.values():
                        if isinstance(v, str) and v:
                            forms.add(normalize(v))
    forms.discard("")
    return forms


def main():
    forms = build_forms()
    unresolved = Counter()
    total_tokens = 0
    total_texts = 0
    for line in TEXTS.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        t = json.loads(line)
        total_texts += 1
        # Strip combining stress/diaeresis first so "чита́ю" tokenizes as one word
        # (matching the app's [\p{L}́]+ tokenizer), not "чита" + "ю".
        body = t.get("body", "").replace("́", "").replace("̈", "")
        for tok in WORD_RE.findall(body):
            total_tokens += 1
            if normalize(tok) not in forms:
                unresolved[normalize(tok)] += 1
    covered = total_tokens - sum(unresolved.values())
    print(f"texts={total_texts} tokens={total_tokens} "
          f"coverage={covered/total_tokens*100:.1f}% "
          f"unresolved_types={len(unresolved)} unresolved_tokens={sum(unresolved.values())}")
    # Per-text coverage to see where the gap concentrates.
    print("--- per-text coverage (low to high) ---")
    rows = []
    for line in TEXTS.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        t = json.loads(line)
        body = t.get("body", "").replace("́", "").replace("̈", "")
        toks = WORD_RE.findall(body)
        if not toks:
            continue
        miss = sum(1 for tok in toks if normalize(tok) not in forms)
        rows.append(((len(toks) - miss) / len(toks), t.get("title", "?")[:34], len(toks), miss))
    for cov, title, n, miss in sorted(rows):
        print(f"{cov*100:5.1f}%  miss={miss:3d}/{n:3d}  {title}")
    print("--- all unresolved (word: count) ---")
    for w, c in unresolved.most_common():
        print(f"{w}: {c}")


if __name__ == "__main__":
    main()
