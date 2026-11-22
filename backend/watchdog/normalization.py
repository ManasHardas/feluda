"""Address normalization. Deterministic and local: no external geocoding service is called."""

from __future__ import annotations

import re
import unicodedata

_SUFFIXES = {
    "street": "st",
    "str": "st",
    "st": "st",
    "avenue": "ave",
    "av": "ave",
    "ave": "ave",
    "road": "rd",
    "rd": "rd",
    "drive": "dr",
    "drv": "dr",
    "dr": "dr",
    "lane": "ln",
    "ln": "ln",
    "court": "ct",
    "crt": "ct",
    "ct": "ct",
    "circle": "cir",
    "circ": "cir",
    "cir": "cir",
    "boulevard": "blvd",
    "boul": "blvd",
    "blvd": "blvd",
    "place": "pl",
    "pl": "pl",
    "terrace": "ter",
    "terr": "ter",
    "ter": "ter",
    "trail": "trl",
    "trl": "trl",
    "parkway": "pkwy",
    "pky": "pkwy",
    "pkwy": "pkwy",
    "way": "way",
    "highway": "hwy",
    "hwy": "hwy",
    "square": "sq",
    "sq": "sq",
    "crossing": "xing",
    "xing": "xing",
    "cove": "cv",
    "cv": "cv",
    "point": "pt",
    "pt": "pt",
    "bend": "bnd",
    "bnd": "bnd",
    "ridge": "rdg",
    "rdg": "rdg",
    "run": "run",
    "pass": "pass",
    "loop": "loop",
    "path": "path",
    "row": "row",
    "walk": "walk",
}
_DIRECTIONS = {
    "north": "n",
    "south": "s",
    "east": "e",
    "west": "w",
    "northeast": "ne",
    "northwest": "nw",
    "southeast": "se",
    "southwest": "sw",
    "n": "n",
    "s": "s",
    "e": "e",
    "w": "w",
    "ne": "ne",
    "nw": "nw",
    "se": "se",
    "sw": "sw",
}


def _ascii_lower(value: str) -> str:
    decomposed = unicodedata.normalize("NFKD", value)
    return "".join(c for c in decomposed if not unicodedata.combining(c)).lower()


def normalize_house_number(value: str) -> str:
    """'  12-B ' -> '12b'. Keeps letters and digits only."""
    cleaned = re.sub(r"[^a-z0-9]", "", _ascii_lower(value))
    if not cleaned:
        raise ValueError("House number is empty after normalization")
    return cleaned.lstrip("0") or "0"


def normalize_street_name(value: str) -> str:
    """'North Maple   Street.' -> 'n maple st'."""
    text = re.sub(r"[^a-z0-9 ]", " ", _ascii_lower(value))
    words = text.split()
    if not words:
        raise ValueError("Street name is empty after normalization")
    out: list[str] = []
    last = len(words) - 1
    for index, word in enumerate(words):
        if word in _DIRECTIONS and (index == 0 or index == last) and len(words) > 1:
            out.append(_DIRECTIONS[word])
        elif (
            index == last
            and word in _SUFFIXES
            and len(words) > 1
            or index == last - 1
            and words[last] in _DIRECTIONS
            and word in _SUFFIXES
            and len(words) > 2
        ):
            out.append(_SUFFIXES[word])
        else:
            out.append(word)
    return " ".join(out)
