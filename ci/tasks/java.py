# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

import logging
import os
import subprocess
import re
from . import common


def get_jdk_major_version():
    try:
        # Run the 'java -version' command
        result = subprocess.run(["java", "-version"], capture_output=True, text=True)
        output = result.stderr  # java -version outputs to stderr

        # Use regex to find the version string
        match = re.search(r'version "([^"]+)"', output)
        if not match:
            return None

        version_string = match.group(1)

        # Parse the version string
        version_parts = version_string.split(".")
        if version_parts[0] == "1":
            # Java 8 or earlier
            return int(version_parts[1])
        else:
            # Java 9 or later
            return int(version_parts[0])

    except Exception:
        return None


# JDK versions
JDKS = {
    "8": "zulu8.72.0.17-ca-jdk8.0.382-linux_x64",
    "11": "zulu11.66.15-ca-jdk11.0.20-linux_x64",
    "17": "zulu17.44.17-ca-crac-jdk17.0.8-linux_x64",
    "21": "zulu21.28.85-ca-jdk21.0.0-linux_x64",
    "24": "zulu24.32.13-ca-fx-jdk24.0.2-linux_x64",
    "25": "zulu25.30.17-ca-jdk25.0.1-linux_x64",
}


def install_jdks():
    """Download and install JDKs."""
    logging.info("Downloading and installing JDKs")
    common.cd_project_subdir("")  # Go to the project root
    for jdk in JDKS.values():
        if os.path.exists(os.path.join(common.PROJECT_ROOT_DIR, jdk)):
            logging.info(f"JDK {jdk} already exists")
            continue
        common.exec_cmd(
            f"wget -q https://cdn.azul.com/zulu/bin/{jdk}.tar.gz -O {jdk}.tar.gz"
        )
        common.exec_cmd(f"tar zxf {jdk}.tar.gz")
    logging.info("Creating toolchains.xml")
    create_toolchains_xml(JDKS)
    logging.info("JDKs downloaded and installed successfully")


def create_toolchains_xml(jdk_mappings):
    """Create toolchains.xml file in ~/.m2/ directory."""
    import os
    import xml.etree.ElementTree as ET
    from xml.dom import minidom

    # Create ~/.m2 directory if it doesn't exist
    m2_dir = os.path.expanduser("~/.m2")
    os.makedirs(m2_dir, exist_ok=True)

    # Create the root element
    toolchains = ET.Element("toolchains")

    for version, jdk_name in jdk_mappings.items():
        toolchain = ET.SubElement(toolchains, "toolchain")

        # Set type
        type_elem = ET.SubElement(toolchain, "type")
        type_elem.text = "jdk"

        # Set provides
        provides = ET.SubElement(toolchain, "provides")
        version_elem = ET.SubElement(provides, "version")
        version_elem.text = version
        vendor_elem = ET.SubElement(provides, "vendor")
        vendor_elem.text = "azul"

        # Set configuration
        configuration = ET.SubElement(toolchain, "configuration")
        jdk_home = ET.SubElement(configuration, "jdkHome")
        jdk_home.text = os.path.abspath(os.path.join(common.PROJECT_ROOT_DIR, jdk_name))

    # Create pretty XML string
    rough_string = ET.tostring(toolchains, "unicode")
    reparsed = minidom.parseString(rough_string)
    pretty_xml = reparsed.toprettyxml(indent="  ")

    # Add proper XML header with encoding
    xml_header = '<?xml version="1.0" encoding="UTF8"?>\n'
    pretty_xml = (
        xml_header + pretty_xml.split("\n", 1)[1]
    )  # Remove the default header and add our custom one

    # Write to ~/.m2/toolchains.xml
    toolchains_path = os.path.join(m2_dir, "toolchains.xml")
    with open(toolchains_path, "w", encoding="utf-8") as f:
        f.write(pretty_xml)

    logging.info(f"Created toolchains.xml at {toolchains_path}")
    logging.info("Toolchains configuration:")
    for version, jdk_name in jdk_mappings.items():
        jdk_path = os.path.join(common.PROJECT_ROOT_DIR, jdk_name)
        logging.info(f"  JDK {version}: {jdk_path}")
    # print toolchains.xml
    with open(toolchains_path, "r", encoding="utf-8") as f:
        logging.info(f.read())


def install_fory():
    """Install Fory."""
    # Always install jdks and create toolchains.xml to ensure proper JDK environment
    if get_jdk_major_version() == 8:
        install_jdks()
        java_home = os.path.join(common.PROJECT_ROOT_DIR, JDKS["11"])
        os.environ["JAVA_HOME"] = java_home
        os.environ["PATH"] = f"{java_home}/bin:{os.environ.get('PATH', '')}"
    common.cd_project_subdir("java")
    common.exec_cmd("mvn -T16 --batch-mode --no-transfer-progress install -DskipTests")


def run_java8():
    """Run Java 8 tests."""
    logging.info("Executing fory java tests with Java 8")
    install_jdks()
    common.cd_project_subdir("java")
    common.exec_cmd("mvn -T16 --batch-mode --no-transfer-progress test")
    logging.info("Executing fory java tests succeeds")


def run_java11():
    """Run Java 11 tests."""
    logging.info("Executing fory java tests with Java 11")
    common.cd_project_subdir("java")
    common.exec_cmd("mvn -T16 --batch-mode --no-transfer-progress test")
    logging.info("Executing fory java tests succeeds")


def run_jdk17_plus(java_version="17"):
    """Run Java 17+ tests."""
    logging.info(f"Executing fory java tests with Java {java_version}")
    common.exec_cmd("java -version")
    os.environ["JDK_JAVA_OPTIONS"] = (
        "--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED"
    )

    common.cd_project_subdir("java")
    common.exec_cmd("mvn -T10 --batch-mode --no-transfer-progress install")

    logging.info("Executing fory java tests succeeds")


def run_windows_java21():
    """Run Java 21 tests on Windows."""
    logging.info("Executing fory java tests on Windows with Java 21")
    common.exec_cmd("java -version")

    common.cd_project_subdir("java")
    # Use double quotes for Windows compatibility
    if common.is_windows():
        common.exec_cmd(
            'mvn -T10 --batch-mode --no-transfer-progress test install -pl "!fory-testsuite"'
        )
    else:
        common.exec_cmd(
            "mvn -T10 --batch-mode --no-transfer-progress test install -pl '!fory-testsuite'"
        )

    logging.info("Executing fory java tests succeeds")


def run_integration_tests():
    """Run Java integration tests."""
    logging.info("Install JDKs")
    install_jdks()

    logging.info("Executing fory integration tests")

    common.cd_project_subdir("java")
    common.exec_cmd(
        "mvn -T10 -B --no-transfer-progress clean install -DskipTests -pl '!:fory-testsuite'"
    )

    logging.info("benchmark tests")
    common.cd_project_subdir("benchmarks/java")
    common.exec_cmd("mvn -T10 -B --no-transfer-progress clean test install -Pjmh")

    logging.info("Start JPMS tests")
    common.cd_project_subdir("integration_tests/jpms_tests")
    common.exec_cmd("mvn -T10 -B --no-transfer-progress clean compile")

    logging.info("Start jdk compatibility tests")
    common.cd_project_subdir("integration_tests/jdk_compatibility_tests")
    common.exec_cmd("mvn -T10 -B --no-transfer-progress clean test")

    # Run tests with different JDK versions
    # This is a two-phase process:
    # 1. First round: Generate serialized data files for each JDK version
    # 2. Second round: Test if these files can be deserialized correctly by each JDK version

    # First round: Generate serialized data files
    logging.info("First round: Generate serialized data files for each JDK version")
    for jdk in JDKS.values():
        java_home = os.path.join(common.PROJECT_ROOT_DIR, jdk)
        os.environ["JAVA_HOME"] = java_home
        os.environ["PATH"] = f"{java_home}/bin:{os.environ.get('PATH', '')}"

        logging.info(f"Generating data with JDK: {jdk}")
        common.exec_cmd(
            "mvn -T10 --no-transfer-progress clean test -Dtest=org.apache.fory.integration_tests.JDKCompatibilityTest"
        )

    # Second round: Test cross-JDK compatibility
    logging.info("Second round: Test cross-JDK compatibility")
    for jdk in JDKS.values():
        java_home = os.path.join(common.PROJECT_ROOT_DIR, jdk)
        os.environ["JAVA_HOME"] = java_home
        os.environ["PATH"] = f"{java_home}/bin:{os.environ.get('PATH', '')}"

        logging.info(f"Testing compatibility with JDK: {jdk}")
        common.exec_cmd(
            "mvn -T10 --no-transfer-progress clean test -Dtest=org.apache.fory.integration_tests.JDKCompatibilityTest"
        )

    logging.info("Executing fory integration tests succeeds")


def run_graalvm_test():
    """Run GraalVM tests."""
    logging.info("Start GraalVM tests")

    common.cd_project_subdir("java")
    common.exec_cmd(
        "mvn -T10 -B --no-transfer-progress clean install -DskipTests -pl '!:fory-testsuite'"
    )

    logging.info("Start to build graalvm native image")
    common.cd_project_subdir("integration_tests/graalvm_tests")
    common.exec_cmd("mvn -DskipTests=true --no-transfer-progress -Pnative package")

    logging.info("Built graalvm native image")
    logging.info("Start to run graalvm native image")
    common.exec_cmd("./target/main")

    logging.info("Execute graalvm tests succeed!")


def run_release():
    """Release to Maven Central."""
    logging.info("Starting release to Maven Central with Java")
    common.cd_project_subdir("java")

    # Clean and install without tests first
    logging.info("Cleaning and installing dependencies")
    common.exec_cmd("mvn -T10 -B --no-transfer-progress clean install -DskipTests")

    # Deploy to Maven Central
    logging.info("Deploying to Maven Central")
    common.exec_cmd(
        "mvn -T10 -B --no-transfer-progress clean deploy -Dgpg.skip -DskipTests -Papache-release"
    )

    logging.info("Release to Maven Central completed successfully")


def run(version=None, release=False, install_jdks=False, install_fory=False):
    """Run Java CI tasks based on the specified Java version."""
    if install_jdks:
        globals()["install_jdks"]()
    if install_fory:
        globals()["install_fory"]()
    if release:
        logging.info("Release mode enabled - will release to Maven Repository")
        run_release()
    elif version == "8":
        run_java8()
    elif version == "11":
        run_java11()
    elif version == "17":
        run_jdk17_plus("17")
    elif version == "21":
        run_jdk17_plus("21")
    elif version == "25":
        run_jdk17_plus("25")
    elif version == "windows_java21":
        run_windows_java21()
    elif version == "integration_tests":
        run_integration_tests()
    elif version == "graalvm":
        run_graalvm_test()
