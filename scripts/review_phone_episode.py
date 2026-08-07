#!/usr/bin/env python3
"""Capture and drive a real-phone SibirskySpeak episode for UI/learning QA.

The PowerShell wrapper installs the isolated QA package and protects device
settings. This script owns the deterministic evidence pass: it waits for an
unlocked phone, launches the app, walks controls exposed through Compose test
tags, captures each stable framebuffer and semantics tree, exports telemetry,
and writes a self-contained HTML review report.
"""
from __future__ import annotations

import argparse
import html
import json
import re
import sqlite3
import struct
import subprocess
import sys
import time
import unicodedata
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable


BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
WORD = re.compile(r"[^\W_]+(?:[-'][^\W_]+)*", re.UNICODE)


@dataclass(frozen=True)
class UiNode:
    text: str
    resource_id: str
    content_desc: str
    class_name: str
    clickable: bool
    enabled: bool
    bounds: tuple[int, int, int, int]

    @property
    def tag(self) -> str:
        return self.resource_id.rsplit("/", 1)[-1]

    @property
    def center(self) -> tuple[int, int]:
        x1, y1, x2, y2 = self.bounds
        return ((x1 + x2) // 2, (y1 + y2) // 2)


class PhoneReview:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.output = Path(args.output).resolve()
        self.output.mkdir(parents=True, exist_ok=True)
        self.stages: list[dict] = []
        self.issues: list[dict] = []
        self.stage_number = 0
        self.last_signature = ""
        self.started_episode = False
        self.played_listening: set[str] = set()
        self.density = self._density()
        self.display_size = self._display_size()

    def adb(self, values: Iterable[str], *, check: bool = True, text: bool = True) -> subprocess.CompletedProcess:
        command = [self.args.adb, "-s", self.args.serial, *map(str, values)]
        if text:
            return subprocess.run(
                command,
                check=check,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
            )
        return subprocess.run(command, check=check, capture_output=True, text=False)

    def _density(self) -> float:
        value = self.adb(["shell", "wm", "density"]).stdout
        match = re.search(r"Physical density:\s*(\d+)", value)
        return (int(match.group(1)) / 160.0) if match else 1.0

    def _display_size(self) -> tuple[int, int]:
        value = self.adb(["shell", "wm", "size"]).stdout
        match = re.search(r"Physical size:\s*(\d+)x(\d+)", value)
        return (int(match.group(1)), int(match.group(2))) if match else (0, 0)

    def wait_for_unlock(self) -> None:
        self.adb(["shell", "input", "keyevent", "KEYCODE_WAKEUP"], check=False)
        deadline = time.monotonic() + self.args.unlock_timeout
        announced = False
        while time.monotonic() < deadline:
            policy = self.adb(["shell", "dumpsys", "window", "policy"], check=False).stdout
            trust = self.adb(["shell", "dumpsys", "trust"], check=False).stdout
            screen_on = "screenState=SCREEN_STATE_ON" in policy and "interactiveState=INTERACTIVE_STATE_AWAKE" in policy
            unlocked = not re.search(r"deviceLocked=1", trust)
            if screen_on and unlocked:
                return
            if not announced:
                print(f"Phone {self.args.serial} is locked. Unlock it; waiting up to {self.args.unlock_timeout}s...", flush=True)
                announced = True
            time.sleep(1)
        raise RuntimeError(
            "The phone stayed locked. Unlock it and rerun; the review harness never disables or bypasses a real keyguard."
        )

    def launch(self) -> None:
        self.adb(["shell", "am", "force-stop", self.args.package], check=False)
        self.adb(["shell", "monkey", "-p", self.args.package, "-c", "android.intent.category.LAUNCHER", "1"])
        deadline = time.monotonic() + self.args.render_timeout
        while time.monotonic() < deadline:
            xml = self.dump_ui(settle=False)
            nodes = parse_nodes(xml)
            if any(self.args.package in node.resource_id for node in nodes) or any(
                marker in xml for marker in ("SibirskySpeak", "Learn Russian by using it", "tutor_continue")
            ):
                return
            time.sleep(1)
        raise RuntimeError(f"{self.args.package} did not publish a usable semantics tree within {self.args.render_timeout}s")

    def dump_ui(self, *, settle: bool = True) -> str:
        remote = "/sdcard/sibirsky-phone-review.xml"
        prior = None
        attempts = 6 if settle else 1
        current = ""
        for _ in range(attempts):
            self.adb(["shell", "uiautomator", "dump", remote], check=False)
            current = self.adb(["exec-out", "cat", remote], check=False).stdout
            if settle and current and current == prior:
                break
            prior = current
            if settle:
                time.sleep(0.35)
        return current

    def snapshot(self) -> dict | None:
        result = self.adb(
            ["exec-out", "run-as", self.args.package, "cat", "shared_prefs/sibirsky_settings.xml"],
            check=False,
        )
        if result.returncode != 0 or not result.stdout.strip():
            return None
        try:
            root = ET.fromstring(result.stdout)
            value = next((item.text for item in root.findall("string") if item.get("name") == "episode_snapshot_v1"), None)
            return json.loads(value) if value else None
        except (ET.ParseError, json.JSONDecodeError):
            return None

    def current_task(self, snapshot: dict | None) -> dict | None:
        if not snapshot:
            return None
        tasks = snapshot.get("episode", {}).get("tasks", [])
        index = int(snapshot.get("taskIndex", 0))
        return tasks[index] if 0 <= index < len(tasks) else None

    def capture(self, label: str, xml: str, snapshot: dict | None) -> None:
        # A UI dump and framebuffer captured on opposite sides of a Compose
        # transition produce actively misleading evidence. Require the semantic
        # state (including the episode checkpoint) to be identical immediately
        # before and after the screenshot, retrying transient frames.
        candidate = self.output / ".capture-in-progress.png"
        stable = None
        for _ in range(5):
            before_xml = self.dump_ui()
            before_snapshot = self.snapshot()
            before_signature = state_signature(before_xml, before_snapshot)
            if before_signature == self.last_signature:
                return
            with candidate.open("wb") as stream:
                result = subprocess.run(
                    [self.args.adb, "-s", self.args.serial, "exec-out", "screencap", "-p"],
                    stdout=stream,
                    stderr=subprocess.PIPE,
                )
            after_xml = self.dump_ui(settle=False)
            after_snapshot = self.snapshot()
            after_signature = state_signature(after_xml, after_snapshot)
            if result.returncode == 0 and candidate.stat().st_size >= 1000 and before_signature == after_signature:
                stable = (after_xml, after_snapshot, after_signature)
                break
            candidate.unlink(missing_ok=True)
            time.sleep(0.5)
        if stable is None:
            raise RuntimeError(f"No valid framebuffer capture for {label}")
        xml, snapshot, signature = stable
        nodes = parse_nodes(xml)
        label = classify_state(nodes, snapshot, self.current_task(snapshot))
        self.last_signature = signature
        slug = re.sub(r"[^a-z0-9]+", "-", label.lower()).strip("-") or "state"
        stem = f"{self.stage_number:03d}-{slug}"
        self.stage_number += 1
        xml_path = self.output / f"{stem}.xml"
        png_path = self.output / f"{stem}.png"
        snapshot_path = self.output / f"{stem}.snapshot.json"
        candidate.replace(png_path)
        xml_path.write_text(xml, encoding="utf-8")
        if snapshot is not None:
            snapshot_path.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2), encoding="utf-8")
        stage_issues = self.audit_ui(nodes, png_path, snapshot)
        self.issues.extend({"stage": stem, **issue} for issue in stage_issues)
        task = self.current_task(snapshot)
        self.stages.append(
            {
                "stem": stem,
                "label": label,
                "screenshot": png_path.name,
                "ui": xml_path.name,
                "snapshot": snapshot_path.name if snapshot is not None else None,
                "taskIndex": snapshot.get("taskIndex") if snapshot else None,
                "taskKind": task.get("kind") if task else None,
                "instruction": task.get("instruction") if task else None,
                "visibleText": visible_text(nodes),
                "issues": stage_issues,
            }
        )
        print(f"Captured {stem}", flush=True)

    def audit_ui(self, nodes: list[UiNode], screenshot: Path, snapshot: dict | None) -> list[dict]:
        issues: list[dict] = []
        width, height = self.display_size
        if screenshot.stat().st_size < 5000:
            issues.append({"severity": "error", "code": "tiny_screenshot", "message": "Framebuffer is probably black or incomplete."})
        ids = [node.resource_id for node in nodes if node.resource_id]
        duplicates = sorted({value for value in ids if ids.count(value) > 1})
        if duplicates:
            issues.append({"severity": "warning", "code": "duplicate_ids", "message": f"Duplicate automation ids: {', '.join(duplicates[:5])}"})
        for node in nodes:
            x1, y1, x2, y2 = node.bounds
            if width and height and (x1 < 0 or y1 < 0 or x2 > width or y2 > height):
                issues.append({"severity": "error", "code": "offscreen", "message": f"{node.tag or node.text!r} extends beyond the display."})
            if node.clickable and node.resource_id:
                width_dp = (x2 - x1) / self.density
                height_dp = (y2 - y1) / self.density
                if width_dp < 48 or height_dp < 48:
                    issues.append(
                        {
                            "severity": "warning",
                            "code": "small_target",
                            "message": f"{node.tag} is {width_dp:.0f}×{height_dp:.0f}dp; target is 48×48dp.",
                        }
                    )
        if snapshot:
            action_tags = {node.tag for node in nodes if node.enabled}
            has_action = any(
                tag.startswith("tutor_choice_") or tag.startswith("answer_tile_") or tag in {
                    "tutor_next", "tutor_check", "tutor_listen", "tutor_speech_fallback"
                }
                for tag in action_tags
            )
            if not has_action:
                issues.append({"severity": "error", "code": "no_action", "message": "Active episode state exposes no enabled learner action."})
        return issues

    def tap(self, node: UiNode) -> None:
        x, y = node.center
        self.adb(["shell", "input", "tap", str(x), str(y)])
        time.sleep(0.55)

    def find(self, nodes: list[UiNode], tag: str, *, enabled: bool | None = None) -> UiNode | None:
        values = [node for node in nodes if node.tag == tag and (enabled is None or node.enabled == enabled)]
        return values[0] if values else None

    def drive(self) -> None:
        for _ in range(self.args.max_actions):
            self.wait_for_unlock()
            xml = self.dump_ui()
            nodes = parse_nodes(xml)
            snapshot = self.snapshot()
            task = self.current_task(snapshot)
            label = classify_state(nodes, snapshot, task)
            self.capture(label, xml, snapshot)
            texts = {node.text for node in nodes if node.text}
            if "Episode complete" in texts:
                return
            onboarding = self.find(nodes, "tutor_onboarding_start", enabled=True)
            if onboarding:
                self.tap(onboarding)
                continue
            start = self.find(nodes, "tutor_continue", enabled=True)
            if start:
                if self.started_episode:
                    return
                self.started_episode = True
                self.tap(start)
                continue
            fallback = self.find(nodes, "tutor_speech_fallback", enabled=True)
            if fallback and snapshot and not snapshot.get("speechFallback"):
                self.tap(fallback)
                continue
            next_button = self.find(nodes, "tutor_next", enabled=True)
            if next_button:
                self.tap(next_button)
                continue
            task_key = f"{snapshot.get('episode', {}).get('id')}:{snapshot.get('taskIndex')}" if snapshot else ""
            listen = self.find(nodes, "tutor_listen", enabled=True)
            if listen and task_key not in self.played_listening:
                self.played_listening.add(task_key)
                self.tap(listen)
                continue
            check = self.find(nodes, "tutor_check")
            choices = sorted(
                (node for node in nodes if node.tag.startswith("tutor_choice_") and node.enabled),
                key=lambda node: int(node.tag.rsplit("_", 1)[-1]),
            )
            if choices and check and not check.enabled:
                expected = str((task or {}).get("expected") or "")
                chosen = next((node for node in choices if normalized(node.text) == normalized(expected)), choices[0])
                self.tap(chosen)
                continue
            assembled = self.find(nodes, "answer_tile_assembled")
            all_tiles = sorted(
                (node for node in nodes if node.tag.startswith("answer_tile_") and node.tag.rsplit("_", 1)[-1].isdigit()),
                key=lambda node: int(node.tag.rsplit("_", 1)[-1]),
            )
            tiles = [node for node in all_tiles if node.enabled]
            if tiles and assembled and check and not check.enabled and len(tiles) == len(all_tiles):
                order = solve_tiles(str((task or {}).get("expected") or ""), all_tiles)
                if not order:
                    self.issues.append(
                        {"stage": self.stages[-1]["stem"], "severity": "error", "code": "tile_unsolved", "message": "Could not derive the authored response from exposed tile labels."}
                    )
                    order = tiles[:1]
                for tile in order:
                    self.tap(tile)
                continue
            enabled_check = self.find(nodes, "tutor_check", enabled=True)
            if enabled_check:
                self.tap(enabled_check)
                continue
            if is_loading(nodes):
                time.sleep(1)
                continue
            self.issues.append(
                {"stage": self.stages[-1]["stem"], "severity": "error", "code": "driver_stalled", "message": "No supported next action was found."}
            )
            return
        self.issues.append(
            {"stage": self.stages[-1]["stem"] if self.stages else "launch", "severity": "error", "code": "action_budget", "message": f"Driver exceeded {self.args.max_actions} actions."}
        )

    def export_private_file(self, remote: str, local: Path) -> bool:
        with local.open("wb") as stream:
            result = subprocess.run(
                [self.args.adb, "-s", self.args.serial, "exec-out", "run-as", self.args.package, "cat", remote],
                stdout=stream,
                stderr=subprocess.PIPE,
            )
        if result.returncode != 0 or local.stat().st_size == 0:
            local.unlink(missing_ok=True)
            return False
        return True

    def collect_diagnostics(self) -> dict:
        for name, command in {
            "meminfo.txt": ["shell", "dumpsys", "meminfo", self.args.package],
            "gfxinfo.txt": ["shell", "dumpsys", "gfxinfo", self.args.package, "framestats"],
            "activity.txt": ["shell", "dumpsys", "activity", "activities"],
            "window.txt": ["shell", "dumpsys", "window"],
        }.items():
            (self.output / name).write_text(self.adb(command, check=False).stdout, encoding="utf-8")
        pid = self.adb(["shell", "pidof", self.args.package], check=False).stdout.strip()
        log_args = ["logcat", "-d", "-v", "threadtime"] + (["--pid", pid] if pid else [])
        (self.output / "logcat.txt").write_text(self.adb(log_args, check=False).stdout, encoding="utf-8", errors="replace")
        db = self.output / "sibirsky_speak.db"
        for suffix in ("", "-wal", "-shm"):
            self.export_private_file(f"databases/sibirsky_speak.db{suffix}", self.output / f"sibirsky_speak.db{suffix}")
        return analyze_telemetry(db)

    def write_report(self, telemetry: dict) -> None:
        metadata = {
            "createdAt": datetime.now(timezone.utc).isoformat(),
            "serial": self.args.serial,
            "package": self.args.package,
            "model": self.adb(["shell", "getprop", "ro.product.model"], check=False).stdout.strip(),
            "sdk": self.adb(["shell", "getprop", "ro.build.version.sdk"], check=False).stdout.strip(),
            "display": {"width": self.display_size[0], "height": self.display_size[1], "densityScale": self.density},
            "stages": self.stages,
            "issues": self.issues,
            "telemetry": telemetry,
        }
        (self.output / "report.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
        cards = []
        for stage in self.stages:
            issues = "".join(
                f'<li class="{html.escape(issue["severity"])}">{html.escape(issue["message"])}</li>' for issue in stage["issues"]
            ) or "<li class=ok>No structural issue detected.</li>"
            cards.append(
                f"""<article><h2>{html.escape(stage['label'])}</h2>
                <p><b>{html.escape(str(stage.get('taskKind') or 'surface'))}</b> · {html.escape(str(stage.get('instruction') or ''))}</p>
                <a href="{stage['screenshot']}"><img loading="lazy" src="{stage['screenshot']}" alt="{html.escape(stage['label'])}"></a>
                <details><summary>Visible semantics</summary><pre>{html.escape(stage['visibleText'])}</pre></details><ul>{issues}</ul></article>"""
            )
        all_issues = "".join(
            f'<li class="{html.escape(issue["severity"])}"><b>{html.escape(issue["stage"])}</b>: {html.escape(issue["message"])}</li>'
            for issue in self.issues
        ) or "<li class=ok>No structural issues detected.</li>"
        telemetry_html = html.escape(json.dumps(telemetry, ensure_ascii=False, indent=2))
        document = f"""<!doctype html><meta charset="utf-8"><title>SibirskySpeak phone review</title>
        <style>body{{font:16px system-ui;margin:auto;max-width:1500px;padding:24px;background:#10151b;color:#e8eef5}}h1,h2{{margin:.3em 0}}.meta{{color:#a9b6c4}}.grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(330px,1fr));gap:20px}}article{{background:#18212b;border:1px solid #344353;border-radius:16px;padding:16px}}img{{width:100%;max-height:780px;object-fit:contain;background:#080b0f;border-radius:10px}}pre{{white-space:pre-wrap;overflow:auto}}li.error{{color:#ff9f9f}}li.warning{{color:#ffd38a}}li.ok{{color:#9de3af}}a{{color:#8ec7ff}}</style>
        <h1>SibirskySpeak live-phone episode review</h1><p class=meta>{html.escape(metadata['model'])} · API {html.escape(metadata['sdk'])} · {html.escape(metadata['package'])} · {len(self.stages)} captured states</p>
        <h2>Findings</h2><ul>{all_issues}</ul><h2>Telemetry</h2><pre>{telemetry_html}</pre><div class=grid>{''.join(cards)}</div>"""
        (self.output / "report.html").write_text(document, encoding="utf-8")


def parse_nodes(xml: str) -> list[UiNode]:
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return []
    result = []
    for value in root.iter("node"):
        match = BOUNDS.fullmatch(value.get("bounds", ""))
        bounds = tuple(map(int, match.groups())) if match else (0, 0, 0, 0)
        result.append(
            UiNode(
                text=value.get("text", ""),
                resource_id=value.get("resource-id", ""),
                content_desc=value.get("content-desc", ""),
                class_name=value.get("class", ""),
                clickable=value.get("clickable") == "true",
                enabled=value.get("enabled") != "false",
                bounds=bounds,  # type: ignore[arg-type]
            )
        )
    return result


def visible_text(nodes: list[UiNode]) -> str:
    values = []
    for node in nodes:
        value = node.text or node.content_desc
        if value and value not in values:
            values.append(value)
    return "\n".join(values)


def state_signature(xml: str, snapshot: dict | None) -> str:
    snapshot_state = None if not snapshot else {
        "episode": snapshot.get("episode", {}).get("id"),
        "task": snapshot.get("taskIndex"),
        "checked": snapshot.get("checked"),
        "correct": snapshot.get("correct"),
        "fallback": snapshot.get("speechFallback"),
    }
    nodes = parse_nodes(xml)
    semantic = [(node.text, node.resource_id, node.enabled) for node in nodes if node.text or node.resource_id]
    return json.dumps([snapshot_state, semantic], ensure_ascii=False, sort_keys=True)


def classify_state(nodes: list[UiNode], snapshot: dict | None, task: dict | None) -> str:
    texts = {node.text for node in nodes}
    tags = {node.tag for node in nodes}
    if "tutor_onboarding_start" in tags:
        return "onboarding"
    if "Episode complete" in texts:
        return "episode complete"
    if "tutor_continue" in tags:
        return "episode overview"
    if is_loading(nodes):
        return "loading"
    if snapshot and task:
        suffix = " feedback" if snapshot.get("checked") else ""
        if snapshot.get("speechFallback"):
            suffix += " supported fallback"
        return f"step {int(snapshot.get('taskIndex', 0)) + 1} {str(task.get('kind', 'task')).lower().replace('_', ' ')}{suffix}"
    return "app surface"


def is_loading(nodes: list[UiNode]) -> bool:
    tags = {node.tag for node in nodes}
    learner_actions = {
        "tutor_onboarding_start", "tutor_continue", "tutor_next", "tutor_check",
        "tutor_listen", "tutor_speech_fallback",
    }
    has_action = any(tag in learner_actions or tag.startswith(("tutor_choice_", "answer_tile_")) for tag in tags)
    return not has_action and any(node.class_name == "android.widget.ProgressBar" for node in nodes)


def normalized(value: str) -> str:
    value = unicodedata.normalize("NFD", value.casefold()).replace("\u0301", "").replace("\u0300", "")
    return " ".join(WORD.findall(unicodedata.normalize("NFC", value)))


def solve_tiles(expected: str, tiles: list[UiNode]) -> list[UiNode]:
    value = expected.strip()
    if not value:
        return []
    if not re.search(r"\s", value):
        value = re.split(r"[/;,]", value, maxsplit=1)[0]
    value = unicodedata.normalize("NFD", value).replace("\u0301", "").replace("\u0300", "")
    value = unicodedata.normalize("NFC", value).casefold()
    if re.search(r"\s", value):
        wanted = WORD.findall(value)
        remaining = list(tiles)
        result = []
        for word in wanted:
            match = next((node for node in remaining if node.text.casefold() == word.casefold()), None)
            if match is None:
                return []
            result.append(match)
            remaining.remove(match)
        return result
    target = "".join(value.split())
    labels = ["".join(node.text.casefold().split()) for node in tiles]

    def search(position: int, remaining: tuple[int, ...]) -> list[int] | None:
        if position == len(target):
            return []
        for index in remaining:
            label = labels[index]
            if label and target.startswith(label, position):
                tail = search(position + len(label), tuple(value for value in remaining if value != index))
                if tail is not None:
                    return [index, *tail]
        return None

    indices = search(0, tuple(range(len(tiles))))
    return [tiles[index] for index in indices] if indices is not None else []


def analyze_telemetry(path: Path) -> dict:
    if not path.exists():
        return {"available": False}
    try:
        with path.open("rb") as stream:
            header = stream.read(16)
        if header != b"SQLite format 3\x00":
            return {"available": False, "error": "No initialized telemetry database was available."}
    except OSError as error:
        return {"available": False, "error": str(error)}
    try:
        connection = sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True)
        try:
            counts = dict(connection.execute("SELECT eventType, COUNT(*) FROM telemetry_events GROUP BY eventType"))
            rows = list(
                connection.execute(
                    "SELECT eventType, sessionId, timestamp, metadataJson FROM telemetry_events "
                    "WHERE eventType LIKE 'episode_%' ORDER BY timestamp DESC LIMIT 40"
                )
            )
        finally:
            connection.close()
        recent = []
        for event_type, session_id, timestamp, raw_metadata in rows:
            try:
                metadata = json.loads(raw_metadata or "{}")
            except json.JSONDecodeError:
                metadata = {"_invalidJson": raw_metadata}
            recent.append(
                {"eventType": event_type, "sessionId": session_id, "timestamp": timestamp, "metadata": metadata}
            )
        started = counts.get("episode_started", 0)
        completed = counts.get("episode_completed", 0)
        return {
            "available": True,
            "counts": counts,
            "episodeCompletionRate": (completed / started) if started else None,
            "recentEpisodeEvents": recent,
        }
    except sqlite3.Error as error:
        return {"available": False, "error": str(error)}


def parser() -> argparse.ArgumentParser:
    value = argparse.ArgumentParser()
    value.add_argument("--adb", required=True)
    value.add_argument("--serial", required=True)
    value.add_argument("--package", default="com.sibirskyspeak.qa")
    value.add_argument("--output", required=True)
    value.add_argument("--unlock-timeout", type=int, default=120)
    value.add_argument("--render-timeout", type=int, default=60)
    value.add_argument("--max-actions", type=int, default=80)
    value.add_argument("--no-drive", action="store_true")
    return value


def main() -> int:
    args = parser().parse_args()
    review = PhoneReview(args)
    try:
        review.wait_for_unlock()
        review.launch()
        if args.no_drive:
            xml = review.dump_ui()
            snapshot = review.snapshot()
            review.capture(classify_state(parse_nodes(xml), snapshot, review.current_task(snapshot)), xml, snapshot)
        else:
            review.drive()
        telemetry = review.collect_diagnostics()
        review.write_report(telemetry)
        print(review.output)
        return 0 if not any(issue["severity"] == "error" for issue in review.issues) else 2
    except Exception as error:  # Preserve a useful report even for interrupted runs.
        review.issues.append(
            {"stage": review.stages[-1]["stem"] if review.stages else "launch", "severity": "error", "code": "harness_failure", "message": str(error)}
        )
        (review.output / "failure.txt").write_text(str(error), encoding="utf-8")
        try:
            telemetry = review.collect_diagnostics()
        except Exception as diagnostic_error:
            telemetry = {"available": False, "error": f"Diagnostics failed: {diagnostic_error}"}
        try:
            review.write_report(telemetry)
        except Exception as report_error:
            print(f"REPORT ERROR: {report_error}", file=sys.stderr)
        print(f"ERROR: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
