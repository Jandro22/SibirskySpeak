import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from scripts.review_phone_episode import (
    UiNode,
    analyze_telemetry,
    classify_state,
    normalized,
    parse_nodes,
    solve_tiles,
)


def node(text: str, tag: str, *, enabled: bool = True) -> UiNode:
    return UiNode(text, f"com.sibirskyspeak.qa:id/{tag}", "", "android.view.View", True, enabled, (0, 0, 100, 100))


class ReviewPhoneEpisodeTest(unittest.TestCase):
    def test_parse_nodes_exposes_compose_resource_tags_and_bounds(self):
        xml = """<?xml version='1.0'?><hierarchy><node text='Continue' resource-id='com.sibirskyspeak.qa:id/tutor_next' content-desc='' clickable='true' enabled='true' bounds='[10,20][210,120]'/></hierarchy>"""

        nodes = parse_nodes(xml)

        self.assertEqual(1, len(nodes))
        self.assertEqual("tutor_next", nodes[0].tag)
        self.assertEqual((110, 70), nodes[0].center)
        self.assertTrue(nodes[0].clickable)

    def test_solve_tiles_orders_words_and_ignores_decoys(self):
        tiles = [node("дом", "answer_tile_0"), node("это", "answer_tile_1"), node("книга", "answer_tile_2")]

        result = solve_tiles("Это дом.", tiles)

        self.assertEqual(["это", "дом"], [value.text for value in result])

    def test_solve_tiles_assembles_compact_word_chunks(self):
        tiles = [node("го", "answer_tile_0"), node("во", "answer_tile_1"), node("рю", "answer_tile_2"), node("x", "answer_tile_3")]

        result = solve_tiles("говорю", tiles)

        self.assertEqual(["го", "во", "рю"], [value.text for value in result])

    def test_normalization_ignores_case_stress_and_punctuation(self):
        self.assertEqual(normalized("Э́то дом."), normalized("это дом"))

    def test_classify_state_uses_episode_task_and_feedback(self):
        snapshot = {"taskIndex": 1, "checked": True}
        task = {"kind": "GUIDED_RESPONSE"}

        label = classify_state([], snapshot, task)

        self.assertEqual("step 2 guided response feedback", label)

    def test_classify_state_distinguishes_bootstrap_spinner_from_progress(self):
        spinner = UiNode("", "", "", "android.widget.ProgressBar", False, True, (0, 0, 100, 100))
        overview_progress = [spinner, node("", "tutor_continue")]

        self.assertEqual("loading", classify_state([spinner], None, None))
        self.assertEqual("episode overview", classify_state(overview_progress, None, None))

    def test_analyze_telemetry_tolerates_invalid_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.db"
            connection = sqlite3.connect(path)
            try:
                connection.execute(
                    "CREATE TABLE telemetry_events (eventType TEXT, sessionId TEXT, timestamp INTEGER, metadataJson TEXT)"
                )
                connection.executemany(
                    "INSERT INTO telemetry_events VALUES (?, ?, ?, ?)",
                    [
                        ("episode_started", "episode-1", 1, json.dumps({"mode": "ACQUIRE"})),
                        ("episode_completed", "episode-1", 2, "not-json"),
                    ],
                )
                connection.commit()
            finally:
                connection.close()

            result = analyze_telemetry(path)

        self.assertTrue(result["available"])
        self.assertEqual(1.0, result["episodeCompletionRate"])
        self.assertEqual("not-json", result["recentEpisodeEvents"][0]["metadata"]["_invalidJson"])

    def test_analyze_telemetry_reports_uninitialized_database_cleanly(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.db"
            path.write_text("cat: file not found", encoding="utf-8")

            result = analyze_telemetry(path)

        self.assertFalse(result["available"])
        self.assertIn("initialized telemetry database", result["error"])


if __name__ == "__main__":
    unittest.main()
