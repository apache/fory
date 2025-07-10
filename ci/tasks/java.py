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
from . import common

# JDK versions
JDKS = [
    "zulu21.28.85-ca-jdk21.0.0-linux_x64",
    "zulu17.44.17-ca-crac-jdk17.0.8-linux_x64",
    "zulu15.46.17-ca-jdk15.0.10-linux_x64",
    "zulu13.54.17-ca-jdk13.0.14-linux_x64",
    "zulu11.66.15-ca-jdk11.0.20-linux_x64",
    "zulu8.72.0.17-ca-jdk8.0.382-linux_x64",
]


def install_jdks():
    """Download and install JDKs."""
    common.cd_project_subdir("")  # Go to the project root
    for jdk in JDKS:
        common.exec_cmd(
            f"wget -q https://cdn.azul.com/zulu/bin/{jdk}.tar.gz -O {jdk}.tar.gz"
        )
        common.exec_cmd(f"tar zxf {jdk}.tar.gz")


def run_java8():
    """Run Java 8 tests."""
    logging.info("Executing fory java tests with Java 8")
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
    logging.info("Executing latest_jdk_tests")

    common.cd_project_subdir("integration_tests/latest_jdk_tests")
    common.exec_cmd("mvn -T10 -B --no-transfer-progress clean test")

    logging.info("Executing latest_jdk_tests succeeds")


def run_windows_java21():
    """Run Java 21 tests on Windows."""
    logging.info("Executing fory java tests on Windows with Java 21")
    common.exec_cmd("java -version")

    common.cd_project_subdir("java")
    # Use double quotes for Windows compatibility
    if common.is_windows():
        common.exec_cmd(
            'mvn -T10 --batch-mode --no-transfer-progress test -Dtest=!org.apache.fory.CrossLanguageTest install -pl "!fory-format,!fory-testsuite"'
        )
    else:
        common.exec_cmd(
            "mvn -T10 --batch-mode --no-transfer-progress test -Dtest=!org.apache.fory.CrossLanguageTest install -pl '!fory-format,!fory-testsuite'"
        )

    logging.info("Executing fory java tests succeeds")


def run_integration_tests():
    """Run Java integration tests."""
    logging.info("Install JDKs")
    install_jdks()

    logging.info("Executing fory integration tests")

    common.cd_project_subdir("java")
    common.exec_cmd("mvn -T10 -B --no-transfer-progress clean install -DskipTests")

    logging.info("benchmark tests")
    common.cd_project_subdir("java/benchmark")
    common.exec_cmd("mvn -T10 -B --no-transfer-progress clean test install -Pjmh")

    logging.info("Start latest jdk tests")
    common.cd_project_subdir("integration_tests/latest_jdk_tests")
    logging.info("latest_jdk_tests: JDK 21")

    # Set Java 21 as the current JDK
    java_home = os.path.join(common.PROJECT_ROOT_DIR, JDKS[0])
    os.environ["JAVA_HOME"] = java_home
    os.environ["PATH"] = f"{java_home}/bin:{os.environ.get('PATH', '')}"

    common.exec_cmd("mvn -T10 -B --no-transfer-progress clean test")

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
    for jdk in JDKS:
        java_home = os.path.join(common.PROJECT_ROOT_DIR, jdk)
        os.environ["JAVA_HOME"] = java_home
        os.environ["PATH"] = f"{java_home}/bin:{os.environ.get('PATH', '')}"

        logging.info(f"Generating data with JDK: {jdk}")
        common.exec_cmd(
            "mvn -T10 --no-transfer-progress clean test -Dtest=org.apache.fory.integration_tests.JDKCompatibilityTest"
        )

    # Second round: Test cross-JDK compatibility
    logging.info("Second round: Test cross-JDK compatibility")
    for jdk in JDKS:
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
    common.exec_cmd("mvn -T10 -B --no-transfer-progress clean install -DskipTests")

    logging.info("Start to build graalvm native image")
    common.cd_project_subdir("integration_tests/graalvm_tests")
    common.exec_cmd("mvn -DskipTests=true --no-transfer-progress -Pnative package")

    logging.info("Built graalvm native image")
    logging.info("Start to run graalvm native image")
    common.exec_cmd("./target/main")

    logging.info("Execute graalvm tests succeed!")


def run(java_version=None):
    """Run Java CI tasks based on the specified Java version."""
    if java_version == "8":
        run_java8()
    elif java_version == "11":
        run_java11()
    elif java_version == "17":
        run_jdk17_plus("17")
    elif java_version == "21":
        run_jdk17_plus("21")
    elif java_version == "24":
        run_jdk17_plus("24")
    elif java_version == "windows_java21":
        run_windows_java21()
    elif java_version == "integration_tests":
        run_integration_tests()
    elif java_version == "graalvm":
        run_graalvm_test()
    else:
        # Default to Java 17 if no version specified
        run_jdk17_plus("17")
