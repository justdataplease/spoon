"""Observed publisher timing contradictions and deliberately safe negatives."""
from copy import deepcopy
import pytest
from tools.recipe_importer.timing_guard import normalize_catalog_timing, timing_contradictions
from tools.recipe_importer.facet_taxonomy import normalize_catalog_facets


def record(step, total=20, **extra):
    return {"totalMinutes": total, "quickRecipe": 0 < total < 30,
            "prepMinutes": 5, "cookMinutes": 15, "waitMinutes": 0,
            "methodSections": [{"title": "", "steps": [step]}],
            "sourcePayload": {"reportedTotalMinutes": total}, **extra}


@pytest.mark.parametrize(("step", "total", "bound"), [
    ("Τότε, σκεπάζουμε και το αποθηκεύουμε στο ψυγείο για 8 ώρες.", 20, 480),
    ("Αφήνουμε στο ψυγείο για τουλάχιστον 1 ώρα.", 15, 60),
    ("Αφήνουμε το κουρκούτι να ξεκουραστεί για 30 λεπτά έως και 8 ώρες.", 15, 30),
    ("Σκεπάζουμε τη γάστρα και ψήνουμε για 2.5-3 ώρες.", 18, 150),
    ("Αφήνουμε 1-2 ώρες να κρυώσει, κόβουμε και σερβίρουμε.", 20, 60),
    ("Σιγοβράζουμε σε χαμηλή φωτιά τη σάλτσα για 45 λεπτά, να δέσει.", 10, 45),
    ("Βάζουμε το μείγμα στο ψυγείο για μισή ώρα.", 20, 30),
    ("Βάζουμε το μείγμα στο ψυγείο για 1/2 ώρα.", 20, 30),
    ("Βάζουμε το μείγμα στο ψυγείο για 1 1/2 ώρα.", 20, 90),
    ("Βάζουμε το μείγμα στο ψυγείο για 1–1½ ώρα.", 20, 60),
    ("Μαρινάρουμε για 4 ώρες στο ψυγείο και τα αφαιρούμε από τη μαρινάδα.", 15, 240),
    ("Διατηρούμε στο ψυγείο για 1 ώρα τουλάχιστον πριν τις σερβίρουμε.", 15, 60),
    ("Τη διατηρούμε στο ψυγείο για 4-6 ώρες ή μέχρι να παγώσει καλά.", 15, 240),
    ("Τα παγώνουμε για 1 ώρα στο ψυγείο (διατηρείται στο ψυγείο).", 15, 60),
    ("Βάζουμε το μείγμα στην κατάψυξη. Ύστερα από 1 ώρα το χτυπάμε ξανά.", 10, 60),
    ("Σκεπάζουμε το προζύμι και αφήνουμε για 24 ώρες.", 15, 1440),
    ("Αφήνουμε για 2 μέρες και συνεχίζουμε.", 60, 2880),
    ("Αφήνουμε για τρεις ώρες και συνεχίζουμε.", 20, 180),
    ("-Αφήστε τα μακαρόν, περίπου 1 ώρα ώστε να πιάσουν κρούστα στην επιφάνεια!", 12, 60),
    ("Αφήνουμε για µισό λεπτό.", 0.25, 0.5),
])
def test_required_single_duration_exceeds_published_total(step, total, bound):
    raw = record(step, total)
    before = deepcopy(raw)
    evidence = timing_contradictions(raw)
    assert evidence[0]["requiredDurationLowerBoundMinutes"] == bound
    projected = normalize_catalog_facets(raw)
    assert (projected["totalMinutes"], projected["quickRecipe"]) == (0, False)
    assert projected["sourcePayload"] == raw["sourcePayload"]
    assert [projected[k] for k in ("prepMinutes", "cookMinutes", "waitMinutes")] == [5, 15, 0]
    assert raw == before
    assert normalize_catalog_facets(projected) == projected


@pytest.mark.parametrize("step", [
    "Αν δεν θέλουμε να χρησιμοποιήσουμε τη σαντιγί αμέσως, μπορούμε να τη διατηρήσουμε στο ψυγείο για 3 -4 ώρες.",
    "Αν υπάρχει χρόνος αφήνουμε για μισή ώρα, αλλιώς συνεχίζουμε.",
    "Σερβίρουμε είτε ζεστό όπως είναι, είτε το βάζουμε στο ψυγείο για μία ώρα.",
    "Απολαμβάνουμε! Εναλλακτικά μπορείτε να βάλετε για μισή ώρα στο ψυγείο!",
    "Ιδανικά αφήνουμε να μαριναριστεί για 4 ώρες.",
    "Προαιρετικά, αφήνουμε για 1 ώρα περίπου.",
    "Βάζουμε την ζύμη για 1 ώρα στο ψυγείο ή 10′ στην κατάψυξη.",
    "Σερβίρουμε αμέσως ή αφήνουμε τη σαλάτα στο ψυγείο για 20-30 λεπτά.",
    "Βάζουμε στο ψυγείο και αφήνουμε να κρυώσει για 1 ώρα ή σερβίρουμε αμέσως με παγάκια.",
    "Διατηρείται στο ψυγείο σε καλά κλεισμένο δοχείο για 2-3 ημέρες.",
    "Διατηρούμε στο ψυγείο για 4 ημέρες.",
    "Παίρνουμε 4 ώριμες μπανάνες και τις βάζουμε στο μπολ.",
    "Αφήνουμε για 1/4 ώρα και σερβίρουμε.",
    "Βάζουμε στο ψυγείο για 10 λεπτά έως και 8 ώρες.",
    "Βάζουμε στο ψυγείο για 20 λεπτά και σερβίρουμε.",
    "Αφήνουμε για 15 λεπτά (διατηρείται στο ψυγείο για 4 ημέρες).",
    "Αφήνουμε για 1/0 ώρα.",
    "Μετά από 2 ημέρες στο ψυγείο η γεύση μαλακώνει.",
    "Αφήνουμε για 30 λεπτά (μπορούμε να χρησιμοποιήσουμε το υγρό απευθείας αν το επιθυμούμε).",
])
def test_optional_storage_alternative_non_duration_and_noncontradictory_steps(step):
    raw = record(step)
    assert timing_contradictions(raw) == []
    assert normalize_catalog_timing(raw) == raw


def test_later_optional_storage_does_not_cancel_required_rest():
    raw = record("Αφήνουμε τη ζύμη στο ψυγείο να ξεκουραστεί για τουλάχιστον 1 ώρα. "
                 "Μπορούμε να διατηρήσουμε τη ζύμη στο ψυγείο για μελλοντική χρήση.", 15)
    assert normalize_catalog_timing(raw)["totalMinutes"] == 0


def test_explicit_overnight_step_excludes_quick_without_inventing_a_total():
    raw = record("Σκεπάζουμε το μπολ και το βάζουμε στο ψυγείο για όλο το βράδυ.", 5)
    evidence = timing_contradictions(raw)
    assert evidence[0]["requiredDurationLowerBoundMinutes"] is None
    assert evidence[0]["requiredOvernightWait"] is True
    assert normalize_catalog_timing(raw)["totalMinutes"] == 0


def test_tips_and_unknown_totals_do_not_create_new_timing():
    raw = record("Ανακατεύουμε και σερβίρουμε.", tips=["Αφήνουμε στο ψυγείο για 8 ώρες."])
    assert normalize_catalog_timing(raw) == raw
    unknown = record("Αφήνουμε στο ψυγείο για 8 ώρες.", 0)
    assert normalize_catalog_timing(unknown) == unknown


def test_decomposed_greek_action_and_duration_match_without_rewriting_evidence():
    import unicodedata
    text = unicodedata.normalize("NFD", "Το αφήνουμε στο ψυγείο για τουλάχιστον 1 ώρα.")
    raw = record(text, 20)
    before = deepcopy(raw)
    evidence = timing_contradictions(raw)
    assert evidence[0]["requiredDurationLowerBoundMinutes"] == 60
    assert evidence[0]["sentence"] == text
    assert normalize_catalog_timing(raw)["totalMinutes"] == 0
    assert normalize_catalog_timing(raw)["methodSections"] == before["methodSections"]
    assert raw == before


def test_lid_or_film_choice_does_not_make_overnight_refrigeration_optional():
    text = "Σκεπάζουμε με ένα καπάκι αλλιώς με λίγο μεμβράνη και το βάζουμε στο ψυγείο για όλο το βράδυ"
    raw = record(text, 5)
    evidence = timing_contradictions(raw)
    assert evidence[0]["requiredOvernightWait"] is True
    assert evidence[0]["sentence"] == text
    assert normalize_catalog_timing(raw)["totalMinutes"] == 0
    # A separate immediate-serving route still makes the wait optional.
    alternative = record(text + " ή σερβίρουμε αμέσως.", 5)
    assert normalize_catalog_timing(alternative) == alternative


def test_unsupported_unicode_fraction_cannot_match_only_its_denominator():
    # Publisher compatibility text can render "1½" as "11⁄2"; its meaning
    # cannot safely be inferred as either 11/2 or the final denominator 2.
    raw = record("Αφήνουμε για 11⁄2 ώρες.", 90)
    assert timing_contradictions(raw) == []
    assert normalize_catalog_timing(raw) == raw
    mixed = record("Βάζουμε στο ψυγείο για 1-11⁄2 ώρα. Αφήνουμε για τουλάχιστον 1 ώρα.", 20)
    evidence = timing_contradictions(mixed)
    assert [item["requiredDurationLowerBoundMinutes"] for item in evidence] == [60]
