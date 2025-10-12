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

set -e

# Function to add frontmatter header to a guide file
add_header() {
  local SOURCE_FILE=$1
  local DEST_FILE=$2
  local HEADER_CONTENT=$3

  echo "Processing $SOURCE_FILE -> $DEST_FILE"

  # Resolve symlink if it is one
  if [ -L "$DEST_FILE" ]; then
    SOURCE_FILE=$(readlink -f "$DEST_FILE" 2>/dev/null || readlink "$DEST_FILE")
    echo "Resolved symlink to: $SOURCE_FILE"
  fi

  if [ -f "$SOURCE_FILE" ]; then
    # Create file with header and source content
    echo "$HEADER_CONTENT" > "$DEST_FILE"
    cat "$SOURCE_FILE" >> "$DEST_FILE"
    echo "Successfully created $DEST_FILE with header"
  else
    echo "Warning: $SOURCE_FILE not found"
  fi
}

# Python guide header
PYTHON_HEADER=$(cat << 'EOF'
---
title: Python Serialization
sidebar_position: 1
id: python_serialization
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

EOF
)

# Rust guide header
RUST_HEADER=$(cat << 'EOF'
---
title: Rust Serialization
sidebar_position: 2
id: rust_serialization
license: |
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
---

EOF
)

# Process Python guide
rm -rf docs/guide/python_guide.md
add_header "python/README.md" "docs/guide/python_guide.md" "$PYTHON_HEADER"

# Process Rust guide
rm -rf docs/guide/rust_guide.md
add_header "rust/README.md" "docs/guide/rust_guide.md" "$RUST_HEADER"
git add docs/guide/rust_guide.md
git add docs/guide/python_guide.md
git commit -m "Added rust and python docs"