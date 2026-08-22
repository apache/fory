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
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

from . import common


PRODUCTION_MODULES = "fory-kotlin,fory-kotlin-ksp,fory-json-kotlin,fory-json-kotlin-ksp"
LOW_JDK_MODULES = "fory-kotlin,fory-kotlin-ksp,fory-json-kotlin"
JSON_MODULES = "fory-json-kotlin,fory-json-kotlin-ksp"
CORPUS_MODULE_NAME = "org.apache.fory.integration.kotlin.json.corpus"
CORPUS_PACKAGE = "org.apache.fory.integration.kotlin.json.corpus"
CORPUS_RULE_MODELS = (
    "PlatformAccount",
    "PlatformBox",
    "PlatformCircle",
    "PlatformId",
    "PlatformMarker",
    "PlatformRoot",
    "PlatformShape",
)
CORPUS_MIXIN_TARGETS = {
    "PlatformJavaProfileMixin": "PlatformJavaProfile",
}


def java_major_version():
    """Return the active Java runtime's major version."""
    version_output = subprocess.check_output(
        "java -version 2>&1", shell=True, universal_newlines=True
    )
    match = re.search(r'version "([^"]+)"', version_output)
    if not match:
        raise RuntimeError(f"Unable to parse Java version from:\n{version_output}")
    version = match.group(1)
    if version.startswith("1."):
        return int(version.split(".")[1])
    return int(version.split(".")[0])


def install_java_json(include_jpms=False):
    """Install the Java artifacts consumed by Kotlin JSON modules."""
    modules = "fory-json,fory-annotation-processor"
    major = java_major_version()
    # JDK25+ activates multi-release verifiers whose test-owned main classes and source JAR inputs
    # must exist even when this installation does not run tests.
    test_option = "-DskipTests" if major >= 25 else "-Dmaven.test.skip=true"
    if include_jpms:
        modules = (
            "fory-json,fory-format,fory-test-core,fory-testsuite,"
            "fory-annotation-processor"
        )
        # The JDK25 multi-release verifier is test-owned and must still compile.
        test_option = "-DskipTests"
    install_options = [test_option, "-Dmaven.javadoc.skip=true"]
    if major < 25:
        install_options.append("-Dmaven.source.skip=true")
    common.cd_project_subdir("java")
    common.exec_cmd(
        "mvn -T16 --batch-mode --no-transfer-progress "
        f"-pl {modules} -am install {' '.join(install_options)}"
    )


def install_artifacts(include_corpus=True, modules=PRODUCTION_MODULES):
    """Install Kotlin production artifacts and the shared JSON corpus."""
    # Artifact consumers need only main JARs. Test stages compile their own test sources later.
    common.cd_project_subdir("kotlin")
    common.exec_cmd(
        "mvn -T16 --batch-mode --no-transfer-progress "
        f"-pl {modules} -am clean install -Dmaven.test.skip=true "
        "-Ddokka.skip=true -Dmaven.source.skip=true"
    )
    if include_corpus:
        common.cd_project_subdir("integration_tests/kotlin_json_corpus")
        common.exec_cmd(
            "mvn -T16 --batch-mode --no-transfer-progress clean install "
            "-Dmaven.test.skip=true -Ddokka.skip=true -Dmaven.source.skip=true"
        )
        verify_corpus_artifact()


def verify_corpus_artifact():
    """Verify exact KSP consumer-rule packaging in the shared platform corpus JAR."""
    module_dir = Path(
        common.PROJECT_ROOT_DIR, "integration_tests", "kotlin_json_corpus"
    )
    version = _kotlin_version()
    jar_path = module_dir / "target" / f"kotlin-json-corpus-{version}.jar"
    if not jar_path.is_file():
        raise FileNotFoundError(f"Missing Kotlin JSON corpus artifact: {jar_path}")

    expected_rules = {
        f"META-INF/proguard/fory-json-{CORPUS_PACKAGE}.{model}.pro"
        for model in CORPUS_RULE_MODELS
    }
    expected_rules.update(
        f"META-INF/proguard/fory-json-mixin-{CORPUS_PACKAGE}.{model}.pro"
        for model in CORPUS_MIXIN_TARGETS
    )
    with zipfile.ZipFile(jar_path) as jar:
        names = set(jar.namelist())
        manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8")
        expected_module = f"Automatic-Module-Name: {CORPUS_MODULE_NAME}"
        if expected_module not in manifest:
            raise RuntimeError(f"{jar_path} is missing {expected_module}")
        missing = sorted(expected_rules - names)
        if missing:
            raise RuntimeError(f"{jar_path} is missing consumer rules: {missing}")
        actual_rules = {
            name
            for name in names
            if name.startswith("META-INF/proguard/fory-json-") and name.endswith(".pro")
        }
        if actual_rules != expected_rules:
            raise RuntimeError(
                f"{jar_path} has unexpected consumer rules: "
                f"{sorted(actual_rules ^ expected_rules)}"
            )
        stale_classes = {
            name
            for name in names
            if name.endswith("_ForyJsonCodec.class")
            or name.endswith("_Operations.class")
        }
        if stale_classes:
            raise RuntimeError(
                f"{jar_path} contains generated codec classes: {stale_classes}"
            )


def _kotlin_version():
    pom = Path(common.PROJECT_ROOT_DIR, "kotlin", "pom.xml")
    root = ET.parse(pom).getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    version = root.findtext("m:version", namespaces=namespace)
    if not version:
        raise ValueError(f"Cannot find Kotlin parent version in {pom}")
    return version


def run_tests():
    """Run the Kotlin JVM matrix for the active JDK."""
    logging.info("Executing fory kotlin tests")
    os.environ.setdefault("ENABLE_FORY_DEBUG_OUTPUT", "1")
    major = java_major_version()
    install_java_json(include_jpms=major == 25)
    modules = PRODUCTION_MODULES if major >= 17 else LOW_JDK_MODULES
    install_artifacts(include_corpus=major >= 17, modules=modules)
    common.cd_project_subdir("kotlin")
    if major >= 17:
        common.exec_cmd(
            "mvn -T16 --batch-mode --no-transfer-progress test -DfailIfNoTests=false"
        )
        common.exec_cmd("mvn -T16 --batch-mode --no-transfer-progress spotless:check")
        common.cd_project_subdir("integration_tests/kotlin_json_corpus")
        common.exec_cmd(
            "mvn -T16 --batch-mode --no-transfer-progress clean test "
            "-DfailIfNoTests=false"
        )
    else:
        logging.info(
            "Skipping KSP generation tests on JDK < 17 because ksp-maven-plugin requires Java 17+"
        )
        common.exec_cmd(
            "mvn -T16 --batch-mode --no-transfer-progress "
            f"-pl {LOW_JDK_MODULES} -am test -DfailIfNoTests=false"
        )
    if major == 25:
        common.cd_project_subdir("kotlin")
        common.exec_cmd(
            "mvn -T16 --batch-mode --no-transfer-progress "
            f"-pl {PRODUCTION_MODULES} -am package -DskipTests "
            "-Dgpg.skip=true -Papache-release"
        )
        common.cd_project_subdir("")
        common.exec_cmd("python ci/release.py verify_kotlin_artifacts")
        common.cd_project_subdir("integration_tests/jpms_tests")
        common.exec_cmd("mvn -T10 --batch-mode --no-transfer-progress clean test")

    logging.info("Executing fory kotlin tests succeeds")


def run_native_json():
    """Build and execute the dedicated Kotlin JSON Native Image fixture."""
    os.environ.setdefault("ENABLE_FORY_DEBUG_OUTPUT", "1")
    install_java_json()
    install_artifacts(include_corpus=True)
    common.cd_project_subdir("integration_tests/graalvm_kotlin_tests")
    common.exec_cmd(
        "mvn --batch-mode --no-transfer-progress -DskipTests=true -Pnative clean package"
    )
    common.exec_cmd("./target/main")


def run(task="tests"):
    """Run the selected Kotlin CI task."""
    if task == "tests":
        run_tests()
    elif task == "install-json":
        install_java_json()
        install_artifacts(include_corpus=True, modules=JSON_MODULES)
    elif task == "install-kotlin":
        install_artifacts(include_corpus=True)
    elif task == "native-json":
        run_native_json()
    else:
        raise ValueError(f"Unsupported Kotlin CI task: {task}")
