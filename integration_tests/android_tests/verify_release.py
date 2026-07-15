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

"""Verify Fory JSON metadata and the release-minified Android application."""

from __future__ import annotations

import argparse
import json
import re
import sys
import zipfile
from pathlib import Path


RULES_DIRECTORY = Path("META-INF/com.android.tools/r8")
EXPECTED_COMPANIONS = (
    "org.apache.fory.android.json.AppModel_ForyJsonMetadata",
    "org.apache.fory.android.json.FieldModeModel_ForyJsonMetadata",
    "org.apache.fory.android.json.AndroidJsonRuntimeScenarios_AnyModel_ForyJsonMetadata",
    "org.apache.fory.android.json.AndroidJsonRuntimeScenarios_AppRecord_ForyJsonMetadata",
    "org.apache.fory.android.json.AndroidJsonRuntimeScenarios_Circle_ForyJsonMetadata",
    "org.apache.fory.android.json.AndroidJsonRuntimeScenarios_CodedValue_ForyJsonMetadata",
    "org.apache.fory.android.json.AndroidJsonRuntimeScenarios_NestedModel_ForyJsonMetadata",
    "org.apache.fory.android.json.AndroidJsonRuntimeScenarios_PlatformChild_ForyJsonMetadata",
    "org.apache.fory.android.json.AndroidJsonRuntimeScenarios_PrimitiveBean_ForyJsonMetadata",
    "org.apache.fory.android.json.AndroidJsonRuntimeScenarios_PrivateCreatorModel_ForyJsonMetadata",
    "org.apache.fory.android.json.AndroidJsonRuntimeScenarios_PrivateFactoryModel_ForyJsonMetadata",
    "org.apache.fory.android.json.AndroidJsonRuntimeScenarios_PrivateRecord_ForyJsonMetadata",
    "org.apache.fory.android.json.AndroidJsonRuntimeScenarios_PrivateStaticModel_ForyJsonMetadata",
    "org.apache.fory.android.json.AndroidJsonRuntimeScenarios_Shape_ForyJsonMetadata",
    "org.apache.fory.android.json.AndroidJsonRuntimeScenarios_Square_ForyJsonMetadata",
)
FORBIDDEN_DEX_DESCRIPTORS = (
    b"Lorg/apache/fory/json/codegen/JsonCodegen;",
    b"Lorg/apache/fory/json/codegen/JsonJITContext;",
    b"Lorg/apache/fory/json/resolver/GeneratedCodecInstantiator;",
    b"Lorg/apache/fory/shaded/org/codehaus/janino/",
    b"Lorg/codehaus/janino/",
    b"Ljava/lang/reflect/AnnotatedType;",
)
FORBIDDEN_MAPPING_NAMES = (
    "org.apache.fory.json.codegen.JsonCodegen",
    "org.apache.fory.json.codegen.JsonJITContext",
    "org.apache.fory.json.resolver.GeneratedCodecInstantiator",
    "java.lang.reflect.AnnotatedType",
)
FORBIDDEN_MAPPING_PREFIXES = (
    "org.apache.fory.shaded.org.codehaus.janino.",
    "org.codehaus.janino.",
)


def fail(message: str) -> None:
    raise AssertionError(message)


def one_file(root: Path, pattern: str, description: str) -> Path:
    matches = sorted(path for path in root.glob(pattern) if path.is_file())
    if len(matches) != 1:
        fail(f"Expected one {description} under {root}, found {matches}")
    return matches[0]


def class_output(root: Path) -> Path:
    javac = root / "build/intermediates/javac/release"
    candidates = (
        javac / "classes",
        javac / "compileReleaseJavaWithJavac/classes",
    )
    outputs = [path for path in candidates if path.is_dir()]
    if len(outputs) != 1:
        fail(f"Expected one release javac CLASS_OUTPUT, found {outputs}")
    return outputs[0]


def verify_generated_metadata(root: Path) -> tuple[int, int]:
    output = class_output(root)
    for companion in EXPECTED_COMPANIONS:
        path = output / (companion.replace(".", "/") + ".class")
        if not path.is_file():
            fail(f"Missing generated JSON metadata companion: {path}")

    rules_root = output / RULES_DIRECTORY
    rules = sorted(rules_root.glob("fory-json-generated-*.pro"))
    if len(rules) != len(EXPECTED_COMPANIONS):
        fail(
            "Expected one standard R8 rule file per generated companion, "
            f"found {len(rules)} under {rules_root}"
        )
    text = "".join(path.read_text(encoding="utf-8") for path in rules)
    if "**" in text:
        fail(f"Generated R8 rules contain a broad wildcard under {rules_root}")
    for companion in EXPECTED_COMPANIONS:
        if companion not in text:
            fail(f"Generated R8 rules do not retain {companion}")
    return len(EXPECTED_COMPANIONS), len(rules)


def mapping_classes(path: Path) -> dict[str, str]:
    classes = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"(\S+) -> (\S+):", line)
        if match is not None:
            classes[match.group(1)] = match.group(2)
    return classes


def verify_r8_outputs(root: Path) -> tuple[Path, Path, Path]:
    mapping_root = root / "build/outputs/mapping/release"
    configuration = one_file(mapping_root, "configuration.txt", "R8 configuration")
    mapping = one_file(mapping_root, "mapping.txt", "R8 mapping")
    usage = one_file(mapping_root, "usage.txt", "R8 usage")

    configuration_text = configuration.read_text(encoding="utf-8")
    if (
        "org.apache.fory.platform.AndroidSupport" not in configuration_text
        or "IS_ANDROID return true" not in configuration_text
    ):
        fail("R8 configuration did not consume Fory's Android rule")
    for companion in EXPECTED_COMPANIONS:
        if companion not in configuration_text:
            fail(f"R8 configuration did not consume the generated rule for {companion}")

    classes = mapping_classes(mapping)
    for companion in EXPECTED_COMPANIONS:
        if classes.get(companion) != companion:
            fail(f"Generated companion name was not retained: {companion}")
    for class_name in classes:
        if any(
            class_name == forbidden or class_name.startswith(forbidden + "$")
            for forbidden in FORBIDDEN_MAPPING_NAMES
        ) or class_name.startswith(FORBIDDEN_MAPPING_PREFIXES):
            fail(f"Forbidden Android class survived R8: {class_name}")

    usage_text = usage.read_text(encoding="utf-8")
    if not usage_text.strip():
        fail(f"R8 usage report is empty: {usage}")
    for companion in EXPECTED_COMPANIONS:
        if re.search(rf"(?m)^{re.escape(companion)}(?:$|:)", usage_text):
            fail(f"Generated companion was removed by R8: {companion}")
    return configuration, mapping, usage


def zip_entries(path: Path) -> dict[str, bytes]:
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            fail(f"Duplicate ZIP entry in {path}")
        return {name: archive.read(name) for name in names}


def verify_dex(entries: dict[str, bytes], pattern: str, source: Path) -> int:
    dex_names = sorted(name for name in entries if re.fullmatch(pattern, name))
    if not dex_names:
        fail(f"No DEX files in {source}")
    dex = b"".join(entries[name] for name in dex_names)
    for descriptor in FORBIDDEN_DEX_DESCRIPTORS:
        if descriptor in dex:
            fail(f"Forbidden Android descriptor {descriptor!r} found in {source}")
    for companion in EXPECTED_COMPANIONS:
        descriptor = ("L" + companion.replace(".", "/") + ";").encode("utf-8")
        if descriptor not in dex:
            fail(f"Generated companion descriptor {descriptor!r} missing from {source}")
    return len(dex_names)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    root = Path(__file__).resolve().parent
    companions, rule_files = verify_generated_metadata(root)
    configuration, mapping, usage = verify_r8_outputs(root)
    apk = one_file(root, "build/outputs/apk/release/*.apk", "release APK")
    bundle = one_file(root, "build/outputs/bundle/release/*.aab", "release bundle")
    apk_dex = verify_dex(zip_entries(apk), r"classes\d*\.dex", apk)
    bundle_dex = verify_dex(zip_entries(bundle), r"base/dex/classes\d*\.dex", bundle)

    report = {
        "apk": str(apk.relative_to(root)),
        "apk_bytes": apk.stat().st_size,
        "apk_dex_files": apk_dex,
        "bundle": str(bundle.relative_to(root)),
        "bundle_bytes": bundle.stat().st_size,
        "bundle_dex_files": bundle_dex,
        "generated_companions": companions,
        "generated_rule_files": rule_files,
        "r8_configuration_bytes": configuration.stat().st_size,
        "r8_mapping_bytes": mapping.stat().st_size,
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
