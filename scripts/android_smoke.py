"""Exercise the production APK through Android intents and its accessible UI on API 35."""
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

OUT = pathlib.Path("smoke-results")
OUT.mkdir(exist_ok=True)
PACKAGE = "com.abonoura.aura"
ACTIVITY = PACKAGE + "/.MainActivity"

def adb(*args, check=True):
    return subprocess.run(["adb", *args], check=check, capture_output=True).stdout.decode(errors="replace")

def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/aura-ui.xml")
    xml = adb("shell", "cat", "/sdcard/aura-ui.xml")
    return list(ET.fromstring(xml).iter("node"))

def tap(text):
    candidates = [n for n in nodes() if n.get("text") == text or n.get("content-desc") == text]
    if not candidates:
        raise AssertionError("UI control not found: " + text)
    bounds = [int(x) for x in re.findall(r"\d+", candidates[0].get("bounds", ""))]
    adb("shell", "input", "tap", str((bounds[0]+bounds[2])//2), str((bounds[1]+bounds[3])//2))
    time.sleep(1)

def capture(name):
    (OUT / (name+".png")).write_bytes(subprocess.check_output(["adb", "exec-out", "screencap", "-p"]))
    (OUT / (name+".xml")).write_text(adb("shell", "cat", "/sdcard/aura-ui.xml"))

def launch():
    adb("shell", "am", "start", "-n", ACTIVITY)
    time.sleep(3)

adb("logcat", "-c")
adb("shell", "wm", "size", "1280x800")
adb("shell", "wm", "density", "160")
launch()
assert any(n.get("text") == "أهلًا بك في أورا" for n in nodes()), "Home must open without library permission"
capture("01-tablet-without-permission")
for permission in ["READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO"]:
    adb("shell", "pm", "grant", PACKAGE, "android.permission."+permission)
adb("shell", "mkdir", "-p", "/sdcard/Movies", "/sdcard/Music", "/sdcard/Download")
adb("push", "smoke-fixtures/AuraSmoke.mp4", "/sdcard/Movies/AuraSmoke.mp4")
adb("push", "smoke-fixtures/AuraTone.mp3", "/sdcard/Music/AuraTone.mp3")
for source in ["/sdcard/Movies/AuraSmoke.mp4", "/sdcard/Music/AuraTone.mp3"]:
    adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://"+source)
adb("shell", "am", "force-stop", PACKAGE)
launch()
tap("الملفات")
assert any("AuraSmoke" in n.get("text", "") for n in nodes()), "Video must appear in the library"
capture("02-video-library")
tap("الصوت")
assert any("AuraTone" in n.get("text", "") for n in nodes()), "Audio must appear in the library"
capture("03-audio-library")
adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", "file:///sdcard/Movies/AuraSmoke.mp4", "-t", "video/mp4", "-n", ACTIVITY)
time.sleep(4)
if any(n.get("text") == "Got it" for n in nodes()):
    tap("Got it")
adb("shell", "input", "tap", "640", "400")
video_nodes=nodes()
capture("04-external-video")
assert any(re.search(r"0:[0-9]{2} / 0:45", n.get("text", "")) for n in video_nodes), "Video duration and position must be available"
assert PACKAGE in adb("shell", "dumpsys", "activity", "activities"), "Video Activity must remain alive"
adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", "file:///sdcard/Music/AuraTone.mp3", "-t", "audio/mpeg", "-n", ACTIVITY)
time.sleep(5)
nodes()
capture("04b-external-audio-before-background")
(OUT / "media-session.txt").write_text(adb("shell", "dumpsys", "media_session"))
(OUT / "audio-logcat.txt").write_text(adb("logcat", "-d", "-s", "ExoPlayerImplInternal:E", "MediaSessionService:V", "MediaSessionImpl:V", "AndroidRuntime:E"))
adb("shell", "input", "keyevent", "3")
time.sleep(2)
service = adb("shell", "dumpsys", "activity", "services", PACKAGE+"/.AudioService")
(OUT / "audio-service.txt").write_text(service)
assert "isForeground=true" in service, "Audio must keep a foreground media service while the Activity is backgrounded"
adb("shell", "input", "keyevent", "127")
launch()
nodes()
capture("05-audio-mini-player")
tap("الإعدادات")
assert any(n.get("text") == "خلفية الصفحة" for n in nodes()), "Wallpaper controls must be present"
capture("06-wallpaper-settings")
adb("shell", "input", "keyevent", "4")
adb("shell", "wm", "size", "1080x2400")
adb("shell", "wm", "density", "420")
adb("shell", "am", "force-stop", PACKAGE)
launch()
for title in ["الرئيسية", "الملفات", "الصوت", "البث"]:
    assert any(n.get("text") == title for n in nodes()), "Phone navigation missing: "+title
capture("07-phone-home")
log = adb("logcat", "-d", "-s", "AndroidRuntime:E")
(OUT / "runtime-errors.txt").write_text(log)
assert "FATAL EXCEPTION" not in log and "UnsatisfiedLinkError" not in log, "App crashed; see runtime-errors.txt"
print("Android smoke checks passed")
