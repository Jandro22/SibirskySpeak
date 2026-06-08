"""Rule-based Russian nominal declension engine.

Produces singular (and, where requested, plural) case tables for regular
nouns and one-gender adjective paradigms. The engine handles the standard
hard/soft stems plus the two spelling rules that matter most for formal
vocabulary:

  * the 7-letter rule: after г к х ж ч ш щ write и, not ы;
  * the hushing rule: after ж ч ш щ an unstressed о ending becomes е.

It deliberately covers the *regular* paradigms only. Irregular nouns
(e.g. имя, путь, мать) are supplied with explicit tables in the wordlist
rather than run through the engine. Declension forms are emitted WITHOUT
stress marks, matching the app convention (the stressed citation form lives
in Note.russian; the reader normalizes stress away before matching).
"""
from __future__ import annotations

VELARS = set("гкх")
HUSHES = set("жчшщ")
SIBILANT_C = "ц"


def strip_stress(text: str) -> str:
    return text.replace("́", "").replace("̀", "")


def _ы_or_и(stem: str) -> str:
    return "и" if stem and stem[-1] in (VELARS | HUSHES) else "ы"


def _hush_ins_m(stem: str) -> str:
    # masculine/neuter instrumental: hushing/ц stems take -ем (unstressed
    # assumption, true for the formal vocabulary used here), otherwise -ом
    return "ем" if stem and stem[-1] in (HUSHES | {SIBILANT_C}) else "ом"


def _hush_ins_f(stem: str) -> str:
    # feminine -а instrumental: hushing/ц stems take -ей, otherwise -ой
    return "ей" if stem and stem[-1] in (HUSHES | {SIBILANT_C}) else "ой"


def decline_noun(citation: str, decl_class: str, animate: bool = False,
                 numbers: tuple[str, ...] = ("SG",)) -> dict[str, str]:
    """Return a flat {CASE_NUMBER: form} table for a regular noun.

    citation must be the unstressed nominative singular (or the plurale-tantum
    nominative for pl-only classes).
    """
    w = strip_stress(citation)
    table: dict[str, str] = {}

    def sg(stem: str, gen: str, dat: str, acc: str, ins: str, prep: str) -> None:
        table["NOM_SG"] = w
        table["GEN_SG"] = stem + gen
        table["DAT_SG"] = stem + dat
        # Animacy only affects the masculine accusative (acc == "" here): animate
        # masc = genitive, inanimate masc = nominative. Feminine nouns pass their
        # own explicit accusative (-у/-ю), which is correct regardless of animacy;
        # neuter passes the nominative. Only fall back to the animate rule when no
        # explicit accusative is given (the masculine classes).
        table["ACC_SG"] = acc if acc else ((stem + gen) if animate else w)
        table["INS_SG"] = stem + ins
        table["PREP_SG"] = stem + prep

    if "SG" in numbers:
        if decl_class == "m_hard":
            stem = w
            sg(stem, "а", "у", "", _hush_ins_m(stem), "е")
        elif decl_class == "m_fleeting":
            # Masculine noun with a fleeting vowel (беглая гласная): the о/е/ё before
            # the final consonant drops in every oblique case (рынок→рынка, посол→посла,
            # авианосец→авианосца). The nominative keeps it; the oblique stem is the
            # citation minus that penultimate vowel. Used only for genuinely-fleeting
            # nouns (NOT lookalikes like энергоблок), assigned explicitly in the list.
            stem = w[:-2] + w[-1]
            sg(stem, "а", "у", "", _hush_ins_m(stem), "е")
        elif decl_class == "m_j":
            stem = w[:-1]
            sg(stem, "я", "ю", "", "ем", "е")
        elif decl_class == "m_iy":  # -ий
            stem = w[:-2]
            table["NOM_SG"] = w
            table["GEN_SG"] = stem + "ия"
            table["DAT_SG"] = stem + "ию"
            table["ACC_SG"] = (stem + "ия") if animate else w
            table["INS_SG"] = stem + "ием"
            table["PREP_SG"] = stem + "ии"
        elif decl_class == "m_soft":  # -ь
            stem = w[:-1]
            sg(stem, "я", "ю", "", "ем", "е")
        elif decl_class == "f_a":
            stem = w[:-1]
            sg(stem, _ы_or_и(stem), "е", stem + "у", _hush_ins_f(stem), "е")
        elif decl_class == "f_ya":  # -я (not -ия)
            stem = w[:-1]
            sg(stem, "и", "е", stem + "ю", "ей", "е")
        elif decl_class == "f_iya":  # -ия
            root = w[:-2]
            table["NOM_SG"] = w
            table["GEN_SG"] = root + "ии"
            table["DAT_SG"] = root + "ии"
            # Feminine -ия accusative is -ию regardless of animacy (Мария → Марию).
            table["ACC_SG"] = root + "ию"
            table["INS_SG"] = root + "ией"
            table["PREP_SG"] = root + "ии"
        elif decl_class == "f_soft":  # -ь
            stem = w[:-1]
            table["NOM_SG"] = w
            table["GEN_SG"] = stem + "и"
            table["DAT_SG"] = stem + "и"
            # Feminine soft-sign accusative singular equals the nominative
            # (дверь → дверь), for both animate and inanimate nouns.
            table["ACC_SG"] = w
            table["INS_SG"] = stem + "ью"
            table["PREP_SG"] = stem + "и"
        elif decl_class == "n_o":
            stem = w[:-1]
            sg(stem, "а", "у", w, _hush_ins_m(stem), "е")
            table["ACC_SG"] = w
        elif decl_class == "n_e":  # -е (not -ие)
            stem = w[:-1]
            sg(stem, "я", "ю", w, "ем", "е")
            table["ACC_SG"] = w
        elif decl_class == "n_ie":  # -ие
            root = w[:-2]
            table["NOM_SG"] = w
            table["GEN_SG"] = root + "ия"
            table["DAT_SG"] = root + "ию"
            table["ACC_SG"] = w
            table["INS_SG"] = root + "ием"
            table["PREP_SG"] = root + "ии"
        else:
            raise ValueError(f"unknown singular class {decl_class!r} for {citation!r}")

    if "PL" in numbers:
        _decline_plural(table, w, decl_class, animate)

    return table


def _decline_plural(table: dict[str, str], w: str, decl_class: str, animate: bool) -> None:
    if decl_class in ("m_hard",):
        stem = w
        nom = stem + ("и" if stem[-1] in (VELARS | HUSHES) else "ы")
        gen = stem + ("ей" if stem[-1] in HUSHES else "ов")
        _set_plural(table, stem, nom, gen, "ам", "ами", "ах", animate)
    elif decl_class == "m_fleeting":
        stem = w[:-2] + w[-1]  # drop the fleeting vowel
        nom = stem + ("и" if stem[-1] in (VELARS | HUSHES) else "ы")
        gen = stem + ("ев" if stem[-1] == SIBILANT_C else ("ей" if stem[-1] in HUSHES else "ов"))
        _set_plural(table, stem, nom, gen, "ам", "ами", "ах", animate)
    elif decl_class == "m_j":  # -й, e.g. случай -> случаи/случаев
        stem = w[:-1]
        _set_plural(table, stem, stem + "и", stem + "ев", "ям", "ями", "ях", animate)
    elif decl_class == "m_iy":  # -ий, e.g. сценарий -> сценарии/сценариев
        stem = w[:-2]
        _set_plural(table, stem, stem + "ии", stem + "иев", "иям", "иями", "иях", animate)
    elif decl_class == "m_soft":
        stem = w[:-1]
        _set_plural(table, stem, stem + "и", stem + "ей", "ям", "ями", "ях", animate)
    elif decl_class == "f_a":
        stem = w[:-1]
        nom = stem + ("и" if stem[-1] in (VELARS | HUSHES) else "ы")
        _set_plural(table, stem, nom, stem, "ам", "ами", "ах", animate)
    elif decl_class == "f_iya":
        root = w[:-2]
        _set_plural(table, root, root + "ии", root + "ий", "иям",
                    "иями", "иях", animate, gen_form=root + "ий")
    elif decl_class == "f_soft":
        stem = w[:-1]
        _set_plural(table, stem, stem + "и", stem + "ей", "ям", "ями", "ях", animate)
    elif decl_class == "n_o":
        stem = w[:-1]
        _set_plural(table, stem, stem + "а", stem, "ам", "ами", "ах", animate)
    elif decl_class == "n_ie":
        root = w[:-2]
        _set_plural(table, root, root + "ия", root + "ий", "иям",
                    "иями", "иях", animate, gen_form=root + "ий")
    else:
        raise ValueError(f"no plural rule for class {decl_class!r}")


def _set_plural(table, stem, nom, gen, dat, ins, prep, animate, gen_form=None):
    g = gen_form if gen_form is not None else gen
    table["NOM_PL"] = nom
    table["GEN_PL"] = g
    table["DAT_PL"] = stem + dat
    table["INS_PL"] = stem + ins
    table["PREP_PL"] = stem + prep
    table["ACC_PL"] = g if animate else nom


def decline_plurale_tantum(citation: str, table_pl: dict[str, str]) -> dict[str, str]:
    """Plurale-tantum nouns supply their PL table explicitly (irregular gen)."""
    return {k: strip_stress(v) for k, v in table_pl.items()}


def decline_adjective(citation: str, gender: str = "M") -> dict[str, str]:
    """Full hard/soft adjective paradigm (all genders, numbers, cases).

    Masculine-singular forms use the standard CASE_SG keys so the app turns them
    into (consistent, masculine) CASE_FILL drills. Feminine/neuter/plural forms
    use gender/number-prefixed keys (FEM_*, NEUT_*, PL_*) which the app's card
    generator ignores but the reader form index still consumes — so every
    inflected adjective surface form is recognised for coverage, while grammar
    drilling stays masculine-consistent.
    """
    w = strip_stress(citation)
    if not w.endswith(("ый", "ой", "ий")):
        raise ValueError(f"unhandled adjective ending: {citation!r}")
    stem = w[:-2]
    last = stem[-1] if stem else ""
    velar = last in VELARS
    husher = last in HUSHES
    stressed_end = w.endswith("ой")
    soft = w.endswith("ий") and not velar and not husher

    if soft:
        # синий-type: soft vowels throughout, no о/ы
        return {
            "NOM_SG": w,
            "GEN_SG": stem + "его", "DAT_SG": stem + "ему", "ACC_SG": w,
            "INS_SG": stem + "им", "PREP_SG": stem + "ем",
            "FEM_NOM": stem + "яя", "FEM_GEN": stem + "ей", "FEM_DAT": stem + "ей",
            "FEM_ACC": stem + "юю", "FEM_INS": stem + "ей", "FEM_PREP": stem + "ей",
            "NEUT_NOM": stem + "ее",
            "PL_NOM": stem + "ие", "PL_GEN": stem + "их", "PL_DAT": stem + "им",
            "PL_ACC": stem + "ие", "PL_INS": stem + "ими", "PL_PREP": stem + "их",
        }

    i_for_y = velar or husher          # 7-letter / hushing rule: ы -> и
    e_for_o = husher and not stressed_end  # unstressed о -> е after hushers

    def end(s: str) -> str:
        if i_for_y:
            s = s.replace("ы", "и")
        if e_for_o:
            s = s.replace("о", "е")
        return stem + s

    return {
        "NOM_SG": w,
        "GEN_SG": end("ого"), "DAT_SG": end("ому"), "ACC_SG": w,
        "INS_SG": end("ым"), "PREP_SG": end("ом"),
        "FEM_NOM": end("ая"), "FEM_GEN": end("ой"), "FEM_DAT": end("ой"),
        "FEM_ACC": end("ую"), "FEM_INS": end("ой"), "FEM_PREP": end("ой"),
        "NEUT_NOM": end("ое"),
        "PL_NOM": end("ые"), "PL_GEN": end("ых"), "PL_DAT": end("ым"),
        "PL_ACC": end("ые"), "PL_INS": end("ыми"), "PL_PREP": end("ых"),
    }


def past_masculine(infinitive: str) -> str:
    """Regular masculine past tense: -ть -> -л, -ться -> -лся.

    Correct for the overwhelming majority of -ать/-ять/-ить/-еть/-овать verbs
    used in formal register. Irregular stems (-ти, -чь, нести-type) are not
    produced here; the wordlist avoids them for the aspect drill.
    """
    w = strip_stress(infinitive)
    if w.endswith("ться"):
        return w[:-4] + "лся"
    if w.endswith("ть"):
        return w[:-2] + "л"
    return w
