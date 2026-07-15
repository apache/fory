#!/usr/bin/env python3
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

"""Verify the release-minified Android artifacts without loading application code."""

from __future__ import annotations

import argparse
import io
import json
import re
import sys
import zipfile
from pathlib import Path


RULE_CARRIER_PREFIX = "META-INF/fory-json/r8/"
RULE_CARRIER_NAME = "fory-json-generated-"
FORBIDDEN_DEX_DESCRIPTORS = (
    b"Lorg/apache/fory/json/codegen/JsonCodegen;",
    b"Lorg/apache/fory/json/codegen/JsonJITContext;",
    b"Lorg/apache/fory/json/resolver/GeneratedCodecInstantiator;",
    b"Lorg/apache/fory/shaded/org/codehaus/janino/",
    b"Lorg/codehaus/janino/",
    b"Ljava/lang/reflect/AnnotatedType;",
)
FORBIDDEN_MAPPING_CLASSES = (
    "org.apache.fory.json.codegen.JsonCodegen",
    "org.apache.fory.json.codegen.JsonJITContext",
    "org.apache.fory.json.resolver.GeneratedCodecInstantiator",
    "java.lang.reflect.AnnotatedType",
)
FORBIDDEN_MAPPING_PREFIXES = (
    "org.apache.fory.shaded.org.codehaus.janino.",
    "org.codehaus.janino.",
)
NON_RECORD_APP_COMPANIONS = (
    "org.apache.fory.android.json.AppModel_ForyJsonMetadata",
    "org.apache.fory.android.json.FieldModeModel_ForyJsonMetadata",
    "org.apache.fory.android.json.AndroidJsonRuntimeScenarios_PrivateCreatorModel_ForyJsonMetadata",
    "org.apache.fory.android.json.AndroidJsonRuntimeScenarios_PrivateFactoryModel_ForyJsonMetadata",
    "org.apache.fory.android.json.AndroidJsonRuntimeScenarios_PrivateStaticModel_ForyJsonMetadata",
)
NON_RECORD_JAR_COMPANIONS = tuple(
    "org.apache.fory.android.json.jar." + name + "_ForyJsonMetadata"
    for name in (
        "JarAnyModel",
        "JarArrayShape",
        "JarCircle",
        "JarCodedValue",
        "JarCreatorModel",
        "JarModel",
        "JarObjectShape",
        "JarPrimitiveBean",
        "JarShape",
        "JarSquare",
    )
)
NON_RECORD_AAR_COMPANIONS = (
    "org.apache.fory.android.json.aar.AarModel_ForyJsonMetadata",
)
RECORD_APP_COMPANIONS = (
    "org.apache.fory.android.json.record.AppRecord_ForyJsonMetadata",
    "org.apache.fory.android.json.record.RecordRuntimeScenarios_PrivateRecord_ForyJsonMetadata",
)
RECORD_JAR_COMPANIONS = (
    "org.apache.fory.android.json.record.jar.JarRecord_ForyJsonMetadata",
)
RECORD_AAR_COMPANIONS = (
    "org.apache.fory.android.json.record.aar.AarRecord_ForyJsonMetadata",
)


def fail(message: str) -> None:
    raise AssertionError(message)


def one_file(root: Path, pattern: str, description: str) -> Path:
    matches = sorted(path for path in root.glob(pattern) if path.is_file())
    if len(matches) != 1:
        fail(f"Expected one {description} under {root}, found {matches}")
    return matches[0]


def archive_entries(source: Path | io.BytesIO, description: str) -> dict[str, bytes]:
    with zipfile.ZipFile(source) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            fail(f"Duplicate ZIP entry in {description}")
        return {name: archive.read(name) for name in names}


def zip_entries(path: Path) -> dict[str, bytes]:
    return archive_entries(path, str(path))


def verify_rule_contents(
    rule_entries: dict[str, bytes], expected_companions: tuple[str, ...], source: Path
) -> int:
    generated_rules = {
        name: value
        for name, value in rule_entries.items()
        if name.startswith(RULE_CARRIER_PREFIX) and RULE_CARRIER_NAME in name
    }
    if not generated_rules:
        fail(f"Missing generated R8 rule carriers in {source}")
    combined = ""
    for name, value in generated_rules.items():
        text = value.decode("utf-8")
        if "-keep" not in text or "_ForyJsonMetadata" not in text:
            fail(f"Generated rule is incomplete in {source}!/{name}")
        if "**" in text:
            fail(f"Generated rule contains a broad wildcard in {source}!/{name}")
        combined += text
    for companion in expected_companions:
        if companion not in combined:
            fail(f"No generated targeted R8 rule retains {companion} in {source}")
    return len(generated_rules)


def verify_jar(path: Path, expected_companions: tuple[str, ...]) -> tuple[int, int]:
    outer = zip_entries(path)
    for companion in expected_companions:
        entry = companion.replace(".", "/") + ".class"
        if entry not in outer:
            fail(f"Missing generated companion {entry} in {path}")
    carriers = {
        name: value
        for name, value in outer.items()
        if name.startswith(RULE_CARRIER_PREFIX)
    }
    rule_count = verify_rule_contents(carriers, expected_companions, path)
    return len([name for name in outer if name.endswith(".class")]), rule_count


def reject_carriers(entries: dict[str, bytes], source: Path) -> None:
    leaked = sorted(
        name
        for name in entries
        if name.startswith(RULE_CARRIER_PREFIX) or ("/" + RULE_CARRIER_PREFIX) in name
    )
    if leaked:
        fail(f"Generated rule carrier leaked into {source}: {leaked}")


def verify_aar(path: Path, expected_companions: tuple[str, ...]) -> tuple[int, int]:
    outer = zip_entries(path)
    reject_carriers(outer, path)
    if "classes.jar" not in outer:
        fail(f"AAR has no classes.jar: {path}")
    entries = archive_entries(io.BytesIO(outer["classes.jar"]), f"{path}!/classes.jar")
    reject_carriers(entries, path)
    for name, content in outer.items():
        if name.startswith("libs/") and name.endswith(".jar"):
            reject_carriers(
                archive_entries(io.BytesIO(content), f"{path}!/{name}"), path
            )
    for companion in expected_companions:
        entry = companion.replace(".", "/") + ".class"
        if entry not in entries:
            fail(f"Missing generated companion {entry} in {path}")
    if "proguard.txt" not in outer:
        fail(f"Published AAR has no standard consumer rules: {path}")
    rules = outer["proguard.txt"].decode("utf-8")
    if "**" in rules:
        fail(f"AAR consumer rules contain a broad wildcard: {path}")
    for companion in expected_companions:
        if companion not in rules:
            fail(f"AAR consumer rules do not retain {companion}: {path}")
    return len([name for name in entries if name.endswith(".class")]), 1


def verify_app_class_output(
    app_root: Path, expected_companions: tuple[str, ...]
) -> tuple[int, int]:
    javac_root = app_root / "build/intermediates/javac/release"
    candidates = (
        javac_root / "classes",
        javac_root / "compileReleaseJavaWithJavac/classes",
    )
    existing = [path for path in candidates if path.is_dir()]
    if len(existing) != 1:
        fail(f"Expected one release javac CLASS_OUTPUT, found {existing}")
    classes_root = existing[0]
    for companion in expected_companions:
        path = classes_root / (companion.replace(".", "/") + ".class")
        if not path.is_file():
            fail(f"Missing generated application companion in CLASS_OUTPUT: {path}")
    rule_entries = {
        path.relative_to(classes_root).as_posix(): path.read_bytes()
        for path in (classes_root / RULE_CARRIER_PREFIX).glob(
            "fory-json-generated-*.pro"
        )
    }
    rule_count = verify_rule_contents(rule_entries, expected_companions, classes_root)
    return len(expected_companions), rule_count


def verify_collected_rules(
    app_root: Path, expected_companions: tuple[str, ...]
) -> Path:
    path = app_root / "build/intermediates/fory-json-r8/release/rules.pro"
    if not path.is_file():
        fail(f"Missing Fory JSON variant R8 rules: {path}")
    text = path.read_text(encoding="utf-8")
    if "**" in text:
        fail(f"Collected Fory JSON rules contain a broad wildcard: {path}")
    for companion in expected_companions:
        if companion not in text:
            fail(f"Collected Fory JSON rules do not retain {companion}: {path}")
    return path


def find_mapping(app_root: Path) -> Path:
    return one_file(app_root, "build/outputs/mapping/release/mapping.txt", "R8 mapping")


def find_configuration(app_root: Path) -> Path:
    return one_file(
        app_root,
        "build/outputs/mapping/release/configuration.txt",
        "merged R8 configuration",
    )


def find_seeds(app_root: Path) -> Path:
    return one_file(app_root, "build/outputs/mapping/release/seeds.txt", "R8 seeds")


def find_usage(app_root: Path) -> Path:
    return one_file(app_root, "build/outputs/mapping/release/usage.txt", "R8 usage")


def verify_configuration(path: Path, expected_companions: tuple[str, ...]) -> None:
    text = path.read_text(encoding="utf-8")
    if (
        "org.apache.fory.platform.AndroidSupport" not in text
        or "IS_ANDROID return true" not in text
    ):
        fail(
            f"Merged R8 configuration did not consume the Fory Android assumption: {path}"
        )
    for companion in expected_companions:
        if companion not in text:
            fail(
                f"Merged R8 configuration has no generated rule for {companion}: {path}"
            )
    broad_model_rule = re.compile(
        r"-keep[^\n]*class\s+org\.apache\.fory\.android\.json(?:\.[\w$]+)*\.\*\*"
    )
    if broad_model_rule.search(text):
        fail(f"Merged R8 configuration contains a broad JSON model keep rule: {path}")


def verify_mapping(path: Path, expected_companions: tuple[str, ...]) -> None:
    text = path.read_text(encoding="utf-8")
    for companion in expected_companions:
        if f"{companion} -> {companion}:" not in text:
            fail(f"Companion convention name was not retained: {companion}")
    for line in text.splitlines():
        match = re.fullmatch(r"(\S+) -> \S+:", line)
        if match is None:
            continue
        class_name = match.group(1)
        if any(
            class_name == forbidden or class_name.startswith(forbidden + "$")
            for forbidden in FORBIDDEN_MAPPING_CLASSES
        ) or class_name.startswith(FORBIDDEN_MAPPING_PREFIXES):
            fail(f"Forbidden Android class survived R8: {class_name} in {path}")


def verify_seeds_and_usage(
    seeds_path: Path, usage_path: Path, expected_companions: tuple[str, ...]
) -> None:
    seeded_classes = set(seeds_path.read_text(encoding="utf-8").splitlines())
    removed_classes = set(usage_path.read_text(encoding="utf-8").splitlines())
    for companion in expected_companions:
        if companion not in seeded_classes:
            fail(f"Generated companion is not an R8 seed: {companion}")
        if companion in removed_classes:
            fail(f"Generated companion was removed by R8: {companion}")


def verify_apk(path: Path, expected_companions: tuple[str, ...]) -> tuple[int, int]:
    entries = zip_entries(path)
    reject_carriers(entries, path)
    dex_entries = sorted(
        name for name in entries if re.fullmatch(r"classes\d*\.dex", name)
    )
    if not dex_entries:
        fail(f"No DEX files in {path}")
    dex_bytes = b"".join(entries[name] for name in dex_entries)
    for descriptor in FORBIDDEN_DEX_DESCRIPTORS:
        if descriptor in dex_bytes:
            fail(f"Forbidden Android class descriptor {descriptor!r} found in {path}")
    for companion in expected_companions:
        descriptor = ("L" + companion.replace(".", "/") + ";").encode("utf-8")
        if descriptor not in dex_bytes:
            fail(f"Generated companion descriptor {descriptor!r} missing from {path}")
    return len(dex_entries), path.stat().st_size


def verify_bundle(path: Path) -> int:
    reject_carriers(zip_entries(path), path)
    return path.stat().st_size


def verify_dependencies(path: Path | None) -> None:
    if path is None:
        return
    text = path.read_text(encoding="utf-8")
    if "org.codehaus.janino:janino" in text:
        fail(f"Android release runtime classpath resolves external Janino: {path}")


def profile_inputs(
    root: Path, profile: str
) -> tuple[
    Path,
    Path,
    Path,
    Path,
    tuple[str, ...],
    tuple[str, ...],
    tuple[str, ...],
    Path,
]:
    if profile == "non-record":
        jar = one_file(root / "json-model-jar", "build/libs/*.jar", "JSON model JAR")
        aar = one_file(
            root / "json-model-aar", "build/outputs/aar/*-release.aar", "JSON model AAR"
        )
        app_root = root
        apk = one_file(app_root, "build/outputs/apk/release/*.apk", "release APK")
        bundle = one_file(
            app_root, "build/outputs/bundle/release/*.aab", "release Android App Bundle"
        )
        return (
            jar,
            aar,
            apk,
            bundle,
            NON_RECORD_APP_COMPANIONS,
            NON_RECORD_JAR_COMPANIONS,
            NON_RECORD_AAR_COMPANIONS,
            app_root,
        )
    jar = one_file(root / "record-model-jar", "build/libs/*.jar", "record model JAR")
    aar = one_file(
        root / "record-model-aar", "build/outputs/aar/*-release.aar", "record model AAR"
    )
    app_root = root / "record-app"
    apk = one_file(app_root, "build/outputs/apk/release/*.apk", "record release APK")
    bundle = one_file(
        app_root,
        "build/outputs/bundle/release/*.aab",
        "record release Android App Bundle",
    )
    return (
        jar,
        aar,
        apk,
        bundle,
        RECORD_APP_COMPANIONS,
        RECORD_JAR_COMPANIONS,
        RECORD_AAR_COMPANIONS,
        app_root,
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", choices=("non-record", "record"), required=True)
    parser.add_argument("--dependencies", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    root = Path(__file__).resolve().parent
    jar, aar, apk, bundle, app_companions, jar_companions, aar_companions, app_root = (
        profile_inputs(root, args.profile)
    )
    companions = app_companions + jar_companions + aar_companions
    app_classes, app_rules = verify_app_class_output(app_root, app_companions)
    jar_classes, jar_rules = verify_jar(jar, jar_companions)
    aar_classes, aar_rules = verify_aar(aar, aar_companions)
    collected_rules = verify_collected_rules(app_root, companions)
    configuration = find_configuration(app_root)
    mapping = find_mapping(app_root)
    seeds = find_seeds(app_root)
    usage = find_usage(app_root)
    verify_configuration(configuration, companions)
    verify_mapping(mapping, companions)
    verify_seeds_and_usage(seeds, usage, companions)
    dex_files, apk_bytes = verify_apk(apk, companions)
    bundle_bytes = verify_bundle(bundle)
    verify_dependencies(args.dependencies)

    report = {
        "profile": args.profile,
        "apk": str(apk.relative_to(root)),
        "apk_bytes": apk_bytes,
        "bundle": str(bundle.relative_to(root)),
        "bundle_bytes": bundle_bytes,
        "app_companions": app_classes,
        "app_generated_rules": app_rules,
        "collected_rule_bytes": collected_rules.stat().st_size,
        "dex_files": dex_files,
        "jar_classes": jar_classes,
        "jar_rule_carriers": jar_rules,
        "aar_classes": aar_classes,
        "aar_consumer_rule_files": aar_rules,
        "r8_seed_bytes": seeds.stat().st_size,
        "r8_usage_bytes": usage.stat().st_size,
    }
    encoded = json.dumps(report, indent=2, sort_keys=True) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded, encoding="utf-8")
    sys.stdout.write(encoded)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"Android release verification failed: {error}", file=sys.stderr)
        raise SystemExit(1)
