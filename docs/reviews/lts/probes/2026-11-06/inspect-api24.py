"""Check exact signed APK instructions against local Android SDK API metadata."""
import hashlib
import pathlib
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

apk = pathlib.Path(sys.argv[1])
sdk = pathlib.Path(sys.argv[2])
dexdump = sdk / "build-tools/36.0.0/dexdump"
api_xml = sdk / "platforms/android-36/data/api-versions.xml"
api = ET.parse(api_xml).getroot()
required = ("java/util/Base64", "java/util/Base64$Encoder", "java/util/Base64$Decoder")
print(f"APK SHA256 {hashlib.sha256(apk.read_bytes()).hexdigest()}", flush=True)
classes = {element.attrib["name"]: element.attrib["since"] for element in api if element.get("name") in required}
assert classes == dict.fromkeys(required, "26"), classes
for name, since in sorted(classes.items()):
    print(f"SDK API index {name}: {since}", flush=True)
methods = []
with tempfile.TemporaryDirectory(prefix="arris-api24-evidence.") as directory, zipfile.ZipFile(apk) as z:
    path = pathlib.Path(directory) / "classes.dex"
    for entry in z.namelist():
        if not re.fullmatch(r"classes\d*\.dex", entry):
            continue
        path.write_bytes(z.read(entry))
        with subprocess.Popen(
            [str(dexdump), "-d", str(path)], stdout=subprocess.PIPE, text=True, errors="replace"
        ) as process:
            for line in process.stdout:
                if "invoke-" in line and any(f"L{name};" in line for name in required):
                    instruction = line.strip().split("|")[-1]
                    print(entry, instruction)
                    methods.append(instruction)
            assert process.wait() == 0
for method in ("getEncoder", "getDecoder", "encodeToString", ".decode:"):
    assert any(method in instruction for instruction in methods), method
