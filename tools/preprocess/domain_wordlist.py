"""Curated formal/political/security-register Russian wordlist.

Hand-assembled real vocabulary for the target domain (MFA / Kremlin / TASS /
military doctrine). Each noun/adjective carries its declension class so the
engine in russian_morph.py can produce a correct case table; each verb pair
carries a deliberately-assigned Vendler/Aktionsart class (confidence "manual").

This replaces the placeholder `generate-mvp` scaffold with content that is
actually studyable. Stress marks live on the citation form only.

Noun tuple:  (citation_stressed, decl_class, translation, animate, numbers)
Adjective:   (citation_stressed, "adj", translation)
Verb pair:   (ipf, pf, translation, aktionsart_ipf, aktionsart_pf[, flags])
             flags may include "motion" (verb of motion) or "bi" (biaspectual,
             expressed as a single citation in the ipf slot with pf="").
"""

# --- Nouns & adjectives -------------------------------------------------
# numbers: "SG", "PL", or "SG+PL". Plurale tantum use class "pl_*" with an
# explicit table provided in PLURALE_TANTUM below.

NOUNS = [
    # core state / politics
    ("госуда́рство", "n_o", "state", False, "SG+PL"),
    ("прави́тельство", "n_o", "government", False, "SG+PL"),
    ("президе́нт", "m_hard", "president", True, "SG+PL"),
    ("мини́стр", "m_hard", "minister", True, "SG+PL"),
    ("министе́рство", "n_o", "ministry", False, "SG+PL"),
    ("ве́домство", "n_o", "agency/department", False, "SG+PL"),
    ("сове́т", "m_hard", "council", False, "SG+PL"),
    ("парла́мент", "m_hard", "parliament", False, "SG"),
    ("депута́т", "m_hard", "deputy/MP", True, "SG+PL"),
    ("власть", "f_soft", "power/authority", False, "SG+PL"),
    ("полити́ка", "f_a", "policy/politics", False, "SG"),
    ("поли́тик", "m_hard", "politician", True, "SG+PL"),
    ("зако́н", "m_hard", "law", False, "SG+PL"),
    ("законода́тельство", "n_o", "legislation", False, "SG"),
    ("указ", "m_hard", "decree", False, "SG+PL"),
    ("постановле́ние", "n_ie", "resolution/decree", False, "SG+PL"),
    ("реше́ние", "n_ie", "decision", False, "SG+PL"),
    ("заявле́ние", "n_ie", "statement", False, "SG+PL"),
    ("заседа́ние", "n_ie", "session/meeting", False, "SG+PL"),
    ("повестка", "f_a", "agenda", False, "SG"),
    ("конститу́ция", "f_iya", "constitution", False, "SG"),
    ("рефо́рма", "f_a", "reform", False, "SG+PL"),
    ("вы́боры", "pl_vybory", "election(s)", False, "PL"),
    ("кандида́т", "m_hard", "candidate", True, "SG+PL"),
    ("прези́диум", "m_hard", "presidium", False, "SG"),
    # diplomacy
    ("дипломати́я", "f_iya", "diplomacy", False, "SG"),
    ("посо́л", "m_hard", "ambassador", True, "SG+PL"),
    ("посо́льство", "n_o", "embassy", False, "SG+PL"),
    ("перегово́ры", "pl_peregovory", "negotiations", False, "PL"),
    ("соглаше́ние", "n_ie", "agreement", False, "SG+PL"),
    ("догово́р", "m_hard", "treaty/contract", False, "SG+PL"),
    ("договорённость", "f_soft", "understanding/arrangement", False, "SG+PL"),
    ("деклара́ция", "f_iya", "declaration", False, "SG+PL"),
    ("резолю́ция", "f_iya", "resolution", False, "SG+PL"),
    ("коммюнике́", "indecl", "communiqué", False, "SG"),
    ("делега́ция", "f_iya", "delegation", False, "SG+PL"),
    ("сторона́", "f_a", "side/party", False, "SG"),
    ("партнёр", "m_hard", "partner", True, "SG+PL"),
    ("сою́зник", "m_hard", "ally", True, "SG+PL"),
    ("проти́вник", "m_hard", "adversary/opponent", True, "SG+PL"),
    ("конфли́кт", "m_hard", "conflict", False, "SG+PL"),
    ("кри́зис", "m_hard", "crisis", False, "SG+PL"),
    ("напряжённость", "f_soft", "tension", False, "SG"),
    ("уре́гулирование", "n_ie", "settlement/resolution", False, "SG"),
    ("компроми́сс", "m_hard", "compromise", False, "SG+PL"),
    ("инициати́ва", "f_a", "initiative", False, "SG+PL"),
    ("са́ммит", "m_hard", "summit", False, "SG+PL"),
    ("встре́ча", "f_a", "meeting", False, "SG+PL"),
    ("ви́зит", "m_hard", "visit", False, "SG+PL"),
    ("прото́кол", "m_hard", "protocol", False, "SG+PL"),
    # security / military
    ("безопа́сность", "f_soft", "security", False, "SG"),
    ("оборо́на", "f_a", "defense", False, "SG"),
    ("вооруже́ние", "n_ie", "armament/weaponry", False, "SG+PL"),
    ("ору́жие", "n_ie", "weapon/arms", False, "SG"),
    ("а́рмия", "f_iya", "army", False, "SG+PL"),
    ("во́йско", "n_o", "troops/force", False, "SG"),
    ("войска́", "pl_voiska", "troops", False, "PL"),
    ("солда́т", "m_hard", "soldier", True, "SG+PL"),
    ("офице́р", "m_hard", "officer", True, "SG+PL"),
    ("кома́ндование", "n_ie", "command", False, "SG"),
    ("команди́р", "m_hard", "commander", True, "SG+PL"),
    ("генера́л", "m_hard", "general", True, "SG+PL"),
    ("разве́дка", "f_a", "intelligence/reconnaissance", False, "SG"),
    ("контрразве́дка", "f_a", "counterintelligence", False, "SG"),
    ("гра́ница", "f_a", "border", False, "SG+PL"),
    ("террори́зм", "m_hard", "terrorism", False, "SG"),
    ("угро́за", "f_a", "threat", False, "SG+PL"),
    ("нападе́ние", "n_ie", "attack/assault", False, "SG+PL"),
    ("оборо́нка", "f_a", "defense industry", False, "SG"),
    ("вторже́ние", "n_ie", "invasion", False, "SG+PL"),
    ("агре́ссия", "f_iya", "aggression", False, "SG"),
    ("санкция", "f_iya", "sanction", False, "SG+PL"),
    ("блока́да", "f_a", "blockade", False, "SG"),
    ("перемирие", "n_ie", "truce/ceasefire", False, "SG+PL"),
    ("капитуля́ция", "f_iya", "capitulation/surrender", False, "SG"),
    ("ми́ссия", "f_iya", "mission", False, "SG+PL"),
    ("опера́ция", "f_iya", "operation", False, "SG+PL"),
    ("учения", "pl_uchenia", "(military) exercises", False, "PL"),
    ("манёвр", "m_hard", "maneuver", False, "SG+PL"),
    ("ба́за", "f_a", "base", False, "SG+PL"),
    ("полиго́н", "m_hard", "proving ground/range", False, "SG+PL"),
    ("ра́кета", "f_a", "missile/rocket", False, "SG+PL"),
    ("боеголо́вка", "f_a", "warhead", False, "SG+PL"),
    ("вооружённость", "f_soft", "level of armament", False, "SG"),
    ("потенциа́л", "m_hard", "potential/capability", False, "SG+PL"),
    ("арсена́л", "m_hard", "arsenal", False, "SG+PL"),
    ("доктри́на", "f_a", "doctrine", False, "SG+PL"),
    ("страте́гия", "f_iya", "strategy", False, "SG+PL"),
    ("та́ктика", "f_a", "tactics", False, "SG"),
    ("деэскала́ция", "f_iya", "de-escalation", False, "SG"),
    ("эскала́ция", "f_iya", "escalation", False, "SG"),
    ("сде́рживание", "n_ie", "deterrence/containment", False, "SG"),
    ("развёртывание", "n_ie", "deployment", False, "SG"),
    ("вы́вод", "m_hard", "withdrawal/conclusion", False, "SG+PL"),
    ("ввод", "m_hard", "introduction/insertion", False, "SG"),
    # economy / energy
    ("эконо́мика", "f_a", "economy", False, "SG"),
    ("ры́нок", "m_hard", "market", False, "SG+PL"),
    ("торго́вля", "f_ya", "trade", False, "SG"),
    ("по́шлина", "f_a", "duty/tariff", False, "SG+PL"),
    ("эмба́рго", "indecl", "embargo", False, "SG"),
    ("экспорт", "m_hard", "export", False, "SG"),
    ("и́мпорт", "m_hard", "import", False, "SG"),
    ("поста́вка", "f_a", "supply/delivery", False, "SG+PL"),
    ("ресу́рс", "m_hard", "resource", False, "SG+PL"),
    ("энерге́тика", "f_a", "energy sector", False, "SG"),
    ("нефть", "f_soft", "oil", False, "SG"),
    ("газ", "m_hard", "gas", False, "SG"),
    ("трубопрово́д", "m_hard", "pipeline", False, "SG+PL"),
    ("валю́та", "f_a", "currency", False, "SG+PL"),
    ("инфля́ция", "f_iya", "inflation", False, "SG"),
    ("бюдже́т", "m_hard", "budget", False, "SG+PL"),
    ("долг", "m_hard", "debt", False, "SG"),
    ("инвести́ция", "f_iya", "investment", False, "SG+PL"),
    ("корпора́ция", "f_iya", "corporation", False, "SG+PL"),
    ("монопо́лия", "f_iya", "monopoly", False, "SG+PL"),
    # institutions / law / media
    ("организа́ция", "f_iya", "organization", False, "SG+PL"),
    ("учрежде́ние", "n_ie", "institution", False, "SG+PL"),
    ("комите́т", "m_hard", "committee", False, "SG+PL"),
    ("коми́ссия", "f_iya", "commission", False, "SG+PL"),
    ("трибуна́л", "m_hard", "tribunal", False, "SG+PL"),
    ("суд", "m_hard", "court", False, "SG+PL"),
    ("пра́во", "n_o", "law/right", False, "SG+PL"),
    ("обвине́ние", "n_ie", "accusation/charge", False, "SG+PL"),
    ("расследование", "n_ie", "investigation", False, "SG+PL"),
    ("доказа́тельство", "n_o", "proof/evidence", False, "SG+PL"),
    ("свиде́тель", "m_soft", "witness", True, "SG+PL"),
    ("наруше́ние", "n_ie", "violation", False, "SG+PL"),
    ("ответственность", "f_soft", "responsibility/liability", False, "SG"),
    ("заявитель", "m_soft", "applicant/declarant", True, "SG+PL"),
    ("докуме́нт", "m_hard", "document", False, "SG+PL"),
    ("докла́д", "m_hard", "report", False, "SG+PL"),
    ("отчёт", "m_hard", "account/report", False, "SG+PL"),
    ("исто́чник", "m_hard", "source", False, "SG+PL"),
    ("заявка", "f_a", "application/request", False, "SG+PL"),
    ("пресс-конференция", "f_iya", "press conference", False, "SG+PL"),
    ("сообще́ние", "n_ie", "message/report", False, "SG+PL"),
    ("информа́ция", "f_iya", "information", False, "SG"),
    ("сре́дство", "n_o", "means/medium", False, "SG+PL"),
    ("исто́рия", "f_iya", "history/story", False, "SG+PL"),
    # abstract / process
    ("вопро́с", "m_hard", "question/issue", False, "SG+PL"),
    ("проблема", "f_a", "problem", False, "SG+PL"),
    ("ситуа́ция", "f_iya", "situation", False, "SG+PL"),
    ("усло́вие", "n_ie", "condition", False, "SG+PL"),
    ("причи́на", "f_a", "cause/reason", False, "SG+PL"),
    ("после́дствие", "n_ie", "consequence", False, "SG+PL"),
    ("проце́сс", "m_hard", "process", False, "SG+PL"),
    ("разви́тие", "n_ie", "development", False, "SG"),
    ("стабильность", "f_soft", "stability", False, "SG"),
    ("интере́с", "m_hard", "interest", False, "SG+PL"),
    ("цель", "f_soft", "goal/target", False, "SG+PL"),
    ("зада́ча", "f_a", "task/objective", False, "SG+PL"),
    ("ме́ра", "f_a", "measure", False, "SG+PL"),
    ("шаг", "m_hard", "step", False, "SG+PL"),
    ("по́зиция", "f_iya", "position", False, "SG+PL"),
    ("подхо́д", "m_hard", "approach", False, "SG+PL"),
    ("при́нцип", "m_hard", "principle", False, "SG+PL"),
    ("факт", "m_hard", "fact", False, "SG+PL"),
    ("собы́тие", "n_ie", "event", False, "SG+PL"),
    ("обстановка", "f_a", "situation/environment", False, "SG"),
    ("давле́ние", "n_ie", "pressure", False, "SG"),
    ("подде́ржка", "f_a", "support", False, "SG"),
    ("сотру́дничество", "n_o", "cooperation", False, "SG"),
    ("противоречие", "n_ie", "contradiction", False, "SG+PL"),
    ("заинтересо́ванность", "f_soft", "interest/stake", False, "SG"),
]

ADJECTIVES = [
    ("я́дерный", "nuclear"),
    ("вое́нный", "military"),
    ("полити́ческий", "political"),
    ("стратеги́ческий", "strategic"),
    ("такти́ческий", "tactical"),
    ("оборо́нный", "defensive/defense"),
    ("госуда́рственный", "state/governmental"),
    ("междунаро́дный", "international"),
    ("двусторо́нний", "bilateral"),
    ("многосторо́нний", "multilateral"),
    ("официа́льный", "official"),
    ("дипломати́ческий", "diplomatic"),
    ("экономи́ческий", "economic"),
    ("энергети́ческий", "energy"),
    ("вооружённый", "armed"),
    ("боево́й", "combat"),
    ("гражда́нский", "civil/civilian"),
    ("федера́льный", "federal"),
    ("региона́льный", "regional"),
    ("национа́льный", "national"),
    ("суверенный", "sovereign"),
    ("сило́вой", "force/security (adj.)"),
    ("ра́кетный", "missile"),
    ("вра́жеский", "enemy/hostile"),
    ("ми́рный", "peaceful/peace"),
    ("агресси́вный", "aggressive"),
    ("санкцио́нный", "sanctions-related"),
    ("кри́зисный", "crisis"),
    ("правово́й", "legal"),
    ("незако́нный", "illegal"),
    ("принуди́тельный", "coercive"),
    ("консультати́вный", "advisory"),
    ("чрезвыча́йный", "extraordinary/emergency"),
    ("коллекти́вный", "collective"),
    ("глоба́льный", "global"),
    ("ло́кальный", "local"),
    ("масшта́бный", "large-scale"),
    ("целево́й", "targeted/dedicated"),
    ("превенти́вный", "preventive"),
    ("асимметри́чный", "asymmetric"),
    ("открове́нный", "frank/candid"),
    ("деструкти́вный", "destructive"),
    ("конструкти́вный", "constructive"),
    ("долгосро́чный", "long-term"),
    ("краткосро́чный", "short-term"),
    ("устойчивый", "stable/sustainable"),
    ("напряжённый", "tense/strained"),
    ("враждебный", "hostile"),
    ("наступа́тельный", "offensive"),
    ("неотло́жный", "urgent"),
]

# Plurale-tantum explicit tables (irregular genitive plural).
PLURALE_TANTUM = {
    "pl_voiska": {"NOM_PL": "войска", "GEN_PL": "войск", "DAT_PL": "войскам",
                  "ACC_PL": "войска", "INS_PL": "войсками", "PREP_PL": "войсках"},
    "pl_peregovory": {"NOM_PL": "переговоры", "GEN_PL": "переговоров", "DAT_PL": "переговорам",
                      "ACC_PL": "переговоры", "INS_PL": "переговорами", "PREP_PL": "переговорах"},
    "pl_vybory": {"NOM_PL": "выборы", "GEN_PL": "выборов", "DAT_PL": "выборам",
                  "ACC_PL": "выборы", "INS_PL": "выборами", "PREP_PL": "выборах"},
    "pl_uchenia": {"NOM_PL": "учения", "GEN_PL": "учений", "DAT_PL": "учениям",
                   "ACC_PL": "учения", "INS_PL": "учениями", "PREP_PL": "учениях"},
}

# --- Verb aspect pairs --------------------------------------------------
# (ipf, pf, translation, aktionsart_ipf, aktionsart_pf, *flags)
VERB_PAIRS = [
    ("обсужда́ть", "обсуди́ть", "to discuss", "activity", "accomplishment"),
    ("заявля́ть", "заяви́ть", "to declare/state", "activity", "achievement"),
    ("подпи́сывать", "подписа́ть", "to sign", "accomplishment", "accomplishment"),
    ("заключа́ть", "заключи́ть", "to conclude (a treaty)", "accomplishment", "achievement"),
    ("принима́ть", "приня́ть", "to adopt/accept", "activity", "achievement"),
    ("предлага́ть", "предложи́ть", "to propose", "activity", "achievement"),
    ("требовать", "потре́бовать", "to demand", "activity", "achievement"),
    ("осужда́ть", "осуди́ть", "to condemn", "activity", "achievement"),
    ("поддерживать", "поддержа́ть", "to support", "state", "achievement"),
    ("угрожа́ть", "пригрози́ть", "to threaten", "activity", "achievement"),
    ("наруша́ть", "нару́шить", "to violate", "activity", "achievement"),
    ("соблюда́ть", "соблюсти́", "to observe/comply with", "state", "accomplishment"),
    ("вводи́ть", "ввести́", "to introduce/impose", "activity", "achievement"),
    ("отменя́ть", "отмени́ть", "to cancel/lift", "activity", "achievement"),
    ("вводи́ть са́нкции", "", "to impose sanctions", "activity", "", "bi-skip"),
    ("развёртывать", "разверну́ть", "to deploy", "activity", "accomplishment"),
    ("выводи́ть", "вы́вести", "to withdraw", "activity", "accomplishment"),
    ("атакова́ть", "", "to attack", "activity", "", "bi"),
    ("наступа́ть", "наступи́ть", "to advance/set in", "activity", "achievement"),
    ("отступа́ть", "отступи́ть", "to retreat", "activity", "achievement"),
    ("защища́ть", "защити́ть", "to defend", "activity", "accomplishment"),
    ("захва́тывать", "захвати́ть", "to seize/capture", "activity", "achievement"),
    ("освобожда́ть", "освободи́ть", "to liberate/release", "activity", "accomplishment"),
    ("уничтожа́ть", "уничто́жить", "to destroy", "activity", "accomplishment"),
    ("укрепля́ть", "укрепи́ть", "to strengthen", "activity", "accomplishment"),
    ("обостря́ть", "обостри́ть", "to aggravate", "activity", "achievement"),
    ("урегули́ровать", "", "to settle/regulate", "accomplishment", "", "bi"),
    ("разреша́ть", "разреши́ть", "to resolve/permit", "activity", "achievement"),
    ("признава́ть", "призна́ть", "to recognize/admit", "state", "achievement"),
    ("отрица́ть", "", "to deny", "state", "", "bi-skip"),
    ("подтвержда́ть", "подтверди́ть", "to confirm", "activity", "achievement"),
    ("опроверга́ть", "опрове́ргнуть", "to refute/deny", "activity", "achievement"),
    ("сообща́ть", "сообщи́ть", "to report/inform", "activity", "achievement"),
    ("объявля́ть", "объяви́ть", "to announce", "activity", "achievement"),
    ("выступа́ть", "вы́ступить", "to speak/come out", "activity", "achievement"),
    ("обраща́ться", "обрати́ться", "to address/appeal", "activity", "achievement"),
    ("призыва́ть", "призва́ть", "to call upon", "activity", "achievement"),
    ("настаивать", "настоя́ть", "to insist", "state", "achievement"),
    ("достига́ть", "дости́чь", "to achieve/reach", "activity", "achievement"),
    ("стреми́ться", "", "to strive", "state", "", "bi-skip"),
    ("намерева́ться", "", "to intend", "state", "", "bi-skip"),
    ("планировать", "заплани́ровать", "to plan", "activity", "accomplishment"),
    ("рассма́тривать", "рассмотре́ть", "to consider/examine", "activity", "accomplishment"),
    ("анализи́ровать", "проанализи́ровать", "to analyze", "activity", "accomplishment"),
    ("оце́нивать", "оцени́ть", "to assess/evaluate", "activity", "achievement"),
    ("принима́ть реше́ние", "", "to make a decision", "accomplishment", "", "bi-skip"),
    ("сотру́дничать", "", "to cooperate", "activity", "", "bi-skip"),
    ("вмешиваться", "вмеша́ться", "to interfere/intervene", "activity", "achievement"),
    ("реаги́ровать", "отреаги́ровать", "to react", "activity", "achievement"),
    ("контроли́ровать", "проконтроли́ровать", "to control/monitor", "state", "accomplishment"),
    ("уси́ливать", "уси́лить", "to intensify/strengthen", "activity", "accomplishment"),
    ("сокраща́ть", "сократи́ть", "to reduce/cut", "activity", "achievement"),
    ("увели́чивать", "увели́чить", "to increase", "activity", "accomplishment"),
    ("ограни́чивать", "ограни́чить", "to limit/restrict", "activity", "achievement"),
    ("создава́ть", "созда́ть", "to create/establish", "activity", "accomplishment"),
    ("проводи́ть", "провести́", "to conduct/hold", "activity", "accomplishment"),
    ("обеспе́чивать", "обеспе́чить", "to ensure/provide", "state", "achievement"),
    ("направля́ть", "напра́вить", "to direct/dispatch", "activity", "achievement"),
]

# Verbs of motion (distinct drill subsystem per design §13.3) -------------
MOTION_VERBS = [
    ("идти́", "пойти́", "to go (on foot)", "activity", "achievement"),
    ("е́хать", "пое́хать", "to go (by vehicle)", "activity", "achievement"),
    ("прибыва́ть", "прибы́ть", "to arrive", "activity", "achievement"),
    ("отправля́ться", "отпра́виться", "to set off/depart", "activity", "achievement"),
    ("возвраща́ться", "верну́ться", "to return", "activity", "achievement"),
]


# --- Extended corpus merge ----------------------------------------------
# The hand-curated lists above are the trusted core. The `extended_*` modules
# (250 nouns, 50 adjectives, 90 verbs, generated against the domain frequency
# list) are merged in below, deduplicated by normalized lemma so a curated
# entry always wins over an extended duplicate.

def _norm_lemma(citation: str) -> str:
    """Strip stress marks, lowercase, fold ё→е — mirrors RussianForms.normalize."""
    return (citation.strip().lower()
            .replace("́", "").replace("̀", "").replace("ё", "е"))


def _extend_unique(base, extra, key):
    """Append entries from `extra` whose key is not already present in `base`."""
    seen = {key(e) for e in base}
    for e in extra:
        k = key(e)
        if k not in seen:
            base.append(e)
            seen.add(k)
    return base


try:
    from extended_nouns_adjectives import EXTENDED_NOUNS, EXTENDED_ADJECTIVES
    from extended_verb_pairs import EXTENDED_VERB_PAIRS, EXTENDED_MOTION_VERBS

    _extend_unique(NOUNS, EXTENDED_NOUNS, key=lambda e: _norm_lemma(e[0]))
    _extend_unique(ADJECTIVES, EXTENDED_ADJECTIVES, key=lambda e: _norm_lemma(e[0]))
    # verbs keyed on the ipf/citation slot (the lemma the partner index uses)
    _extend_unique(VERB_PAIRS, EXTENDED_VERB_PAIRS, key=lambda e: _norm_lemma(e[0]))
    _extend_unique(MOTION_VERBS, EXTENDED_MOTION_VERBS, key=lambda e: _norm_lemma(e[0]))
except ImportError:
    # Extended modules absent: fall back to the curated core only.
    pass
