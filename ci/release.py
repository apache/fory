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

from __future__ import annotations

import argparse
import base64
import json
import logging
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

PROJECT_ROOT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../")
JVM_RELEASE_LANGS = ("java", "kotlin", "scala")
JVM_RELEASE_WORKTREE_ENV = "FORY_JVM_RELEASE_WORKTREE"
HOMEBREW_OPENJDK25 = "openjdk@25"
HOMEBREW_BREW_PATHS = ("/opt/homebrew/bin/brew", "/usr/local/bin/brew")
FORY_CORE_JDK25_ENTRY = (
    "META-INF/versions/25/org/apache/fory/reflect/InstanceFieldAccessors.class"
)
FORY_CORE_ACCESSOR = "org.apache.fory.reflect.InstanceFieldAccessors$InstanceAccessor"
FORY_CORE_FEATURE = "org.apache.fory.platform.ForyGraalVMFeature"
FORY_CORE_FEATURE_ENTRY = (
    "META-INF/versions/17/org/apache/fory/platform/ForyGraalVMFeature.class"
)
FORY_CORE_FEATURE_SOURCE_ENTRY = (
    "META-INF/versions/17/org/apache/fory/platform/ForyGraalVMFeature.java"
)
FORY_CORE_NATIVE_IMAGE_PROPERTIES = (
    "META-INF/native-image/org.apache.fory/fory-core/native-image.properties"
)
GRAALVM_FEATURE_SERVICE_ENTRY = (
    "META-INF/services/org.graalvm.nativeimage.hosted.Feature"
)
JAVA_RELEASE_PACKAGE_CMD = (
    "mvn -T10 clean package --no-transfer-progress -DskipTests -Papache-release"
)
JAVA_RELEASE_DEPLOY_CMD = (
    "mvn -T10 deploy --no-transfer-progress -DskipTests -Papache-release"
)
MAVEN_SNAPSHOT_CMD = (
    "mvn -T10 clean deploy --no-transfer-progress -DskipTests "
    "-Dgpg.skip=true -DretryFailedDeploymentCount=3 -Psnapshot-publication"
)
KOTLIN_RELEASE_PACKAGE_CMD = (
    "mvn -T10 clean package --no-transfer-progress -DskipTests -Papache-release"
)
KOTLIN_RELEASE_DEPLOY_CMD = (
    "mvn -T10 deploy --no-transfer-progress -DskipTests -Papache-release"
)
KOTLIN_SNAPSHOT_PACKAGE_CMD = (
    "mvn -T10 clean package --no-transfer-progress -DskipTests "
    "-Dgpg.skip=true -Psnapshot-publication"
)
KOTLIN_SNAPSHOT_DEPLOY_CMD = (
    "mvn -T10 deploy --no-transfer-progress -DskipTests "
    "-Dgpg.skip=true -DretryFailedDeploymentCount=3 -Psnapshot-publication"
)
SCALA_RELEASE_COMMANDS = (
    "sbt clean",
    "sbt 'project fory-scala' +publishSigned",
    "sbt 'project fory-json-scala' +publishSigned",
    "sbt sonatypePrepare",
    "sbt sonatypeBundleUpload",
)
SCALA_SNAPSHOT_COMMANDS = (
    "sbt clean",
    "sbt 'project fory-scala' +publish",
    "sbt 'project fory-json-scala' +publish",
)
JVM_PUBLICATION_MODES = ("release", "snapshot")
KOTLIN_PUBLIC_ARTIFACTS = (
    "fory-kotlin",
    "fory-kotlin-ksp",
    "fory-json-kotlin",
    "fory-json-kotlin-ksp",
)
KOTLIN_MODULE_NAMES = {
    "fory-json-kotlin": "org.apache.fory.json.kotlin",
    "fory-json-kotlin-ksp": "org.apache.fory.json.kotlin.ksp",
}
KOTLIN_SERVICE_PROVIDERS = {
    "fory-kotlin-ksp": "org.apache.fory.kotlin.ksp.ForyKotlinSymbolProcessorProvider",
    "fory-json-kotlin-ksp": (
        "org.apache.fory.json.kotlin.ksp.ForyJsonKotlinSymbolProcessorProvider"
    ),
}
RELEASE_DOC_ROOTS = (
    "README.md",
    "java/README.md",
    "java/fory-json/README.md",
    "kotlin/README.md",
    "rust/README.md",
    "scala/README.md",
    "scala/fory-scala/README.md",
    "scala/fory-json-scala/README.md",
    "csharp/README.md",
    "swift/README.md",
    "dart/packages/fory/README.md",
    "docs/introduction",
    "docs/start",
    "docs/object-serialization",
    "docs/row-format",
    "docs/json",
    "docs/compiler",
    "docs/grpc",
    "docs/development",
    "examples",
)
RELEASE_DOC_EXTS = (".md", ".example")
VERSION_SUFFIX_PATTERN = r"(?i:-snapshot|-dev\d*|\.dev\d+|-(?:alpha|beta|rc)\.\d+)"
VERSION_PATTERN = rf"\d+\.\d+\.\d+(?:{VERSION_SUFFIX_PATTERN})?"
NEXUS_BASE_URL = "https://repository.apache.org"
NEXUS_TIMEOUT_SECONDS = 30
NEXUS_CLOSE_ATTEMPTS = 30
NEXUS_CLOSE_INTERVAL_SECONDS = 10


def prepare(v: str):
    """Create a new release branch"""
    logger.info("Start to prepare release branch for version %s", v)
    _check_release_version(v)
    os.chdir(PROJECT_ROOT_DIR)
    branch = f"releases-{v}"
    try:
        subprocess.check_call(f"git checkout -b {branch}", shell=True)
        bump_version(version=v, l="all")
        subprocess.check_call("git add -u", shell=True)
        subprocess.check_call(f"git commit -m 'prepare release for {v}'", shell=True)
    except BaseException:
        logger.exception("Prepare branch failed")
        subprocess.check_call(f"git checkout - && git branch -D {branch}", shell=True)
        raise


def build(v: str):
    """version format: 0.5.1"""
    logger.info("Start to prepare release artifacts for version %s", v)
    _check_release_version(v)
    os.chdir(PROJECT_ROOT_DIR)
    if os.path.exists("dist"):
        shutil.rmtree("dist")
    os.mkdir("dist")
    branch = f"releases-{v}"
    # Check if branch exists, if not create it
    result = subprocess.run(
        f"git show-ref --verify --quiet refs/heads/{branch}",
        shell=True,
        capture_output=True,
        check=False,
    )
    if result.returncode == 0:
        # Branch exists, checkout
        subprocess.check_call(f"git checkout {branch}", shell=True)
    else:
        # Branch doesn't exist, create it
        subprocess.check_call(f"git checkout -b {branch}", shell=True)
    src_tar = f"apache-fory-{v}-src.tar.gz"
    _check_all_committed()
    _strip_unnecessary_license()
    subprocess.check_call(
        "git add LICENSE && git commit -m 'remove benchmark from license'", shell=True
    )
    subprocess.check_call(
        f"git archive --format=tar.gz "
        f"--output=dist/{src_tar} "
        f"--prefix=apache-fory-{v}-src/ {branch}",
        shell=True,
    )
    subprocess.check_call("git reset --hard HEAD~", shell=True)
    os.chdir("dist")
    logger.info("Start to generate signature")
    subprocess.check_call(
        f"gpg --armor --output {src_tar}.asc --detach-sig {src_tar}", shell=True
    )
    subprocess.check_call(f"sha512sum {src_tar} >{src_tar}.sha512", shell=True)
    verify(v)


def _check_release_version(v: str):
    assert v
    if "rc" in v:
        raise ValueError(
            "RC should only be contained in tag and svn directory, not in code"
        )


def _check_all_committed():
    proc = subprocess.run(
        "git diff --quiet", capture_output=True, shell=True, check=False
    )
    result = proc.returncode
    if result != 0:
        raise RuntimeError(
            f"There are some uncommitted files: {proc.stdout}, please commit it."
        )


def _strip_unnecessary_license():
    with open("LICENSE", "r") as f:
        lines = f.readlines()
    new_lines = []
    line_number = 0
    while line_number < len(lines):
        line = lines[line_number]
        if "fast-serialization" in line:
            line_number += 4
        elif "benchmark" in line:  # strip license in benchmark
            line_number += 1
        else:
            new_lines.append(line)
            line_number += 1
    text = "".join(new_lines)
    if lines != new_lines:
        with open("LICENSE", "w") as f:
            f.write(text)


def verify(v):
    src_tar = f"apache-fory-{v}-src.tar.gz"
    subprocess.check_call(f"gpg --verify {src_tar}.asc {src_tar}", shell=True)
    logger.info("Verified signature")
    subprocess.check_call(f"sha512sum --check {src_tar}.sha512", shell=True)
    logger.info("Verified checksum successfully")


def publish_jvm(languages="all", mode="release"):
    """Publish Java, Kotlin, and Scala artifacts through one ordered JVM owner."""
    langs = _jvm_release_langs(languages)
    release_worktree = os.environ.get(JVM_RELEASE_WORKTREE_ENV)
    if mode == "release" and not release_worktree:
        _publish_jvm_from_worktree(",".join(langs))
        return
    if mode == "release" and os.path.realpath(release_worktree) != os.path.realpath(
        PROJECT_ROOT_DIR
    ):
        raise RuntimeError(f"Invalid {JVM_RELEASE_WORKTREE_ENV}: {release_worktree}")
    _require_publication_authority(mode)
    _ensure_openjdk25()
    if "java" not in langs:
        if mode == "release":
            _run_release_cmd(JAVA_RELEASE_PACKAGE_CMD, "java")
            verify_java_artifacts()
        else:
            _verify_fory_core_mr_jar()
    for lang in langs:
        if lang == "java":
            _publish_java(mode)
        elif lang == "kotlin":
            _publish_kotlin(mode)
        elif lang == "scala":
            _publish_scala(mode)
        else:
            raise NotImplementedError(f"Unsupported JVM release language: {lang}")


def close_jvm_staging(
    v,
    rc_tag,
    java_kotlin_staging_id,
    scala_staging_id,
    verify_only=False,
):
    """Close and verify the two Nexus repositories created by publish_jvm."""
    _check_release_version(v)
    if not re.fullmatch(r"\d+\.\d+\.\d+", v):
        raise ValueError(f"Invalid final release version: {v}")
    if not re.fullmatch(rf"v{re.escape(v)}-rc\d+", rc_tag):
        raise ValueError(f"RC tag {rc_tag} does not match release version {v}")
    staging_ids = [java_kotlin_staging_id, scala_staging_id]
    for staging_id in staging_ids:
        if not re.fullmatch(r"orgapachefory-\d+", staging_id):
            raise ValueError(f"Invalid Apache Fory staging repository ID: {staging_id}")
    if len(set(staging_ids)) != len(staging_ids):
        raise ValueError("Java/Kotlin and Scala staging repository IDs must differ")

    authorization = _nexus_authorization()
    expected_state = "closed" if verify_only else "open"
    states = _nexus_states(staging_ids, authorization)
    _require_nexus_state(states, expected_state)
    if not verify_only:
        payload = {
            "data": {
                "stagedRepositoryIds": staging_ids,
                "description": f"Close Apache Fory {rc_tag} staging repositories",
            }
        }
        status, _ = _nexus_request(
            "/service/local/staging/bulk/close",
            authorization,
            method="POST",
            payload=payload,
        )
        if status != 201:
            raise RuntimeError(f"Nexus bulk close returned HTTP {status}, expected 201")
        logger.info("Submitted one Nexus bulk close request for %s", staging_ids)
        _wait_for_nexus_close(staging_ids, authorization)

    _verify_nexus_downloads(v, java_kotlin_staging_id, scala_staging_id)


def _nexus_authorization():
    username = os.environ.get("NEXUS_USERNAME")
    password = os.environ.get("NEXUS_PASSWORD")
    if not username or not password:
        raise RuntimeError(
            "NEXUS_USERNAME and NEXUS_PASSWORD are required for Nexus staging"
        )
    credentials = base64.b64encode(f"{username}:{password}".encode()).decode()
    return f"Basic {credentials}"


def _nexus_request(path, authorization, method="GET", payload=None):
    headers = {
        "Accept": "application/json",
        "Authorization": authorization,
        "User-Agent": "apache-fory-release-helper/1",
    }
    body = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        body = json.dumps(payload).encode()
    request = urllib.request.Request(
        f"{NEXUS_BASE_URL}{path}",
        data=body,
        headers=headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(request, timeout=NEXUS_TIMEOUT_SECONDS) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as exc:
        detail = exc.read(4096).decode(errors="replace").strip()
        raise RuntimeError(
            f"Nexus {method} {path} failed with HTTP {exc.code}: {detail}"
        ) from None
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Nexus {method} {path} failed: {exc.reason}") from None


def _nexus_states(staging_ids, authorization):
    status, body = _nexus_request(
        "/service/local/staging/profile_repositories", authorization
    )
    if status != 200:
        raise RuntimeError(
            f"Nexus repository list returned HTTP {status}, expected 200"
        )
    try:
        payload = json.loads(body)
    except json.JSONDecodeError as exc:
        raise RuntimeError(f"Invalid Nexus repository-list response: {exc}") from None
    data = payload.get("data") if isinstance(payload, dict) else None
    if not isinstance(data, list):
        raise RuntimeError("Nexus repository-list response has no data list")
    repositories = {
        item.get("repositoryId"): item for item in data if isinstance(item, dict)
    }
    missing = [
        staging_id for staging_id in staging_ids if staging_id not in repositories
    ]
    if missing:
        raise RuntimeError(f"Missing Nexus staging repositories: {missing}")
    states = {
        staging_id: repositories[staging_id].get("type") for staging_id in staging_ids
    }
    for staging_id, state in states.items():
        logger.info("Nexus staging repository %s: %s", staging_id, state)
    return states


def _require_nexus_state(states, expected_state):
    unexpected = {
        staging_id: state
        for staging_id, state in states.items()
        if state != expected_state
    }
    if unexpected:
        raise RuntimeError(
            f"Nexus repositories must be {expected_state}; found {unexpected}"
        )


def _wait_for_nexus_close(staging_ids, authorization):
    for attempt in range(1, NEXUS_CLOSE_ATTEMPTS + 1):
        states = _nexus_states(staging_ids, authorization)
        if all(state == "closed" for state in states.values()):
            return
        if attempt < NEXUS_CLOSE_ATTEMPTS:
            time.sleep(NEXUS_CLOSE_INTERVAL_SECONDS)
    _log_nexus_activity(staging_ids, authorization)
    raise RuntimeError(
        f"Nexus repositories did not close after {NEXUS_CLOSE_ATTEMPTS} checks"
    )


def _log_nexus_activity(staging_ids, authorization):
    for staging_id in staging_ids:
        path = f"/service/local/staging/repository/{staging_id}/activity"
        try:
            status, body = _nexus_request(path, authorization)
            if status != 200:
                logger.error(
                    "Nexus activity for %s returned HTTP %s", staging_id, status
                )
                continue
            try:
                activity = json.dumps(json.loads(body), indent=2, sort_keys=True)
            except json.JSONDecodeError:
                activity = body[:8192].decode(errors="replace")
            logger.error("Nexus activity for %s:\n%s", staging_id, activity)
        except RuntimeError as exc:
            logger.error("Unable to read Nexus activity for %s: %s", staging_id, exc)


def _verify_nexus_downloads(v, java_kotlin_staging_id, scala_staging_id):
    java_kotlin_url = f"{NEXUS_BASE_URL}/content/repositories/{java_kotlin_staging_id}/"
    scala_url = f"{NEXUS_BASE_URL}/content/repositories/{scala_staging_id}/"
    artifact_urls = [
        java_kotlin_url,
        f"{java_kotlin_url}org/apache/fory/fory-core/{v}/fory-core-{v}.jar",
        f"{java_kotlin_url}org/apache/fory/fory-kotlin/{v}/fory-kotlin-{v}.jar",
        scala_url,
        f"{scala_url}org/apache/fory/fory-scala_2.13/{v}/fory-scala_2.13-{v}.jar",
        f"{scala_url}org/apache/fory/fory-json-scala_3/{v}/fory-json-scala_3-{v}.jar",
    ]
    for url in artifact_urls:
        request = urllib.request.Request(
            url, headers={"User-Agent": "apache-fory-release-helper/1"}
        )
        try:
            with urllib.request.urlopen(
                request, timeout=NEXUS_TIMEOUT_SECONDS
            ) as response:
                status = response.status
        except urllib.error.HTTPError as exc:
            status = exc.code
        except urllib.error.URLError as exc:
            raise RuntimeError(
                f"Anonymous Nexus download failed for {url}: {exc.reason}"
            )
        logger.info("Anonymous Nexus download HTTP %s: %s", status, url)
        if status != 200:
            raise RuntimeError(
                f"Anonymous Nexus download returned HTTP {status}: {url}"
            )


def _jvm_release_langs(languages):
    if languages in (None, "", "all"):
        return list(JVM_RELEASE_LANGS)
    selected = {lang.strip() for lang in languages.split(",") if lang.strip()}
    if not selected:
        raise ValueError("JVM release language selection is empty")
    unsupported = sorted(selected.difference(JVM_RELEASE_LANGS))
    if unsupported:
        raise ValueError(f"Unsupported JVM release language(s): {unsupported}")
    return [lang for lang in JVM_RELEASE_LANGS if lang in selected]


def _require_publication_authority(mode):
    if mode not in JVM_PUBLICATION_MODES:
        raise ValueError(f"Unsupported JVM publication mode: {mode}")
    if mode == "release" and not _has_gpg_secret_key():
        raise RuntimeError("JVM release publication requires a GPG secret key")


def _publish_jvm_from_worktree(languages):
    source_root = os.path.abspath(PROJECT_ROOT_DIR)
    revision = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=source_root, text=True
    ).strip()
    with tempfile.TemporaryDirectory(prefix="fory-jvm-release-") as temp_dir:
        worktree = os.path.join(temp_dir, "worktree")
        subprocess.check_call(
            ["git", "worktree", "add", "--detach", worktree, revision],
            cwd=source_root,
        )
        try:
            logger.info("Publishing JVM release from temporary worktree %s", worktree)
            env = os.environ.copy()
            env[JVM_RELEASE_WORKTREE_ENV] = worktree
            subprocess.check_call(
                [
                    sys.executable,
                    os.path.join(worktree, "ci", "release.py"),
                    "publish_jvm",
                    "-l",
                    languages,
                    "--mode",
                    "release",
                ],
                cwd=worktree,
                env=env,
            )
        finally:
            subprocess.check_call(
                ["git", "worktree", "remove", "--force", worktree],
                cwd=source_root,
            )


def _has_gpg_secret_key():
    gpg = shutil.which("gpg")
    if not gpg:
        return False
    result = subprocess.run(
        [gpg, "--batch", "--list-secret-keys", "--with-colons"],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        check=False,
    )
    return result.returncode == 0 and any(
        line.startswith("sec:") for line in result.stdout.splitlines()
    )


def _publish_java(mode="release"):
    if mode == "release":
        _run_release_cmd(JAVA_RELEASE_PACKAGE_CMD, "java")
        verify_java_artifacts()
        _clean_release_path("java")
        _run_release_cmd(JAVA_RELEASE_DEPLOY_CMD, "java")
        verify_java_artifacts()
    else:
        _run_release_cmd(MAVEN_SNAPSHOT_CMD, "java")
        _verify_fory_core_mr_jar()


def _publish_kotlin(mode="release"):
    if mode == "release":
        package_command = KOTLIN_RELEASE_PACKAGE_CMD
        deploy_command = KOTLIN_RELEASE_DEPLOY_CMD
    else:
        package_command = KOTLIN_SNAPSHOT_PACKAGE_CMD
        deploy_command = KOTLIN_SNAPSHOT_DEPLOY_CMD
    _run_release_cmd(package_command, "kotlin")
    verify_kotlin_artifacts()
    if mode == "release":
        _clean_release_path("kotlin")
    _run_release_cmd(deploy_command, "kotlin")
    if mode == "release":
        verify_kotlin_artifacts()


def _publish_scala(mode="release"):
    # GitHub Actions exposes Maven's NEXUS_* variables, while SBT consumes
    # SONATYPE_* directly. Forward only present values so local SBT credentials
    # continue to work without requiring publication environment variables.
    for nexus_name, sonatype_name in (
        ("NEXUS_USERNAME", "SONATYPE_USERNAME"),
        ("NEXUS_PASSWORD", "SONATYPE_PASSWORD"),
    ):
        value = os.environ.get(nexus_name)
        if value:
            os.environ.setdefault(sonatype_name, value)
    commands = SCALA_RELEASE_COMMANDS if mode == "release" else SCALA_SNAPSHOT_COMMANDS
    for command in commands:
        _run_release_cmd(command, "scala")


def _run_release_cmd(command, path):
    cwd = os.path.join(PROJECT_ROOT_DIR, path)
    logger.info("Run release command in %s: %s", cwd, command)
    subprocess.check_call(command, cwd=cwd, shell=True)


def _clean_release_path(path):
    cwd = os.path.join(PROJECT_ROOT_DIR, path)
    logger.info("Restore temporary release path before deploy: %s", cwd)
    subprocess.check_call(["git", "clean", "-ffdx", "."], cwd=cwd)


def _ensure_openjdk25():
    runtime = _read_java_runtime(_java_tool("java"))
    # The JDK25 multi-release Maven profile is JVM-activated; a lower release
    # JDK silently publishes a jar without the required JDK25 overlay.
    if runtime and _is_openjdk25(runtime):
        _export_java_home(runtime["props"]["java.home"])
        logger.info("Using OpenJDK 25 release runtime: %s", os.environ["JAVA_HOME"])
        return
    if sys.platform != "darwin":
        raise RuntimeError(
            "JVM releases must run with OpenJDK 25. "
            "Install OpenJDK 25 and set JAVA_HOME/PATH before running release.py. "
            f"Found {_java_runtime_summary(runtime)}."
        )
    java_home = _homebrew_openjdk25_home()
    _export_java_home(java_home)
    runtime = _read_java_runtime(_java_tool("java"))
    if not runtime or not _is_openjdk25(runtime):
        raise RuntimeError(
            "JVM releases must run with OpenJDK 25. "
            f"Found {_java_runtime_summary(runtime)} after setting "
            f"JAVA_HOME={java_home}."
        )
    logger.info("Using OpenJDK 25 release runtime: %s", os.environ["JAVA_HOME"])


def _homebrew_openjdk25_home():
    brew = _brew_command()
    if not brew:
        raise RuntimeError(
            "Cannot install OpenJDK 25 automatically because Homebrew was not found."
        )
    prefix = _homebrew_prefix(brew, HOMEBREW_OPENJDK25)
    if not prefix:
        logger.info("Installing %s with Homebrew", HOMEBREW_OPENJDK25)
        subprocess.check_call([brew, "install", HOMEBREW_OPENJDK25])
        prefix = _homebrew_prefix(brew, HOMEBREW_OPENJDK25)
    if not prefix:
        raise RuntimeError(f"Cannot locate Homebrew formula {HOMEBREW_OPENJDK25}")
    for candidate in [
        os.path.join(prefix, "libexec", "openjdk.jdk", "Contents", "Home"),
        prefix,
    ]:
        if os.path.exists(os.path.join(candidate, "bin", "java")):
            return candidate
    raise RuntimeError(f"Cannot find a java executable under {prefix}")


def _brew_command():
    brew = shutil.which("brew")
    if brew:
        return brew
    for brew in HOMEBREW_BREW_PATHS:
        if os.path.exists(brew):
            return brew
    return None


def _homebrew_prefix(brew, formula):
    proc = subprocess.run(
        [brew, "--prefix", formula],
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        return None
    return proc.stdout.strip()


def _export_java_home(java_home):
    os.environ["JAVA_HOME"] = java_home
    java_bin = os.path.join(java_home, "bin")
    path_entries = [
        entry for entry in os.environ.get("PATH", "").split(os.pathsep) if entry
    ]
    path_entries = [entry for entry in path_entries if entry != java_bin]
    os.environ["PATH"] = os.pathsep.join([java_bin] + path_entries)


def _read_java_runtime(java_cmd):
    try:
        proc = subprocess.run(
            [java_cmd, "-XshowSettings:properties", "-version"],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            check=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return None
    return {"props": _java_props(proc.stdout), "output": proc.stdout}


def _is_openjdk25(runtime):
    props = runtime["props"]
    spec_version = props.get("java.specification.version", "")
    runtime_name = props.get("java.runtime.name", "")
    vm_name = props.get("java.vm.name", "")
    is_openjdk = "openjdk" in f"{runtime_name} {vm_name} {runtime['output']}".lower()
    return spec_version == "25" and is_openjdk


def _java_runtime_summary(runtime):
    if not runtime:
        return "no Java runtime"
    props = runtime["props"]
    return (
        f"java.home={props.get('java.home', '')}, "
        f"java.version={props.get('java.version', '')}, "
        f"java.specification.version={props.get('java.specification.version', '')}, "
        f"java.runtime.name={props.get('java.runtime.name', '')}, "
        f"java.vm.name={props.get('java.vm.name', '')}"
    )


def _java_tool(tool):
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        return os.path.join(java_home, "bin", tool)
    return tool


def _java_props(output):
    props = {}
    for line in output.splitlines():
        match = re.match(r"\s*([^=]+?)\s*=\s*(.*)\s*$", line)
        if match:
            props[match.group(1)] = match.group(2)
    return props


def _verify_fory_core_mr_jar():
    jar_path = _fory_core_jar_path()
    sources_jar_path = _fory_core_jar_path("sources")
    if not os.path.exists(jar_path):
        raise FileNotFoundError(
            f"Missing fory-core release jar: {jar_path}. "
            "Run the Java release before publishing Kotlin or Scala artifacts."
        )
    if not os.path.exists(sources_jar_path):
        raise FileNotFoundError(
            f"Missing fory-core release sources jar: {sources_jar_path}. "
            "Run the Java release before publishing Kotlin or Scala artifacts."
        )
    with zipfile.ZipFile(jar_path) as jar:
        names = jar.namelist()
        manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8")
        if names.count(FORY_CORE_NATIVE_IMAGE_PROPERTIES) != 1:
            raise RuntimeError(
                f"{jar_path} must contain exactly one "
                f"{FORY_CORE_NATIVE_IMAGE_PROPERTIES}"
            )
        native_image_properties = jar.read(FORY_CORE_NATIVE_IMAGE_PROPERTIES).decode(
            "utf-8"
        )
    if "Multi-Release: true" not in manifest:
        raise RuntimeError(f"{jar_path} is missing manifest Multi-Release: true")
    if "Build-Jdk-Spec: 25" not in manifest:
        raise RuntimeError(f"{jar_path} was not built with JDK 25")
    if FORY_CORE_JDK25_ENTRY not in names:
        raise RuntimeError(f"{jar_path} is missing {FORY_CORE_JDK25_ENTRY}")
    feature_entries = [
        name
        for name in names
        if name.endswith("org/apache/fory/platform/ForyGraalVMFeature.class")
    ]
    if feature_entries != [FORY_CORE_FEATURE_ENTRY]:
        raise RuntimeError(
            f"{jar_path} must contain only the MR17 GraalVM Feature; "
            f"found {feature_entries}"
        )
    feature_service_entries = [
        name for name in names if name.endswith(GRAALVM_FEATURE_SERVICE_ENTRY)
    ]
    if feature_service_entries:
        raise RuntimeError(
            f"{jar_path} contains obsolete Feature service metadata: "
            f"{feature_service_entries}"
        )
    feature_options = re.findall(r"--features=[^\s\\]+", native_image_properties)
    expected_feature_option = f"--features={FORY_CORE_FEATURE}"
    if feature_options != [expected_feature_option]:
        raise RuntimeError(
            f"{FORY_CORE_NATIVE_IMAGE_PROPERTIES} must contain exactly "
            f"{expected_feature_option}; found {feature_options}"
        )
    if "--initialize-at-build-time=" not in native_image_properties:
        raise RuntimeError(
            f"{FORY_CORE_NATIVE_IMAGE_PROPERTIES} is missing --initialize-at-build-time"
        )
    with zipfile.ZipFile(sources_jar_path) as sources_jar:
        source_names = sources_jar.namelist()
    feature_source_entries = [
        name
        for name in source_names
        if name.endswith("org/apache/fory/platform/ForyGraalVMFeature.java")
    ]
    if feature_source_entries != [FORY_CORE_FEATURE_SOURCE_ENTRY]:
        raise RuntimeError(
            f"{sources_jar_path} must contain only the MR17 GraalVM Feature source; "
            f"found {feature_source_entries}"
        )
    javap = subprocess.run(
        [
            _java_tool("javap"),
            "--multi-release",
            "25",
            "-classpath",
            jar_path,
            "-p",
            FORY_CORE_ACCESSOR,
            FORY_CORE_FEATURE,
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        check=True,
    )
    if "java.lang.invoke.VarHandle" not in javap.stdout:
        raise RuntimeError(f"{FORY_CORE_ACCESSOR} is not the JDK25 VarHandle class")
    if "sun.misc.Unsafe" in javap.stdout:
        raise RuntimeError(f"{FORY_CORE_ACCESSOR} still exposes sun.misc.Unsafe")
    feature_declaration = rf"(?m)^final class {re.escape(FORY_CORE_FEATURE)}\b"
    if not re.search(feature_declaration, javap.stdout):
        raise RuntimeError(f"{FORY_CORE_FEATURE} must remain a non-public final class")
    logger.info(
        "Verified fory-core Multi-Release jars: %s, %s", jar_path, sources_jar_path
    )


def verify_java_artifacts():
    """Validate Java binary and source-release artifacts before deployment."""
    _verify_fory_core_mr_jar()
    version = _read_java_version()
    archive_path = os.path.join(
        PROJECT_ROOT_DIR,
        "java",
        "target",
        f"fory-parent-{version}-source-release.zip",
    )
    if not os.path.exists(archive_path):
        raise FileNotFoundError(f"Missing Java source-release artifact: {archive_path}")

    root = f"fory-parent-{version}/"
    with zipfile.ZipFile(archive_path) as archive:
        archive_files = [
            entry.filename for entry in archive.infolist() if not entry.is_dir()
        ]
        outside_root = [name for name in archive_files if not name.startswith(root)]
        if outside_root:
            raise RuntimeError(
                f"{archive_path} contains files outside {root}: {outside_root}"
            )
        relative_files = [name[len(root) :] for name in archive_files]
        packaged_files = set()
        duplicate_files = set()
        for name in relative_files:
            if name in packaged_files:
                duplicate_files.add(name)
            packaged_files.add(name)
        if duplicate_files:
            raise RuntimeError(
                f"{archive_path} contains duplicate files: {sorted(duplicate_files)}"
            )
        license_text = archive.read(f"{root}LICENSE").decode("utf-8")

    tracked = subprocess.run(
        ["git", "ls-files", "--", "java"],
        cwd=PROJECT_ROOT_DIR,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=True,
    ).stdout.splitlines()
    expected_files = {path[len("java/") :] for path in tracked}
    expected_files.update({"DEPENDENCIES", "LICENSE", "NOTICE"})
    unexpected = sorted(packaged_files.difference(expected_files))
    missing = sorted(expected_files.difference(packaged_files))
    if unexpected or missing:
        raise RuntimeError(
            f"{archive_path} does not match the tracked Java source tree: "
            f"unexpected={unexpected}, missing={missing}"
        )

    license_path = os.path.join(PROJECT_ROOT_DIR, "java", "LICENSE")
    with open(license_path, "r", encoding="utf-8") as license_file:
        expected_license = license_file.read()
    if license_text != expected_license:
        raise RuntimeError(f"{archive_path} LICENSE does not match {license_path}")
    logger.info(
        "Verified Java source release contains %d tracked and legal files: %s",
        len(packaged_files),
        archive_path,
    )


def verify_kotlin_artifacts():
    """Open every public Kotlin artifact and validate its publication surface."""
    version = _read_kotlin_version()
    for artifact in KOTLIN_PUBLIC_ARTIFACTS:
        module_dir = os.path.join(PROJECT_ROOT_DIR, "kotlin", artifact)
        target_dir = os.path.join(module_dir, "target")
        binary_path = os.path.join(target_dir, f"{artifact}-{version}.jar")
        sources_path = os.path.join(target_dir, f"{artifact}-{version}-sources.jar")
        javadoc_path = os.path.join(target_dir, f"{artifact}-{version}-javadoc.jar")
        pom_path = os.path.join(module_dir, "pom.xml")
        for path in (binary_path, sources_path, javadoc_path, pom_path):
            if not os.path.exists(path):
                raise FileNotFoundError(f"Missing Kotlin publication artifact: {path}")
        ET.parse(pom_path)
        with zipfile.ZipFile(binary_path) as binary:
            binary_names = binary.namelist()
            for required in (
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/DEPENDENCIES",
            ):
                if required not in binary_names:
                    raise RuntimeError(f"{binary_path} is missing {required}")
            if not any(name.endswith(".class") for name in binary_names):
                raise RuntimeError(f"{binary_path} contains no classes")
            for name in binary_names:
                if not name.endswith(".class"):
                    continue
                class_bytes = binary.read(name)
                major_version = int.from_bytes(class_bytes[6:8], "big")
                if major_version != 52:
                    raise RuntimeError(
                        f"{binary_path}!/{name} is JVM class version {major_version}, expected 52"
                    )
            module_name = KOTLIN_MODULE_NAMES.get(artifact)
            if module_name:
                manifest = binary.read("META-INF/MANIFEST.MF").decode("utf-8")
                if f"Automatic-Module-Name: {module_name}" not in manifest:
                    raise RuntimeError(
                        f"{binary_path} is missing Automatic-Module-Name: {module_name}"
                    )
            provider = KOTLIN_SERVICE_PROVIDERS.get(artifact)
            if provider:
                service_path = (
                    "META-INF/services/"
                    "com.google.devtools.ksp.processing.SymbolProcessorProvider"
                )
                if service_path not in binary_names:
                    raise RuntimeError(f"{binary_path} is missing {service_path}")
                providers = binary.read(service_path).decode("utf-8").splitlines()
                providers = [line.strip() for line in providers if line.strip()]
                if providers != [provider]:
                    raise RuntimeError(
                        f"{binary_path}!/{service_path} must contain only {provider}; "
                        f"found {providers}"
                    )
        with zipfile.ZipFile(sources_path) as sources:
            source_names = set(sources.namelist())
        expected_sources = set()
        for source_root in ("src/main/kotlin", "src/main/java"):
            root = os.path.join(module_dir, source_root)
            if not os.path.isdir(root):
                continue
            for directory, _, files in os.walk(root):
                for filename in files:
                    if not filename.endswith((".kt", ".java")):
                        continue
                    expected_sources.add(
                        os.path.relpath(
                            os.path.join(directory, filename), root
                        ).replace(os.sep, "/")
                    )
        if not expected_sources:
            raise RuntimeError(
                f"{module_dir} contains no authored Kotlin or Java sources"
            )
        packaged_sources = {
            name for name in source_names if name.endswith((".kt", ".java"))
        }
        if packaged_sources != expected_sources:
            raise RuntimeError(
                f"{sources_path} has an incomplete or stale source surface: "
                f"{sorted(packaged_sources ^ expected_sources)}"
            )
        with zipfile.ZipFile(javadoc_path) as javadocs:
            javadoc_names = javadocs.namelist()
        for required in ("META-INF/LICENSE", "META-INF/NOTICE"):
            if javadoc_names.count(required) != 1:
                raise RuntimeError(
                    f"{javadoc_path} must contain exactly one {required}"
                )
        if "index.html" not in javadoc_names or not any(
            name.endswith(".html") and name != "index.html" for name in javadoc_names
        ):
            raise RuntimeError(f"{javadoc_path} contains no Kotlin API documentation")
        logger.info("Verified Kotlin publication artifacts for %s", artifact)


def _fory_core_jar_path(classifier=None):
    version = _read_java_version()
    classifier_suffix = f"-{classifier}" if classifier else ""
    return os.path.join(
        PROJECT_ROOT_DIR,
        "java",
        "fory-core",
        "target",
        f"fory-core-{version}{classifier_suffix}.jar",
    )


def _read_java_version():
    pom = os.path.join(PROJECT_ROOT_DIR, "java", "pom.xml")
    root = ET.parse(pom).getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    artifact = root.findtext("m:artifactId", namespaces=namespace)
    packaging = root.findtext("m:packaging", namespaces=namespace)
    version = root.findtext("m:version", namespaces=namespace)
    if artifact != "fory-parent" or packaging != "pom" or not version:
        raise ValueError("Cannot find java/fory parent version")
    return version


def _read_kotlin_version():
    pom = os.path.join(PROJECT_ROOT_DIR, "kotlin", "pom.xml")
    root = ET.parse(pom).getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    artifact = root.findtext("m:artifactId", namespaces=namespace)
    packaging = root.findtext("m:packaging", namespaces=namespace)
    version = root.findtext("m:version", namespaces=namespace)
    if artifact != "fory-kotlin-parent" or packaging != "pom" or not version:
        raise ValueError("Cannot find kotlin/fory-kotlin-parent version")
    return version


def bump_version(**kwargs):
    new_version = kwargs["version"]
    langs = kwargs["l"]
    all_langs = langs == "all"
    if langs == "all":
        langs = [
            "java",
            "python",
            "javascript",
            "scala",
            "rust",
            "kotlin",
            "cpp",
            "go",
            "dart",
            "csharp",
            "swift",
            "compiler",
        ]
    else:
        langs = langs.split(",")
    for lang in langs:
        if lang == "java":
            bump_java_version(_normalize_java_version(new_version))
        elif lang == "scala":
            _bump_version(
                "scala",
                "build.sbt",
                _normalize_java_version(new_version),
                _update_scala_version,
            )
        elif lang == "kotlin":
            bump_kotlin_version(_normalize_java_version(new_version))
        elif lang == "rust":
            bump_rust_version(new_version, kwargs.get("release_version"))
        elif lang == "python":
            bump_python_version(new_version)
        elif lang == "javascript":
            js_version = _normalize_js_version(new_version)
            _bump_version(
                "javascript/packages/core",
                "package.json",
                js_version,
                _update_js_version,
            )
            _bump_version(
                "javascript/packages/hps",
                "package.json",
                js_version,
                _update_js_version,
            )
            _bump_version(
                "integration_tests/idl_tests/javascript",
                "package.json",
                js_version,
                _update_js_version,
            )
            bump_js_lock_version(js_version)
        elif lang == "cpp":
            bump_cpp_version(new_version)
        elif lang == "go":
            bump_go_version(new_version)
        elif lang == "dart":
            bump_dart_version(new_version)
        elif lang == "csharp":
            bump_csharp_version(new_version)
        elif lang == "swift":
            bump_swift_version(new_version)
        elif lang == "compiler":
            bump_compiler_version(new_version)
        else:
            raise NotImplementedError(f"Unsupported {lang}")
    if all_langs:
        bump_release_doc_versions(new_version, kwargs.get("release_version"))


def _bump_version(path, file, new_version, func):
    os.chdir(os.path.join(PROJECT_ROOT_DIR, path))
    with open(file, "r") as f:
        lines = f.readlines()
    lines = func(lines, new_version) or lines
    text = "".join(lines)
    with open(file, "w") as f:
        f.write(text)


def bump_java_version(new_version):
    new_version = _normalize_java_version(new_version)
    for p in [
        "integration_tests/graalvm_tests",
        "integration_tests/grpc_tests/java",
        "integration_tests/jdk_compatibility_tests",
        "integration_tests/jpms_tests",
        "integration_tests/idl_tests/java",
        "benchmarks/java",
        "java/fory-core",
        "java/fory-json",
        "java/fory-format",
        "java/fory-extensions",
        "java/fory-test-core",
        "java/fory-testsuite",
        "java/fory-latest-jdk-tests",
        "java/fory-annotation-processor",
    ]:
        _bump_version(p, "pom.xml", new_version, _update_pom_parent_version)
    for file in ["build.gradle", "README.md"]:
        _bump_version(
            "integration_tests/android_tests",
            file,
            new_version,
            _update_android_tests_dependency_version,
        )
    _bump_version(
        "benchmarks/java25",
        "pom.xml",
        new_version,
        _update_java25_benchmark_version,
    )
    # mvn versions:set too slow
    # os.chdir(os.path.join(PROJECT_ROOT_DIR, "java"))
    # subprocess.check_output(
    #     f"mvn versions:set -DnewVersion={new_version}",
    #     shell=True,
    #     universal_newlines=True,
    # )
    _bump_version("java", "pom.xml", new_version, _update_parent_pom_version)


def bump_python_version(new_version):
    _bump_version("python/pyfory", "__init__.py", new_version, _update_python_version)
    _bump_version(
        "integration_tests/idl_tests/python",
        "pyproject.toml",
        new_version,
        _update_pyproject_version,
    )
    _bump_version(
        "integration_tests/grpc_tests/python",
        "pyproject.toml",
        new_version,
        _update_pyproject_version,
    )


def bump_rust_version(new_version, release_version=None):
    rust_version = _normalize_rust_version(new_version)
    release_version = _resolve_release_doc_version(new_version, release_version)
    _bump_version("rust", "Cargo.toml", rust_version, _update_rust_version)
    _bump_version(
        "benchmarks/rust",
        "Cargo.toml",
        rust_version,
        _update_rust_version,
    )
    _bump_version(
        "integration_tests/idl_tests/rust",
        "Cargo.toml",
        rust_version,
        _update_cargo_package_version,
    )
    _bump_version(
        "integration_tests/grpc_tests/rust",
        "Cargo.toml",
        rust_version,
        _update_rust_version,
    )
    _bump_version(
        "integration_tests/idl_tests/rust",
        "Cargo.lock",
        rust_version,
        _update_cargo_lock_version,
    )
    _bump_version(
        "rust/fory/src",
        "lib.rs",
        release_version or rust_version,
        _update_rust_doc_version,
    )


def bump_kotlin_version(new_version):
    _bump_version("kotlin", "pom.xml", new_version, _update_kotlin_version)
    for p in [
        "kotlin/fory-kotlin",
        "kotlin/fory-kotlin-ksp",
        "kotlin/fory-json-kotlin",
        "kotlin/fory-json-kotlin-ksp",
        "kotlin/fory-kotlin-tests",
        "integration_tests/kotlin_json_corpus",
        "integration_tests/graalvm_kotlin_tests",
        "integration_tests/grpc_tests/kotlin",
        "integration_tests/idl_tests/kotlin",
    ]:
        _bump_version(p, "pom.xml", new_version, _update_pom_parent_version)
    for file in ["build.gradle", "README.md"]:
        _bump_version(
            "integration_tests/android_tests",
            file,
            new_version,
            _update_android_kotlin_version,
        )
    _bump_version(
        "benchmarks/kotlin",
        "gradle.properties",
        new_version,
        _update_kotlin_benchmark_version,
    )
    for path, file in [
        ("kotlin/fory-json-kotlin", "README.md"),
        ("kotlin/fory-json-kotlin-ksp", "README.md"),
        ("docs/json", "kotlin.md"),
        ("docs/json", "getting-started.md"),
        ("docs/start", "kotlin.md"),
    ]:
        _bump_version(path, file, new_version, _update_release_doc_lines)


def bump_cpp_version(new_version):
    for p in [
        "cpp",
        "benchmarks/cpp",
        "integration_tests/idl_tests/cpp",
    ]:
        _bump_version(p, "CMakeLists.txt", new_version, _update_cmake_project_version)
    _bump_version("", "MODULE.bazel", new_version, _update_bazel_module_version)


def bump_go_version(new_version):
    for p in [
        "benchmarks/go",
        "integration_tests/idl_tests/go",
    ]:
        _bump_version(p, "go.mod", new_version, _update_go_mod_version)


def bump_dart_version(new_version):
    release_version = _resolve_release_doc_version(new_version)
    for p in [
        "dart",
        "dart/packages/fory",
        "dart/packages/fory-test",
        "integration_tests/idl_tests/dart",
    ]:
        _bump_version(p, "pubspec.yaml", new_version, _update_pubspec_version)
    _bump_version(
        "dart/packages/fory",
        "README.md",
        release_version or new_version,
        _update_dart_readme_dependency_version,
    )
    if release_version:
        bump_dart_changelogs(new_version, release_version)


def bump_compiler_version(new_version):
    _bump_version("compiler", "pyproject.toml", new_version, _update_pyproject_version)
    _bump_version(
        "compiler/fory_compiler",
        "__init__.py",
        new_version,
        _update_python_version,
    )


def bump_csharp_version(new_version):
    release_version = _resolve_release_doc_version(new_version)
    _bump_version(
        "csharp",
        "Directory.Build.props",
        new_version,
        _update_csharp_props_version,
    )
    _bump_version(
        "csharp",
        "README.md",
        release_version or new_version,
        _update_csharp_readme_package_version,
    )
    _bump_version(
        "docs/object-serialization/csharp",
        "index.md",
        release_version or new_version,
        _update_csharp_readme_package_version,
    )


def bump_swift_version(new_version):
    release_version = _resolve_release_doc_version(new_version)
    _bump_version(
        "swift",
        "README.md",
        release_version or new_version,
        _update_swift_readme_dependency_version,
    )


def bump_js_lock_version(new_version):
    package_lock = os.path.join(PROJECT_ROOT_DIR, "javascript", "package-lock.json")
    with open(package_lock, "r") as f:
        lock = json.load(f)
    packages = lock.get("packages", {})
    for package_path in ["packages/core", "packages/hps"]:
        package = packages.get(package_path)
        if package is None:
            raise ValueError(f"No {package_path} entry found in package-lock.json")
        package["version"] = new_version
    with open(package_lock, "w") as f:
        json.dump(lock, f, indent=2)
        f.write("\n")


def bump_dart_changelogs(new_version, release_version):
    dev_version = None if _is_release_version(new_version) else new_version.strip()
    for path, workspace in [
        ("dart/CHANGELOG.md", True),
        ("dart/packages/fory/CHANGELOG.md", False),
    ]:
        file = os.path.join(PROJECT_ROOT_DIR, path)
        with open(file, "r") as f:
            lines = f.readlines()
        lines = _update_dart_changelog(lines, release_version, workspace)
        if dev_version:
            lines = _update_dart_dev_changelog(
                lines, dev_version, release_version, workspace
            )
        with open(file, "w") as f:
            f.write("".join(lines))


def _update_pom_parent_version(lines, new_version):
    start_index, end_index = -1, -1
    for i, line in enumerate(lines):
        if "<parent>" in line:
            start_index = i
        if "</parent>" in line:
            end_index = i
            break
    assert start_index != -1
    assert end_index != -1
    for line_number in range(start_index, end_index):
        line = lines[line_number]
        if "version" in line:
            line = re.sub(
                r"(<version>)[^<>]+(</version>)", r"\g<1>" + new_version + r"\2", line
            )
            lines[line_number] = line


def _update_android_tests_dependency_version(lines, new_version):
    for index, line in enumerate(lines):
        lines[index] = re.sub(
            r"(org\.apache\.fory:fory-(?:core|json|annotation-processor):)[^'`)\s]+",
            r"\g<1>" + new_version,
            line,
        )
    return lines


def _update_android_kotlin_version(lines, new_version):
    for index, line in enumerate(lines):
        lines[index] = re.sub(
            r"(org\.apache\.fory:(?:fory-json-kotlin(?:-ksp)?|kotlin-json-corpus):)[^'`)\s]+",
            r"\g<1>" + new_version,
            line,
        )
    return lines


def _update_kotlin_benchmark_version(lines, new_version):
    for index, line in enumerate(lines):
        if line.startswith("foryVersion="):
            lines[index] = f"foryVersion={new_version}\n"
            return lines
    raise ValueError("No foryVersion entry found in Kotlin benchmark properties")


def _update_scala_version(lines, v):
    v = _normalize_java_version(v)
    for index, line in enumerate(lines):
        if "foryVersion = " in line:
            lines[index] = f'val foryVersion = "{v}"\n'
            break
    return lines


def _update_kotlin_version(lines, v):
    v = _normalize_java_version(v)
    return _update_pom_version(lines, v, "<artifactId>fory-kotlin-parent</artifactId>")


def _update_parent_pom_version(lines, v):
    return _update_pom_version(lines, v, "<packaging>pom</packaging>")


def _update_java25_benchmark_version(lines, v):
    return _update_pom_version(
        lines, v, "<artifactId>java25-memory-access-benchmark</artifactId>"
    )


def _update_pom_version(lines, v, prev):
    target_index = -1
    for index, line in enumerate(lines):
        if prev in line:
            target_index = index + 1
            break
    if target_index == -1:
        raise ValueError(f"Could not find POM version marker: {prev}")
    current_version_line = lines[target_index]
    # Find the start and end of the version number
    start = current_version_line.index("<version>") + len("<version>")
    end = current_version_line.index("</version>")
    # Replace the version number
    updated_version_line = current_version_line[:start] + v + current_version_line[end:]
    lines[target_index] = updated_version_line
    return lines


def _update_rust_version(lines, v):
    in_workspace_package = False
    in_workspace_dependencies = False
    for index, line in enumerate(lines):
        stripped = line.strip()
        if stripped == "[workspace.package]":
            in_workspace_package = True
            in_workspace_dependencies = False
            continue
        if stripped == "[workspace.dependencies]":
            in_workspace_dependencies = True
            in_workspace_package = False
            continue
        if stripped.startswith("[") and stripped.endswith("]"):
            in_workspace_package = False
            in_workspace_dependencies = False
        if in_workspace_package and stripped.startswith("version = "):
            lines[index] = f'version = "{v}"\n'
            continue
        if in_workspace_dependencies and re.match(r"\s*fory(-core|-derive)?\s*=", line):
            lines[index] = re.sub(
                r'(version\s*=\s*")([^"]+)(")',
                r"\g<1>" + v + r"\3",
                line,
            )
    return lines


def _update_python_version(lines, v: str):
    v = _normalize_python_version(v)
    for index, line in enumerate(lines):
        if "__version__ = " in line:
            lines[index] = f'__version__ = "{v}"\n'
            break


def _update_pyproject_version(lines, v: str):
    v = _normalize_python_version(v)
    in_project = False
    for index, line in enumerate(lines):
        stripped = line.strip()
        if stripped == "[project]":
            in_project = True
            continue
        if in_project and stripped.startswith("[") and stripped.endswith("]"):
            in_project = False
        if in_project and stripped.startswith("version ="):
            lines[index] = f'version = "{v}"\n'
            break
    return lines


def _update_cargo_package_version(lines, v: str):
    in_package = False
    for index, line in enumerate(lines):
        stripped = line.strip()
        if stripped == "[package]":
            in_package = True
            continue
        if in_package and stripped.startswith("[") and stripped.endswith("]"):
            in_package = False
        if in_package and stripped.startswith("version ="):
            lines[index] = f'version = "{v}"\n'
            break
    return lines


def _update_cargo_lock_version(lines, v: str):
    package_name = None
    local_packages = {"fory", "fory-core", "fory-derive", "idl_tests"}
    for index, line in enumerate(lines):
        name_match = re.match(r'^name = "([^"]+)"$', line.strip())
        if name_match:
            package_name = name_match.group(1)
            continue
        if package_name in local_packages and line.strip().startswith("version = "):
            lines[index] = f'version = "{v}"\n'
            package_name = None
    return lines


def _update_rust_doc_version(lines, v: str):
    for index, line in enumerate(lines):
        if re.match(r'^//!\s+fory\s*=\s*"', line):
            lines[index] = re.sub(r'"[^"]+"', f'"{v}"', line, count=1)
            break
    return lines


def _update_cmake_project_version(lines, v: str):
    cmake_version = _normalize_cmake_version(v)
    in_project = False
    for index, line in enumerate(lines):
        if re.search(r"^\s*project\(", line):
            in_project = True
        if in_project and "VERSION" in line:
            lines[index] = re.sub(
                r"(VERSION\s+)([0-9]+(?:\.[0-9]+){1,2})",
                r"\g<1>" + cmake_version,
                line,
            )
        if in_project and ")" in line:
            in_project = False
    return lines


def _update_bazel_module_version(lines, v: str):
    bazel_version = _normalize_cmake_version(v)
    in_module = False
    for index, line in enumerate(lines):
        if re.search(r"^\s*module\(", line):
            in_module = True
        if in_module and re.search(r"^\s*version\s*=", line):
            lines[index] = re.sub(
                r'(version\s*=\s*")[^"]+(")',
                r"\g<1>" + bazel_version + r"\2",
                line,
            )
            return lines
        if in_module and ")" in line:
            in_module = False
    raise ValueError("No MODULE.bazel module version found")


def _update_go_mod_version(lines, v: str):
    go_version = _normalize_go_version(v)
    for index, line in enumerate(lines):
        if "github.com/apache/fory/go/fory" not in line:
            continue
        lines[index] = re.sub(
            r"(github.com/apache/fory/go/fory\s+)(v[^\s]+)",
            r"\g<1>" + go_version,
            line,
        )
    return lines


def _update_pubspec_version(lines, v: str):
    for index, line in enumerate(lines):
        if re.match(r"^version\s*:", line):
            lines[index] = f"version: {v}\n"
            continue
        if re.match(r"^\s*fory\s*:\s+\S+", line):
            prefix = re.match(r"^(\s*fory\s*:)\s*.*", line)
            if prefix:
                lines[index] = f"{prefix.group(1)} {v}\n"
    return lines


def _update_dart_readme_dependency_version(lines, v: str):
    for index, line in enumerate(lines):
        if re.match(r"^\s*fory:\s*\^[^\s]+\s*$", line):
            lines[index] = f"  fory: ^{v}\n"
            return lines
    raise ValueError("No Dart README dependency snippet for fory found")


def _update_dart_changelog(lines, v: str, workspace=False):
    v = _strip_version_prefix(v)
    heading = f"## {v}\n"
    if workspace:
        body = [
            "\n",
            f"- Align the Dart workspace version with the Apache Fory {v} release.\n",
            "\n",
        ]
    else:
        body = ["\n", f"- Release Apache Fory Dart {v}.\n", "\n"]
    heading_pattern = re.compile(rf"^##\s+{re.escape(v)}(?:-[^\s]+)?\s*$")
    start_index = -1
    for index, line in enumerate(lines):
        if heading_pattern.match(line):
            if line == heading:
                return lines
            start_index = index
            break
    if start_index == -1:
        return [heading] + body + lines

    end_index = len(lines)
    for index in range(start_index + 1, len(lines)):
        if re.match(r"^##\s+", lines[index]):
            end_index = index
            break
    updated = list(lines)
    updated[start_index] = heading
    dev_cycle = re.compile(
        r"^- Start the next (?:Dart workspace )?development cycle after "
        r"the \S+ release\.\s*$"
    )
    for index in range(start_index + 1, end_index):
        if dev_cycle.match(updated[index]):
            updated[index] = body[1]
            break
    return updated


def _update_dart_dev_changelog(lines, v: str, release_version: str, workspace=False):
    heading = f"## {v}\n"
    if workspace:
        body = [
            "\n",
            (
                "- Start the next Dart workspace development cycle after the "
                f"{release_version} release.\n"
            ),
            "\n",
        ]
    else:
        body = [
            "\n",
            f"- Start the next development cycle after the {release_version} release.\n",
            "\n",
        ]
    for line in lines:
        if line == heading:
            return lines
    return [heading] + body + lines


def _update_csharp_props_version(lines, v: str):
    for index, line in enumerate(lines):
        if "<Version>" not in line:
            continue
        lines[index] = re.sub(
            r"(<Version>)[^<]+(</Version>)",
            r"\g<1>" + v + r"\2",
            line,
        )
        return lines
    raise ValueError("No <Version> element found in csharp/Directory.Build.props")


def _update_csharp_readme_package_version(lines, v: str):
    for index, line in enumerate(lines):
        if "PackageReference" not in line or "Apache.Fory" not in line:
            continue
        lines[index] = re.sub(
            r'(<PackageReference\s+Include="Apache\.Fory"\s+Version=")[^"]+(")',
            r"\g<1>" + v + r"\2",
            line,
        )
        return lines
    raise ValueError("No Apache.Fory PackageReference version snippet found")


def _update_swift_readme_dependency_version(lines, v: str):
    for index, line in enumerate(lines):
        if "https://github.com/apache/fory.git" not in line:
            continue
        lines[index] = re.sub(
            r'(\.package\(url:\s*"https://github\.com/apache/fory\.git",\s*from:\s*")[^"]+("\))',
            r"\g<1>" + v + r"\2",
            line,
        )
        return lines
    raise ValueError("No Swift Package dependency snippet for apache/fory.git found")


def bump_release_doc_versions(new_version: str, release_version: str | None = None):
    release_version = _resolve_release_doc_version(new_version, release_version)
    if not release_version:
        logger.info("Skip release documentation version update for %s", new_version)
        return
    for file in _release_doc_files():
        _update_release_doc_file(file, release_version)


def _resolve_release_doc_version(new_version: str, release_version: str | None = None):
    if release_version:
        release_version = _strip_version_prefix(release_version)
        if not _is_release_version(release_version):
            raise ValueError(
                f"Invalid release documentation version: {release_version}"
            )
        return release_version
    new_version = _strip_version_prefix(new_version)
    if _is_release_version(new_version):
        return new_version
    dot_dev_version = _dot_dev_release_version(new_version)
    if dot_dev_version:
        return dot_dev_version
    base_match = re.match(r"^(\d+)\.(\d+)\.(\d+)", new_version)
    if not base_match:
        return None
    major, minor, patch = [int(part) for part in base_match.groups()]
    if patch > 0:
        patch -= 1
    elif minor > 0:
        minor -= 1
    else:
        return None
    return f"{major}.{minor}.{patch}"


def _dot_dev_release_version(v: str):
    match = re.match(r"^(\d+\.\d+\.(\d+))\.dev\d*$", v, flags=re.IGNORECASE)
    if match and int(match.group(2)) > 0:
        return match.group(1)
    return None


def _release_doc_files():
    for root in RELEASE_DOC_ROOTS:
        path = os.path.join(PROJECT_ROOT_DIR, root)
        if os.path.isfile(path):
            yield path
            continue
        if not os.path.isdir(path):
            continue
        for dirpath, dirnames, filenames in os.walk(path):
            dirnames[:] = [
                name
                for name in dirnames
                if name not in {"build", "node_modules", "target"}
            ]
            for filename in sorted(filenames):
                if filename.endswith(RELEASE_DOC_EXTS):
                    yield os.path.join(dirpath, filename)


def _update_release_doc_file(file, release_version):
    with open(file, "r") as f:
        lines = f.readlines()
    updated = _update_release_doc_lines(lines, release_version)
    if updated == lines:
        return
    with open(file, "w") as f:
        f.write("".join(updated))


def _update_release_doc_lines(lines, release_version):
    updated = []
    in_dependency_block = False
    in_fory_dependency = False
    for line in lines:
        if "<dependency>" in line:
            in_dependency_block = True
            in_fory_dependency = False
        if in_dependency_block and "org.apache.fory" in line:
            in_fory_dependency = True
        if in_fory_dependency and "<version>" in line:
            line = re.sub(VERSION_PATTERN, release_version, line)
            in_fory_dependency = False
        else:
            line = _update_release_doc_line(line, release_version)
        if "</dependency>" in line:
            in_dependency_block = False
            in_fory_dependency = False
        updated.append(line)
    return updated


def _update_release_doc_line(line, release_version):
    scoped_patterns = (
        r"(\bpyfory(?:\[[^\]]+\])?==)" + VERSION_PATTERN,
        r"(\bgithub\.com/apache/fory/go/fory@v)" + VERSION_PATTERN,
        r"(@apache-fory/(?:core|hps)@)" + VERSION_PATTERN,
    )
    scoped_update = line
    has_scoped_dependency = False
    for pattern in scoped_patterns:
        if re.search(pattern, scoped_update):
            has_scoped_dependency = True
        scoped_update = re.sub(pattern, r"\g<1>" + release_version, scoped_update)
    if has_scoped_dependency:
        return scoped_update
    if not _is_release_doc_line(line):
        return line
    if "crates.io-v" in line:
        return re.sub(
            r"(crates\.io-v)" + VERSION_PATTERN + r"(?:-blue)?",
            r"\g<1>" + release_version + "-blue",
            line,
        )
    return re.sub(VERSION_PATTERN, release_version, line)


def _is_release_doc_line(line):
    return (
        "crates.io-v" in line
        or "https://crates.io/crates/fory" in line
        or "org.apache.fory" in line
        or "Apache.Fory" in line
        or "dart pub add fory" in line
        or re.search(r"^\s*fory\s*[:=]", line)
        or "https://github.com/apache/fory.git" in line
        or 'bazel_dep(name = "fory"' in line
        or 'git_override(module_name = "fory"' in line
        or re.search(r"\bGIT_TAG\s+v" + VERSION_PATTERN, line)
        or re.search(r'\bcommit\s*=\s*"v' + VERSION_PATTERN, line)
    )


def _strip_version_prefix(v: str) -> str:
    v = v.strip()
    if v.startswith("v"):
        return v[1:]
    return v


def _normalize_python_version(v: str) -> str:
    v = v.strip()
    v = re.sub(r"(?i)-?snapshot$", ".dev0", v)
    v = re.sub(r"(?i)-dev(\d+)$", r".dev\1", v)
    v = re.sub(r"(?i)-dev$", ".dev0", v)
    v = v.replace("-alpha", "a")
    v = v.replace("-beta", "b")
    v = v.replace("-rc", "rc")
    v = v.replace("-", "")
    return v


def _normalize_java_version(v: str) -> str:
    v = v.strip()
    if re.search(r"(?i)-snapshot$", v):
        return re.sub(r"(?i)-snapshot$", "-SNAPSHOT", v)
    if re.search(r"(?i)(\.dev\d*|-dev\d*)$", v):
        base = re.sub(r"(?i)(\.dev\d*|-dev\d*)$", "", v)
        return f"{base}-SNAPSHOT"
    return v


def _normalize_go_version(v: str) -> str:
    v = _strip_version_prefix(v)
    v = re.sub(r"-(alpha|beta|rc)(\d+)$", r"-\1.\2", v)
    if re.search(r"(?i)-(alpha|beta)\.0$", v):
        return f"v{v}"
    if re.search(r"(?i)-(alpha|beta|rc)\.\d+$", v):
        return f"v{v}"
    if re.search(r"(?i)-pre$", v):
        return f"v{v}"
    dev_match = re.search(r"(?i)(?:-dev|\.dev)(\d+)$", v)
    if dev_match:
        base = re.sub(r"(?i)(?:-dev|\.dev)\d+$", "", v)
        return f"v{base}-alpha.{dev_match.group(1)}"
    if re.search(r"(?i)(-snapshot|\.dev|-dev)$", v):
        base = re.sub(r"(?i)(-snapshot|\.dev|-dev)$", "", v)
        return f"v{base}-alpha.0"
    return f"v{v}"


def _normalize_cmake_version(v: str) -> str:
    v = _strip_version_prefix(v)
    v = re.split(r"[-+]", v, maxsplit=1)[0]
    return v


def _update_js_version(lines, v: str):
    v = _normalize_js_version(v)
    for index, line in enumerate(lines):
        if "version" in line:
            # "version": "0.5.9-beta"
            for x in ["-alpha", "-beta", "-rc"]:
                if x in v and v.split(x)[-1].isdigit():
                    v = v.replace(x, x + ".")
            lines[index] = f'  "version": "{v}",\n'
            break


def _normalize_js_version(v: str) -> str:
    v = v.strip()
    v = re.sub(r"-(alpha|beta|rc)(\d+)$", r"-\1.\2", v)
    dev_match = re.search(r"(?i)(?:-dev|\\.dev)(\\d+)$", v)
    if dev_match:
        v = re.sub(r"(?i)(?:-dev|\\.dev)\\d+$", f"-alpha.{dev_match.group(1)}", v)
        return v
    if re.search(r"(?i)(-snapshot|\\.dev|-dev)$", v):
        v = re.sub(r"(?i)(-snapshot|\\.dev|-dev)$", "-alpha.0", v)
    return v


def _is_release_version(v: str) -> bool:
    v = _strip_version_prefix(v)
    return re.match(r"^\d+\.\d+\.\d+$", v) is not None


def _normalize_rust_version(v: str) -> str:
    v = v.strip()
    v = re.sub(r"-(alpha|beta|rc)(\d+)$", r"-\1.\2", v)
    if re.search(r"(?i)-(alpha|beta)\.0$", v):
        return v
    if re.search(r"(?i)-(alpha|beta|rc)\.\d+$", v):
        return v
    if re.search(r"(?i)-pre$", v):
        return v
    dev_match = re.search(r"(?i)(?:-dev|\\.dev)(\\d+)$", v)
    if dev_match:
        base = re.sub(r"(?i)(?:-dev|\\.dev)\\d+$", "", v)
        return f"{base}-alpha.{dev_match.group(1)}"
    if re.search(r"(?i)(-snapshot|\\.dev|-dev)$", v):
        base = re.sub(r"(?i)(-snapshot|\\.dev|-dev)$", "", v)
        return f"{base}-alpha.0"
    return v


def _parse_args():
    parser = argparse.ArgumentParser(
        formatter_class=argparse.ArgumentDefaultsHelpFormatter
    )
    parser.set_defaults(func=parser.print_help)
    subparsers = parser.add_subparsers()
    bump_version_parser = subparsers.add_parser(
        "bump_version",
        description="Bump version",
    )
    bump_version_parser.add_argument("-version", type=str, help="new version")
    bump_version_parser.add_argument("-l", type=str, help="language")
    bump_version_parser.add_argument(
        "-release-version",
        dest="release_version",
        type=str,
        default=None,
        help="released version to write in user-facing documentation",
    )
    bump_version_parser.set_defaults(func=bump_version)

    prepare_parser = subparsers.add_parser(
        "prepare",
        description="Prepare release branch",
    )
    prepare_parser.add_argument("-v", type=str, help="new version")
    prepare_parser.set_defaults(func=prepare)

    release_parser = subparsers.add_parser(
        "build",
        description="Build release artifacts",
    )
    release_parser.add_argument("-v", type=str, help="new version")
    release_parser.set_defaults(func=build)

    verify_parser = subparsers.add_parser(
        "verify",
        description="Verify release artifacts",
    )
    verify_parser.add_argument("-v", type=str, help="new version")
    verify_parser.set_defaults(func=verify)

    publish_jvm_parser = subparsers.add_parser(
        "publish_jvm",
        description="Publish Java, Kotlin, and Scala artifacts",
    )
    publish_jvm_parser.add_argument(
        "-l",
        dest="languages",
        type=str,
        default="all",
        help="comma separated JVM languages: java,kotlin,scala",
    )
    publish_jvm_parser.add_argument(
        "--mode",
        choices=JVM_PUBLICATION_MODES,
        default="release",
        help="release stages signed artifacts; snapshot publishes unsigned snapshots",
    )
    publish_jvm_parser.set_defaults(func=publish_jvm)

    close_jvm_parser = subparsers.add_parser(
        "close_jvm_staging",
        description="Close and verify the two Nexus repositories from publish_jvm",
    )
    close_jvm_parser.add_argument(
        "-v",
        dest="v",
        required=True,
        help="final release version without a v prefix or RC suffix",
    )
    close_jvm_parser.add_argument(
        "--rc-tag",
        required=True,
        help="immutable release-candidate tag",
    )
    close_jvm_parser.add_argument(
        "--java-kotlin-id",
        dest="java_kotlin_staging_id",
        required=True,
        help="staging repository ID produced by Java and Kotlin publication",
    )
    close_jvm_parser.add_argument(
        "--scala-id",
        dest="scala_staging_id",
        required=True,
        help="staging repository ID produced by Scala publication",
    )
    close_jvm_parser.add_argument(
        "--verify-only",
        action="store_true",
        help="require closed state and verify downloads without submitting a close",
    )
    close_jvm_parser.set_defaults(func=close_jvm_staging)

    verify_java_parser = subparsers.add_parser(
        "verify_java_artifacts",
        description="Verify Java binary and source-release artifacts",
    )
    verify_java_parser.set_defaults(func=verify_java_artifacts)

    verify_kotlin_parser = subparsers.add_parser(
        "verify_kotlin_artifacts",
        description="Verify Kotlin binary, source, API documentation, and POM artifacts",
    )
    verify_kotlin_parser.set_defaults(func=verify_kotlin_artifacts)

    args = parser.parse_args()
    arg_dict = dict(vars(args))
    del arg_dict["func"]
    args.func(**arg_dict)


if __name__ == "__main__":
    _parse_args()
