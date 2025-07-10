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
from . import common

def run(install_deps_only=False):
    """Run C++ CI tasks.

    Args:
        install_deps_only: If True, only install dependencies without running tests.
    """
    logging.info("Running C++ CI tasks")
    common.install_cpp_deps()

    if install_deps_only:
        logging.info("Skipping tests as --install-deps-only was specified")
        return

    # collect all C++ targets
    query_result = common.bazel("query //...")
    targets = query_result.replace("\n", " ").replace("\r", " ")

    test_command = "test"
    if common.get_os_machine() == "x86_64":
        test_command += " --config=x86_64"

    common.bazel(f"{test_command} {targets}")
    logging.info("C++ CI tasks completed successfully")
