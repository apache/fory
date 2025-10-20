#!/bin/bash
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

# Script to remove commented content from git commit messages
# This ensures clean and readable commit history by filtering out
# lines starting with # or wrapped in <!-- -->

# Read the commit message file
COMMIT_MSG_FILE=$1

if [ -z "$COMMIT_MSG_FILE" ]; then
    echo "Error: No commit message file provided"
    exit 1
fi

# Create a temporary file
TEMP_FILE=$(mktemp)

# Remove commented lines and HTML comments
# - Lines starting with # (after optional whitespace)
# - HTML comment blocks <!-- -->
grep -v '^\s*#' "$COMMIT_MSG_FILE" | \
    sed '/<!--/,/-->/d' > "$TEMP_FILE"

# Replace original file with cleaned content
mv "$TEMP_FILE" "$COMMIT_MSG_FILE"

exit 0
