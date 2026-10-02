import pytest

from reading_robot.aligner import HypWord, StreamingAligner
from reading_robot.config import ReadingConfig
from reading_robot.passages import load_passages
from reading_robot.scoring import LiveTracker, build_report
from reading_robot.simulate import run_simulation
from reading_robot.text import build_passage, normalise

TEXT = "Milo the cat had a big problem. His favourite red sock was gone!"


def hyp(words, start=0.0, step=0.4, gaps=None, acc=None):
    out, t = [], start
    for k, w in enumerate(words):
        t += (gaps or {}).get(k, 0.0)
        out.append(HypWord(normalise(w), t, t + step * 0.8, accuracy=(acc or {}).get(k)))
        t += step
    return out


def align(words, **kw):
    p = build_passage("t", "T", TEXT, "B")
    a = StreamingAligner(p, ReadingConfig())
    return p, a, a.update(hyp(words, **kw))


def statuses(st):
    return [w.status for w in st.words]


def test_word_count_is_computed_not_typed():
    p = load_passages()["mission-7"]
    assert p.word_count == 51          # mockup label says 58 - it is wrong
    assert len(p.sentences) == 7
    assert p.words[28].display == "fridge,"


def test_perfect_read():
    p, _, st = align(TEXT.split())
    assert all(s == "correct" for s in statuses(st))
    assert st.cursor == p.word_count - 1


def test_partial_read_keeps_rest_pending_not_omitted():
    _, _, st = align("Milo the cat had".split())
    assert st.cursor == 3
    assert statuses(st)[4:] == ["pending"] * (len(st.words) - 4)


def test_omission_substitution_insertion():
    _, _, st = align("Milo the cat a big big problem His favourite blue sock was gone".split())
    s = statuses(st)
    assert s[3] == "omission"            # had
    assert s[9] == "substitution"        # red -> blue
    assert st.words[9].said == "blue"
    assert st.words[5].repeated          # "big big" = repetition, not an error
    assert not st.insertions


def test_true_insertion_is_an_error():
    _, _, st = align("Milo the black cat had a big problem".split())
    assert [i.said for i in st.insertions] == ["black"]


def test_sounding_out_and_self_correction_are_not_errors():
    _, _, st = align("Milo the cat had a big problem His fav favourite red sack sock".split())
    assert st.words[8].status == "correct" and st.words[8].sounded_out
    assert st.words[10].status == "correct" and st.words[10].self_corrected
    assert not st.insertions


def test_spelling_variant_and_homophone():
    _, _, st = align("Milo the cat had a big problem His favorite read sock was gone".split())
    assert st.words[8].status == "correct"   # favorite == favourite
    assert st.words[9].status == "correct"   # read ~ red (ASR homophone)


def test_stray_interim_word_does_not_jump_cursor():
    _, _, st = align("Milo the cat had xyzzy".split())
    assert st.cursor == 3


def test_hesitation_detected():
    _, _, st = align("Milo the cat had a big problem".split(), gaps={6: 3.5})
    assert st.words[6].hesitation and st.words[6].hesitation >= 3.0


def test_told_word_counts_as_error_and_advances_cursor():
    p = build_passage("t", "T", TEXT, "B")
    a = StreamingAligner(p)
    a.update(hyp("Milo the cat had a big".split()))
    a.mark_told(6)
    st = a.update(hyp("Milo the cat had a big help".split()))
    assert st.words[6].status == "told"
    assert st.cursor == 6
    assert not st.insertions             # "help" is a command, not reading


def test_interim_revision_is_safe():
    p = build_passage("t", "T", TEXT, "B")
    a = StreamingAligner(p)
    a.update(hyp("Milo the cap".split()))      # interim mis-hearing
    st = a.update(hyp("Milo the cat had".split()))
    assert statuses(st)[:4] == ["correct"] * 4


def test_low_pronunciation_flag_not_error_by_default():
    _, _, st = align("Milo the cat had".split(), acc={2: 40})
    assert st.words[2].status == "correct" and st.words[2].low_pronunciation


def test_sentence_stars():
    p = build_passage("t", "T", TEXT, "B")
    tr = LiveTracker(p)
    ev = tr.update(hyp("Milo the cat had a big problem His".split()))
    assert ev["new_sentences"] == [{"sentence": 0, "errors": 0, "stars": 2}]
    ev = tr.update(hyp("Milo the cat had a big problem His".split()))
    assert ev["new_sentences"] == []     # awarded once


def test_cathy_simulation_report_matches_running_record_maths():
    _, r = run_simulation()
    assert r["total_words"] == 51
    assert r["errors"] == 3                       # very (omit), squeak (sub), snoring (told)
    assert r["accuracy_pct"] == pytest.approx(94.1)
    assert r["accuracy_band"] == "instructional"
    assert r["self_corrections"] == 1
    assert r["error_rate"] == "1:17"
    types = {(m["word"], m["type"]) for m in r["miscues"]}
    assert ("squeak", "substitution") in types and ("very", "omission") in types
    assert ("snoring", "told") in types and ("mouse", "self_correction") in types
    assert ("favourite", "sounded_out") in types and ("even", "pronunciation") in types
    assert r["wcpm"] and 50 < r["wcpm"] < 110


def test_report_on_early_stop():
    p = load_passages()["mission-7"]
    tr = LiveTracker(p)
    tr.update(hyp(p.text.split()[:20]))
    r = build_report(tr, student={"id": "x", "name": "X"}, finished_reason="stalled")
    assert r.words_attempted == 20 and r.completion_pct < 50 and r.accuracy_pct == 100.0
