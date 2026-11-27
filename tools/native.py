#!/usr/bin/env python3
"""Puts a theme on the Karoo's OWN map and looks at it. Uses adb (see tools/karoo.py).

    python3 tools/native.py push karoo-native/GTA-VI-day.xml   replace the theme, restart the ride app, open the map
    python3 tools/native.py restore                            put Hammerhead's stock theme back
    python3 tools/native.py open                               open the Ride profile's map page
    python3 tools/native.py zoom out|in                        one simulated pinch
    python3 tools/native.py shot out.png                       screenshot

The ride app reads /sdcard/offline_v15.xml when it starts. The stock theme is kept on the unit as
/sdcard/offline_v15_ORIGINAL.xml; `push` refuses to run if that backup is missing.
No ride is ever started here: the Ride profile is only opened, which shows its pages.
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import karoo  # noqa: E402

THEME = "/sdcard/offline_v15.xml"
BACKUP = "/sdcard/offline_v15_ORIGINAL.xml"
RIDE = "io.hammerhead.rideapp"


def focus():
    return karoo.adb("shell", "dumpsys", "window", "windows").split("mCurrentFocus=")[-1].split("\n")[0]


def open_map():
    """From wherever the Karoo is: the dashboard, then the first profile card, which opens the map page."""
    for _ in range(6):
        if RIDE in focus():
            return True
        nodes = karoo.nodes()
        ids = {node["id"] for node in nodes}
        if "profileName" in ids:                       # the dashboard with the profile cards
            karoo.tap(239, 400)
            time.sleep(12)
        elif any(node["text"] == "Rides" for node in nodes):   # the apps grid: its back arrow
            karoo.tap(50, 733)
            time.sleep(2.5)
        else:
            karoo.adb("shell", "input", "keyevent", "KEYCODE_HOME")
            time.sleep(3)
    return RIDE in focus()


def restart_ride():
    karoo.adb("shell", "am", "force-stop", RIDE)
    time.sleep(2)
    karoo.adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(3)
    return open_map()


def push(path):
    if "No such file" in karoo.adb("shell", "ls %s 2>&1" % BACKUP):
        sys.exit("no backup of the stock theme on the unit (%s): not replacing anything" % BACKUP)
    karoo.adb("push", path, THEME)
    if not restart_ride():
        sys.exit("pushed, but the ride app's map did not open")
    time.sleep(6)


def restore():
    karoo.adb("shell", "cp %s %s" % (BACKUP, THEME))
    restart_ride()


def zoom(direction):
    if direction == "out":
        karoo.pinch(360, 200, steps=6)
    else:
        karoo.pinch(180, 340, steps=6)
    time.sleep(5)


if __name__ == "__main__":
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "push":
        push(args[0])
        print("theme on the unit:", karoo.adb("shell", "md5sum", THEME).split()[0])
    elif cmd == "restore":
        restore()
        print("stock theme back:", karoo.adb("shell", "md5sum", THEME).split()[0])
    elif cmd == "open":
        print("map page open:", open_map())
    elif cmd == "zoom":
        zoom(args[0])
    elif cmd == "shot":
        print(karoo.shot(args[0]))
