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
from . import common


def generate_doc_example_tests():
    # Generate C++ test files from documentation examples.
    logging.info("Generating documentation example tests")

    script_path = os.path.join(
        common.PROJECT_ROOT_DIR, "ci", "extract_cpp_doc_examples.py"
    )
    result = subprocess.run(
        [
            "python",
            script_path,
            "--docs-dir",
            "docs/guide/cpp",
            "--output-dir",
            "cpp/doc_tests",
            "--generate-build",
        ],
        cwd=common.PROJECT_ROOT_DIR,
        capture_output=True,
        text=True,
    )

    if result.returncode != 0:
        logging.error(f"Failed to generate doc example tests: {result.stderr}")
        raise RuntimeError("Failed to generate doc example tests")

    # logging.info(f"Documentation example tests generated in {output_dir}")


def run_doc_example_tests():
    # Generates test files from documentation and runs them with Bazel.
    generate_doc_example_tests()

    logging.info("Running documentation example tests")
    test_command = "test //cpp/doc_tests:doc_example_tests"
    if common.get_os_machine() == "x86_64":
        test_command = "test --config=x86_64 //cpp/doc_tests:doc_example_tests"
    common.bazel(test_command)


def run(install_deps_only=False, skip_doc_tests=False, doc_tests_only=False):
    """Run C++ CI tasks.

    Args:
        install_deps_only: If True, only install dependencies without running tests.
        skip_doc_tests: If True, skip documentation example tests.
        doc_tests_only: If True, only run documentation example tests.
    """
    logging.info("Running C++ CI tasks")
    common.install_cpp_deps()

    if install_deps_only:
        logging.info("Skipping tests as --install-deps-only was specified")
        return

    if doc_tests_only:
        # logging.info("Running only documentation example tests")
        run_doc_example_tests()
        return

    # collect all C++ targets
    query_result = common.bazel("query //...")
    targets = query_result.replace("\n", " ").replace("\r", " ")

    test_command = "test"
    if common.get_os_machine() == "x86_64":
        test_command += " --config=x86_64"

    common.bazel(f"{test_command} {targets}")
    logging.info("C++ CI tasks completed successfully")

    # Run documentation example tests
    if not skip_doc_tests:
        try:
            run_doc_example_tests()
        except Exception as e:
            logging.warning(f"Documentation example tests failed: {e}")
