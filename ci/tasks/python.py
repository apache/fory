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

def install_python():
    """Install Python using Miniconda."""
    logging.info("Installing Python using Miniconda")
    common.exec_cmd("wget -q https://repo.anaconda.com/miniconda/Miniconda3-py38_23.5.2-0-Linux-x86_64.sh -O Miniconda3.sh")
    common.exec_cmd("bash Miniconda3.sh -b -p $HOME/miniconda && rm -f miniconda.*")
    common.exec_cmd("which python")
    python_version = common.exec_cmd("python -V")
    python_path = common.exec_cmd("which python")
    logging.info(f"Python version {python_version}, path {python_path}")

def install_pyfory():
    """Install pyfory package."""
    logging.info("Installing pyfory package")
    python_version = common.exec_cmd("python -V")
    python_path = common.exec_cmd("which python")
    logging.info(f"Python version {python_version}, path {python_path}")

    # Install PyArrow
    common.exec_cmd(f"{common.PROJECT_ROOT_DIR}/ci/deploy.sh install_pyarrow")

    # Install dependencies
    common.exec_cmd("pip install Cython wheel pytest")

    # Install pyfory
    common.cd_project_subdir("python")
    common.exec_cmd("pip list")
    logging.info("Install pyfory")

    # Fix strange installed deps not found
    common.exec_cmd("pip install setuptools -U")
    common.exec_cmd("pip install -v -e .")

def run():
    """Run Python CI tasks."""
    install_pyfory()
    common.exec_cmd("pip install pandas")

    common.cd_project_subdir("python")
    logging.info("Executing fory python tests")

    # Run tests with default settings
    common.exec_cmd("pytest -v -s --durations=60 pyfory/tests")

    logging.info("Executing fory python tests succeeds")

    # Run tests with ENABLE_FORY_CYTHON_SERIALIZATION=0
    os.environ["ENABLE_FORY_CYTHON_SERIALIZATION"] = "0"
    common.exec_cmd("pytest -v -s --durations=60 pyfory/tests")

    logging.info("Executing fory python tests succeeds")
