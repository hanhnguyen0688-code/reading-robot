"""Passage tokenisation and word normalisation.

The passage is split into *reference words*. Each keeps the exact display
form (with punctuation, for the UI) and a normalised form used for matching.
"""
from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field

_WORD_RE = re.compile(r"[A-Za-z0-9]+(?:['’][A-Za-z]+)*")
_TOKEN_RE = re.compile(r"\S+")
_SENTENCE_END = re.compile(r"[.!?]+[\"'”’)]*$")

# British / American spelling variants that ASR engines swap freely.
# Matching treats them as the same word so a child is never penalised for
# the ASR's spelling choice.
SPELLING_VARIANTS: dict[str, str] = {
    "favorite": "favourite", "color": "colour", "colors": "colours",
    "neighbor": "neighbour", "neighbors": "neighbours", "honor": "honour",
    "mom": "mum", "gray": "grey", "center": "centre", "theater": "theatre",
    "realize": "realise", "realized": "realised", "organize": "organise",
    "traveled": "travelled", "traveling": "travelling", "flavor": "flavour",
    "humor": "humour", "behavior": "behaviour", "jewelry": "jewellery",
    "pajamas": "pyjamas", "cozy": "cosy", "practice": "practise",
}

NUMBER_WORDS: dict[str, str] = {
    "0": "zero", "1": "one", "2": "two", "3": "three", "4": "four", "5": "five",
    "6": "six", "7": "seven", "8": "eight", "9": "nine", "10": "ten",
    "11": "eleven", "12": "twelve", "20": "twenty", "100": "hundred",
}

# Common contractions ASR may expand or contract.
CONTRACTIONS: dict[str, str] = {
    "dont": "do not", "cant": "cannot", "wont": "will not", "im": "i am",
    "its": "it is", "isnt": "is not", "didnt": "did not", "wasnt": "was not",
    "thats": "that is", "lets": "let us", "hes": "he is", "shes": "she is",
}


def normalise(word: str) -> str:
    """Lower-case, strip accents/punctuation, unify spelling variants."""
    w = unicodedata.normalize("NFKD", word)
    w = "".join(c for c in w if not unicodedata.combining(c))
    w = w.lower().replace("’", "'")
    w = re.sub(r"[^a-z0-9']", "", w)
    w = w.strip("'")
    w = w.replace("'", "")
    w = NUMBER_WORDS.get(w, w)
    return SPELLING_VARIANTS.get(w, w)


def split_hypothesis(text: str) -> list[str]:
    """Split an ASR transcript into normalised words (contractions kept whole)."""
    out: list[str] = []
    for m in _WORD_RE.finditer(text):
        n = normalise(m.group(0))
        if n:
            out.append(n)
    return out


@dataclass
class RefWord:
    index: int
    display: str          # token as printed, e.g. "fridge,"
    norm: str             # matching form, e.g. "fridge"
    sentence: int         # sentence index (0-based)
    sentence_end: bool    # last word of its sentence


@dataclass
class Passage:
    id: str
    title: str
    text: str
    level: str = ""
    words: list[RefWord] = field(default_factory=list)
    sentences: list[list[int]] = field(default_factory=list)

    @property
    def word_count(self) -> int:
        return len(self.words)

    def sentence_text(self, s: int) -> str:
        return " ".join(self.words[i].display for i in self.sentences[s])


def build_passage(pid: str, title: str, text: str, level: str = "") -> Passage:
    """Tokenise a passage. Word count is *computed*, never typed by hand."""
    words: list[RefWord] = []
    sentence = 0
    for tok in _TOKEN_RE.findall(text):
        norm = normalise(tok)
        if not norm:          # stand-alone punctuation like "—"
            continue
        end = bool(_SENTENCE_END.search(tok))
        words.append(RefWord(len(words), tok, norm, sentence, end))
        if end:
            sentence += 1
    if words and not words[-1].sentence_end:
        words[-1].sentence_end = True
    sentences: list[list[int]] = []
    for w in words:
        while len(sentences) <= w.sentence:
            sentences.append([])
        sentences[w.sentence].append(w.index)
    return Passage(pid, title, text, level, words, sentences)


def edit_similarity(a: str, b: str) -> float:
    """1.0 = identical, 0.0 = nothing in common (normalised Levenshtein)."""
    if a == b:
        return 1.0
    if not a or not b:
        return 0.0
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return 1.0 - prev[-1] / max(len(a), len(b))


# Homophones a recogniser cannot distinguish acoustically. A child saying
# "two" for "to" read the passage correctly; the ASR just chose a spelling.
_HOMOPHONE_GROUPS = [
    {"to", "too", "two"}, {"there", "their", "theyre"}, {"for", "four"},
    {"by", "buy", "bye"}, {"hear", "here"}, {"see", "sea"}, {"won", "one"},
    {"new", "knew"}, {"no", "know"}, {"right", "write"}, {"sun", "son"},
    {"ate", "eight"}, {"blue", "blew"}, {"red", "read"}, {"tail", "tale"},
    {"whole", "hole"}, {"wear", "where"}, {"week", "weak"}, {"bear", "bare"},
    {"flower", "flour"}, {"its", "it's"}, {"your", "youre"}, {"mail", "male"},
]
_HOMOPHONE: dict[str, int] = {w: i for i, g in enumerate(_HOMOPHONE_GROUPS) for w in g}


def same_word(ref: str, hyp: str) -> bool:
    if ref == hyp:
        return True
    g1, g2 = _HOMOPHONE.get(ref), _HOMOPHONE.get(hyp)
    return g1 is not None and g1 == g2
