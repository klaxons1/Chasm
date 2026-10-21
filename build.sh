#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JDK_HOME="/tmp/jdk8"
SUITE_DIR="/tmp/LLM-J2ME-SUITE"

if [ ! -d "${SUITE_DIR}" ]; then
    echo "=== Cloning LLM-J2ME-SUITE toolchain ==="
    git clone --depth 1 https://github.com/klaxons1/LLM-J2ME-SUITE.git "${SUITE_DIR}"
fi

if [ ! -x "${JDK_HOME}/bin/javac" ]; then
    echo "=== Cloning Linux JDK 8 toolchain ==="
    git clone --depth 1 https://github.com/klaxons1/tools_linux_jdk8.git "${JDK_HOME}"
    chmod +x "${JDK_HOME}/bin/"*
fi

JAVAC="${JDK_HOME}/bin/javac"
JAVA="${JDK_HOME}/bin/java"
JAR="${JDK_HOME}/bin/jar"

BOOT_CP="${SUITE_DIR}/uei/cldc11.jar:${SUITE_DIR}/uei/midp21.jar:${SUITE_DIR}/uei/nokiaui.jar:${SUITE_DIR}/uei/jsr135.jar"
PROGUARD_JAR="${SUITE_DIR}/libs/proguard.jar"

cd "${ROOT_DIR}"

echo "=== [1/4] Checking Chasm: The Rift J2ME Resources ==="
if [ ! -f "${ROOT_DIR}/res/levels/level1.c3b" ] || [ ! -f "${ROOT_DIR}/res/models.bin" ]; then
    if [ ! -f "/tmp/ChasmTheRift/CSM.BIN" ]; then
        git clone --depth 1 https://github.com/Spastic-Dev/ChasmTheRift.git /tmp/ChasmTheRift
    fi
    if [ ! -f "/tmp/CovertOps3D-RE/scripts/c3d2_core.py" ]; then
        git clone --depth 1 -b arena/019ff1eb-covertops3d-re https://github.com/klaxons1/CovertOps3D-RE.git /tmp/CovertOps3D-RE
    fi
    python3 tools/build_assets.py
else
    echo "Using existing pre-built J2ME C3D2/C3B & 3D resources in ${ROOT_DIR}/res/"
fi

echo "=== [2/4] Compiling J2ME Sources (-source 1.3 -target 1.1) ==="
rm -rf /tmp/chasm_build
mkdir -p /tmp/chasm_build/classes

"${JAVAC}" \
    -encoding UTF-8 \
    -source 1.3 \
    -target 1.1 \
    -bootclasspath "${BOOT_CP}" \
    -classpath "${BOOT_CP}" \
    -d /tmp/chasm_build/classes \
    src/chasm/*.java

cat > /tmp/chasm_build/MANIFEST.MF << 'EOF'
Manifest-Version: 1.0
MIDlet-Name: Chasm: The Rift
MIDlet-Version: 1.0.0
MIDlet-Vendor: Action Forms / J2ME Port
MIDlet-Icon: /icon.png
MIDlet-Description: 3D FPS with C3D2 portal renderer and polygonal 3D entities for Nokia S60 & Sony Ericsson
MIDlet-1: Chasm: The Rift, /icon.png, chasm.ChasmMIDlet
MicroEdition-Profile: MIDP-2.0
MicroEdition-Configuration: CLDC-1.1
EOF

echo "=== [3/4] Packaging Unverified JAR & Running ProGuard -microedition Preverifier ==="
cp -r res/* /tmp/chasm_build/classes/
"${JAR}" cfm /tmp/chasm_build/unverified.jar /tmp/chasm_build/MANIFEST.MF -C /tmp/chasm_build/classes .

mkdir -p "${ROOT_DIR}/releases"

cat > /tmp/chasm_build/proguard.pro << EOF
-injars /tmp/chasm_build/unverified.jar
-outjars ${ROOT_DIR}/releases/ChasmTheRift.jar
-libraryjars ${SUITE_DIR}/uei/cldc11.jar
-libraryjars ${SUITE_DIR}/uei/midp21.jar
-libraryjars ${SUITE_DIR}/uei/nokiaui.jar
-libraryjars ${SUITE_DIR}/uei/jsr135.jar
-microedition
-dontshrink
-dontoptimize
-dontobfuscate
-keep public class chasm.ChasmMIDlet
EOF

"${JAVA}" -jar "${PROGUARD_JAR}" @/tmp/chasm_build/proguard.pro

echo "=== [4/4] Generating JAD Descriptor (releases/ChasmTheRift.jad) ==="
JAR_SIZE=$(stat -c%s "${ROOT_DIR}/releases/ChasmTheRift.jar")
cat > "${ROOT_DIR}/releases/ChasmTheRift.jad" << EOF
MIDlet-Name: Chasm: The Rift
MIDlet-Version: 1.0.0
MIDlet-Vendor: Action Forms / J2ME Port
MIDlet-Icon: /icon.png
MIDlet-Description: 3D FPS with C3D2 portal renderer and polygonal 3D entities for Nokia S60 & Sony Ericsson
MIDlet-1: Chasm: The Rift, /icon.png, chasm.ChasmMIDlet
MIDlet-Jar-URL: ChasmTheRift.jar
MIDlet-Jar-Size: ${JAR_SIZE}
MicroEdition-Profile: MIDP-2.0
MicroEdition-Configuration: CLDC-1.1
EOF


rm -rf /tmp/chasm_build
echo "=== BUILD COMPLETE: releases/ChasmTheRift.jar (${JAR_SIZE} bytes) & releases/ChasmTheRift.jad ==="
