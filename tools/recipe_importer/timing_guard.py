"""Conservative timing contradictions for the offline catalog projection.

A publisher's total is unknown when a required method duration already exceeds
it. This does not estimate a replacement total, add sequential durations, or use
optional tips, storage life, conditional instructions or alternative routes.
"""
from __future__ import annotations

import re
import unicodedata
from fractions import Fraction
from collections.abc import Mapping
from typing import Any

_WORD_NUMBERS = {
    "μία": 1, "μια": 1, "ένα": 1, "έναν": 1, "δύο": 2, "δυο": 2,
    "τρεις": 3, "τρείς": 3, "τέσσερις": 4, "τέσσερεις": 4, "πέντε": 5,
    "έξι": 6, "επτά": 7, "εφτά": 7, "οκτώ": 8, "οχτώ": 8, "εννέα": 9,
    "δέκα": 10, "δώδεκα": 12, "μισή": .5, "μισό": .5,
    "μιάμιση": 1.5, "μιαμιση": 1.5, "ενάμιση": 1.5,
}
_FOLDED_WORD_NUMBERS = {key.casefold(): value for key, value in _WORD_NUMBERS.items()}
_NUMBER = r"(?:\d+\s+\d+/\d+|\d+/\d+|\d+(?:[.,]\d+)?(?:\s*[¼½¾])?|[¼½¾]|" + "|".join(_WORD_NUMBERS) + ")"
_DURATION = re.compile(
    r"(?<![\w.,/⁄])(?P<low>" + _NUMBER + r")\s*"
    r"(?:(?:[-–−]|έως(?:\s+και)?|μέχρι|με)\s*(?P<high>" + _NUMBER + r")\s*)?"
    r"(?P<unit>ώρες|ώρα|ωρες|ωρα|λεπτά|λεπτα|λεπτό|λεπτο|ημέρες|ημέρα|μέρες|μέρα|[′’'])"
    r"(?!\w)", re.I,
)
_ACTION = re.compile(
    r"\b(?:αφήνουμε|αφηνουμε|βάζουμε|βαζουμε|τοποθετούμε|τοποθετουμε|"
    r"αποθηκεύουμε|μαρινάρουμε|μουλιάζουμε|ξεκουράζουμε|περιμένουμε|παγώνουμε|"
    r"ψήνουμε|ψηνουμε|βράζουμε|βραζουμε|σιγοβράζουμε|μαγειρεύουμε|σιγομαγειρεύουμε|"
    r"μεταφέρουμε|μεταφερουμε|σκεπάζουμε|σκεπαζουμε|διατηρούμε|χτυπάμε|αφήστε)\b", re.I,
)
_OPTIONAL_SENTENCE = re.compile(
    r"\b(?:αν|εάν|εαν|εφόσον|μπορούμε|μπορουμε|μπορείτε|μπορειτε|μπορείς|μπορεις|"
    r"προαιρετικ\w*|εναλλακτικ\w*|ιδανικά|ιδανικα|συντηρούμε|συντηρείται|"
    r"περισσέψει|περισσεψει)\b", re.I,
)
_ALTERNATIVE_STEP = re.compile(
    r"\b(?:είτε|ειτε|αλλιώς|αλλιως|εναλλακτικ\w*|προαιρετικ\w*|ιδανικά|ιδανικα)\b", re.I,
)
# The publisher's lid-versus-film choice changes only the covering, not
# whether the following refrigeration is required. Keep other alternatives
# conservative; this is the exact observed covering construction.
_COVERING_CHOICE = re.compile(
    r"\bκαπάκι\s+αλλιώς\s+με\s+(?:(?:λίγο|λίγη|μια|μία)\s+)?μεμβράνη\b", re.I,
)
_STORAGE = re.compile(
    r"\b(?:μελλοντικ\w*|διατηρείται|διατηρουνται|διατηρούνται|συντηρ\w*|περισσε\w*|"
    r"υπόλοιπ\w*|υπολοιπ\w*)\b|μέχρι\s+να\s+(?:χρειασ|χρησιμοποι)", re.I,
)
_IMMEDIATE_ALTERNATIVE = re.compile(
    r"αμέσως\s+ή|ή\s+(?:(?:το|τα|την|τον|τη|τις|τους)\s+)?"
    r"(?:σερβίρ\w*|τρ[ωώ]γ\w*|καταναλ\w*|απολα[υύ]\w*)", re.I,
)
_OVERNIGHT = re.compile(
    r"\b(?:όλο\s+το\s+βράδυ|ολο\s+το\s+βραδυ|όλη\s+(?:τη|την)\s+νύχτα|"
    r"ολη\s+(?:τη|την)\s+νυχτα|μέχρι\s+(?:την\s+)?επόμενη\s+(?:μέρα|ημέρα))\b", re.I,
)


def _number(value: str) -> float:
    value = value.strip().casefold()
    if value in _FOLDED_WORD_NUMBERS:
        return _FOLDED_WORD_NUMBERS[value]
    if "/" in value:
        return sum(float(Fraction(part)) for part in value.split())
    fraction = {"¼": .25, "½": .5, "¾": .75}
    if value[-1:] in fraction:
        return float(value[:-1].strip().replace(",", ".") or 0) + fraction[value[-1]]
    return float(value.replace(",", "."))


def _duration_bounds(sentence: str) -> list[float]:
    matches = list(_DURATION.finditer(sentence))
    # Explicit faster alternatives cannot establish the slower route as required.
    if len(matches) > 1 and re.search(r"\bή\b", sentence, re.I):
        return []
    bounds: list[float] = []
    previous = None
    for match in matches:
        try:
            amount = min(_number(match.group("low")), _number(match.group("high"))) if match.group("high") else _number(match.group("low"))
        except (ValueError, ZeroDivisionError):
            continue
        unit = match.group("unit").casefold()
        factor = 60 if unit.startswith(("ώρ", "ωρ")) else 1440 if "μέρ" in unit else 1
        minutes = amount * factor
        if previous is not None and re.fullmatch(r"\s*(?:έως|μέχρι|με)\s*(?:και\s*)?", sentence[previous.end():match.start()], re.I):
            bounds[-1] = min(bounds[-1], minutes)
        else:
            bounds.append(minutes)
        previous = match
    return bounds


def timing_contradictions(record: Mapping[str, Any]) -> list[dict[str, Any]]:
    """Return exact mandatory-method evidence contradicting a positive total."""
    total = record.get("totalMinutes", 0)
    if not isinstance(total, (int, float)) or isinstance(total, bool) or total <= 0:
        return []
    evidence = []
    for section_index, section in enumerate(record.get("methodSections", [])):
        for step_index, step in enumerate(section.get("steps", [])):
            if not isinstance(step, str):
                continue
            # Match NFC locally: source method strings and audit evidence retain
            # their original composed/decomposed Unicode representation.
            route_text = _COVERING_CHOICE.sub("καπάκι μεμβράνη", unicodedata.normalize("NFC", step))
            if _ALTERNATIVE_STEP.search(route_text) or _IMMEDIATE_ALTERNATIVE.search(route_text):
                continue
            for sentence in re.split(r"(?<=[.!?;])\s+|\n+", step):
                sentence = sentence.strip()
                # A storage-life aside does not cancel an explicit required
                # chilling step outside the parentheses.
                instruction = unicodedata.normalize("NFC", sentence)
                instruction = re.sub(r"\([^()]*\)", lambda match: "" if _STORAGE.search(match.group()) else match.group(), instruction)
                if (not _ACTION.search(instruction)
                        or _OPTIONAL_SENTENCE.search(instruction) or _STORAGE.search(instruction)):
                    continue
                # "Keep refrigerated for N days" can describe storage life;
                # explicit before-serving or freezing purpose proves a wait.
                if re.search(r"\bδιατηρούμε\b", instruction, re.I) and not re.search(
                        r"\b(?:πριν|προτού)\b|να\s+(?:παγώ|σφίξ|δέσ|μαριναρ)", instruction, re.I):
                    continue
                bounds = [minutes for minutes in _duration_bounds(instruction) if minutes > total]
                overnight = total < 30 and bool(_OVERNIGHT.search(instruction))
                if bounds or overnight:
                    evidence.append({
                        "sectionIndex": section_index, "stepIndex": step_index,
                        "sentence": sentence, "reportedTotalMinutes": total,
                        "requiredDurationLowerBoundMinutes": max(bounds) if bounds else None,
                        "requiredOvernightWait": overnight,
                    })
    return evidence


def normalize_catalog_timing(record: Mapping[str, Any]) -> dict[str, Any]:
    """Return a copy; preserve every source field and known component duration."""
    result = dict(record)
    if timing_contradictions(record):
        result["totalMinutes"] = 0
        result["quickRecipe"] = False
    return result
