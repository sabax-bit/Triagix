import subprocess
import sys

# 1. Install all required libraries first
dependencies = ["pyinstaller", "tkintermapview", "sounddevice", "numpy", "pillow"]
print("[*] Installing required dependencies...")
for pkg in dependencies:
    subprocess.check_call([sys.executable, "-m", "pip", "install", pkg])

# 2. Build .exe with explicit hidden-imports & collected data
print("\n[*] Packaging TRIAGIX Base Station into .exe...")
build_cmd = [
    sys.executable, "-m", "PyInstaller",
    "--noconsole",
    "--onefile",
    "--collect-all", "tkintermapview",
    "--collect-all", "sounddevice",
    "--hidden-import", "tkintermapview",
    "--hidden-import", "sounddevice",
    "--hidden-import", "numpy",
    "--name", "TRIAGIX_Base_Station",
    "triagix_base_gui.py"
]

subprocess.check_call(build_cmd)
print("\n✅ BUILD SUCCESSFUL! 'dist' folder-la irukura TRIAGIX_Base_Station.exe-ah ippo run pannunga.")
