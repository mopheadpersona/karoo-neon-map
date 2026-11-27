#!/usr/bin/env python3
"""Drives the Karoo over adb: screenshots, reading what is on screen, tapping by label.

    python3 tools/karoo.py shot out.png        screenshot, straight from the unit
    python3 tools/karoo.py ui                  list what is on screen (text, id, centre)
    python3 tools/karoo.py tap "Map style"     tap the first thing whose text or label matches
    python3 tools/karoo.py tapid map_zoom_in   tap by resource id (the part after the slash)
    python3 tools/karoo.py xy 240 400          tap a point
    python3 tools/karoo.py key BACK            press a key (Android KEYCODE_ name)

Uses the adb in the top folder, or the one on your PATH.
"""
import os
import re
import subprocess
import sys
import time
import xml.dom.minidom

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = os.path.join(HERE, "adb") if os.path.exists(os.path.join(HERE, "adb")) else "adb"   # this folder, else the PATH


def adb(*args, binary=False, timeout=60):
    out = subprocess.run([ADB] + list(args), capture_output=True, timeout=timeout).stdout
    return out if binary else out.decode("utf-8", "replace")


def shot(path):
    data = adb("exec-out", "screencap", "-p", binary=True)
    with open(path, "wb") as handle:
        handle.write(data)
    return path


def nodes():
    """Everything uiautomator sees: (text, description, id, class, centre x, centre y, bounds).

    The old dump is deleted first. uiautomator fails while the screen keeps changing, and reading
    the file it left behind last time would silently show a screen that is long gone.
    """
    raw = ""
    for _ in range(5):
        adb("shell", "rm", "-f", "/sdcard/ui.xml")
        said = adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        if "dumped" in said:
            raw = adb("exec-out", "cat", "/sdcard/ui.xml")
            if raw.strip().startswith("<?xml"):
                break
        raw = ""
        time.sleep(1.5)
    if not raw:
        return []
    out = []
    for node in xml.dom.minidom.parseString(raw.encode("utf-8")).getElementsByTagName("node"):
        match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.getAttribute("bounds"))
        if not match:
            continue
        x0, y0, x1, y1 = map(int, match.groups())
        out.append({
            "text": node.getAttribute("text"), "desc": node.getAttribute("content-desc"),
            "id": node.getAttribute("resource-id").split("/")[-1], "cls": node.getAttribute("class").split(".")[-1],
            "x": (x0 + x1) // 2, "y": (y0 + y1) // 2, "bounds": (x0, y0, x1, y1),
            "checked": node.getAttribute("checked"), "clickable": node.getAttribute("clickable"),
            "focused": node.getAttribute("focused"),
        })
    return out


def find(label=None, rid=None, exact=False):
    for node in nodes():
        if rid is not None and node["id"] == rid:
            return node
        if label is not None:
            for value in (node["text"], node["desc"]):
                if (value == label) if exact else (label.lower() in value.lower() and value):
                    return node
    return None


def tap(x, y):
    adb("shell", "input", "tap", str(x), str(y))


def pinch(start, end, centre=(240, 400), steps=8, touch="/dev/input/event1"):
    """A two-finger pinch on the touchscreen: the fingers go from `start` pixels apart to `end`.

    Smaller end than start zooms a map out, bigger zooms in. `input` can't do two fingers, so this
    writes raw touch events (the shell user may: it is in the input group).
    """
    cx, cy = centre
    ev = []

    def finger(slot, x, y, down=False):
        ev.append("3 47 %d" % slot)
        if down:
            ev.append("3 57 %d" % (200 + slot))
        ev.extend(["3 53 %d" % x, "3 54 %d" % y, "3 48 8", "3 58 60"])

    half = start / 2.0
    finger(0, cx, cy - half, down=True)
    finger(1, cx, cy + half, down=True)
    ev.extend(["1 330 1", "0 0 0"])
    for step in range(1, steps + 1):
        half = (start + (end - start) * step / steps) / 2.0
        finger(0, cx, cy - half)
        finger(1, cx, cy + half)
        ev.append("0 0 0")
    ev.extend(["3 47 0", "3 57 4294967295", "3 47 1", "3 57 4294967295", "1 330 0", "0 0 0"])
    adb("shell", "; ".join("sendevent %s %s" % (touch, e) for e in ev))


if __name__ == "__main__":
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "shot":
        print(shot(args[0]))
    elif cmd == "ui":
        for n in nodes():
            if n["text"] or n["desc"] or (n["id"] and n["clickable"] == "true"):
                print("%4d,%-4d %-22s %-28s %s%s" % (n["x"], n["y"], n["cls"][:22], n["id"][:28],
                                                    n["text"] or ("(" + n["desc"] + ")" if n["desc"] else ""),
                                                    "  [x]" if n["checked"] == "true" else ""))
    elif cmd in ("tap", "tapid"):
        node = find(label=args[0]) if cmd == "tap" else find(rid=args[0])
        if not node:
            sys.exit("not on screen: %s" % args[0])
        tap(node["x"], node["y"])
        print("tapped %s at %d,%d" % (args[0], node["x"], node["y"]))
    elif cmd == "xy":
        tap(args[0], args[1])
    elif cmd == "key":
        adb("shell", "input", "keyevent", "KEYCODE_" + args[0])
