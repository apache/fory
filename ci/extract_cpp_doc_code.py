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

"""
Extract C++ code examples from markdown documentation and generate test files.

This script scans markdown files in docs/guide/cpp/, extracts ```cpp code blocks,
and generates compilable C++ test files that can be run to verify the documentation
examples are correct.
"""

import argparse
import logging
import os
import re
import sys
from pathlib import Path
from typing import List, Tuple, Optional

logging.basicConfig(
    level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s"
)


def extract_cpp_code_blocks(content: str) -> List[Tuple[str, int]]:
    # Extract C++ code blocks from markdown content.
    code_blocks = []
    pattern = r"```cpp\n(.*?)```"

    for match in re.finditer(pattern, content, re.DOTALL):
        code = match.group(1).strip()
        line_num = content[: match.start()].count("\n") + 1
        code_blocks.append((code, line_num))

    return code_blocks


def is_complete_example(code: str) -> bool:
    # Check if the code block is a complete, runnable example.A complete example should have a main function with all code inside it.
    # Code blocks with statements outside of functions are not complete examples.
    has_main = "int main()" in code or "int main (" in code

    # Check if there are statements outside of any function
    # look for lines that look like function calls
    # or object declarations at the top level
    lines = code.split("\n")
    brace_depth = 0
    in_main = False

    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("//"):
            continue

        # Track braces
        for char in stripped:
            if char == "{":
                brace_depth += 1
            elif char == "}":
                brace_depth -= 1

        # Check if entering main
        if "int main" in stripped:
            in_main = True
            continue

        # If code is not inside any braces and not in a struct/class declaration,
        # and we see what looks like a function call or object usage,
        # this is a uncompleted example
        if brace_depth == 0 and not in_main:
            # Skip struct/class/enum declarations
            if any(
                keyword in stripped
                for keyword in [
                    "struct ",
                    "class ",
                    "enum ",
                    "using ",
                    "namespace ",
                    "#include",
                    "FORY_STRUCT",
                    "FORY_ENUM",
                ]
            ):
                continue
            # Skip forward declarations
            if stripped.endswith(";"):
                continue
            # If we see code that looks like it's executing (not declaring),
            # this is not a complete example
            if re.search(r"\w+\s*\([^)]*\)\s*;", stripped) and not re.search(
                r"^(struct|class|enum|using|namespace|#include|FORY_)", stripped
            ):
                return False

    return has_main


def wrap_code_as_test(code: str, doc_file: str, block_index: int) -> str:
    # Wrap a code snippet as a complete, compilable test file.

    includes = set()

    if "#include" not in code:
        includes.add('#include "fory/serialization/fory.h"')

    if "std::string" in code and "#include <string>" not in code:
        includes.add("#include <string>")
    if "std::vector" in code and "#include <vector>" not in code:
        includes.add("#include <vector>")
    if "std::map" in code and "#include <map>" not in code:
        includes.add("#include <map>")
    if "std::set" in code and "#include <set>" not in code:
        includes.add("#include <set>")
    if "std::unordered_map" in code and "#include <unordered_map>" not in code:
        includes.add("#include <unordered_map>")
    if "std::unordered_set" in code and "#include <unordered_set>" not in code:
        includes.add("#include <unordered_set>")
    if "std::optional" in code and "#include <optional>" not in code:
        includes.add("#include <optional>")
    if "std::shared_ptr" in code and "#include <memory>" not in code:
        includes.add("#include <memory>")
    if "std::unique_ptr" in code and "#include <memory>" not in code:
        includes.add("#include <memory>")
    if "std::variant" in code and "#include <variant>" not in code:
        includes.add("#include <variant>")
    if "std::chrono" in code and "#include <chrono>" not in code:
        includes.add("#include <chrono>")
    if "std::make_shared" in code and "#include <memory>" not in code:
        includes.add("#include <memory>")
    if "std::make_unique" in code and "#include <memory>" not in code:
        includes.add("#include <memory>")
    if "assert(" in code and "#include <cassert>" not in code:
        includes.add("#include <cassert>")
    if "std::cout" in code and "#include <iostream>" not in code:
        includes.add("#include <iostream>")
    if "RowEncoder" in code and '#include "fory/encoder/row_encoder.h"' not in code:
        includes.add('#include "fory/encoder/row_encoder.h"')
    if "Row" in code and '#include "fory/row/row.h"' not in code:
        includes.add('#include "fory/row/row.h"')

    include_section = "\n".join(sorted(includes))

    if "int main()" in code or "int main (" in code:
        # Only add namespace if not already present
        if "using namespace" not in code:
            code = f"using namespace fory::serialization;\n\n{code}"
        return f"""// Auto-generated test from {doc_file}
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

{include_section}

{code}
"""
    else:
        return f"""// Auto-generated test from {doc_file}, block {block_index}
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include <iostream>
{include_section}

using namespace fory::serialization;

{code}

int main() {{
    std::cout << "Documentation example compiled successfully" << std::endl;
    return 0;
}}
"""


def generate_test_file_name(doc_file: str, block_index: int) -> str:
    # Generate a test file name from documentation file and block index.

    base_name = Path(doc_file).stem
    return f"doc_test_{base_name}_{block_index}.cc"


def process_markdown_file(md_path: Path, output_dir: Path) -> List[Path]:
    # logging.info(f"Processing {md_path}")

    with open(md_path, "r", encoding="utf-8") as f:
        content = f.read()

    code_blocks = extract_cpp_code_blocks(content)
    logging.info(f"  Found {len(code_blocks)} C++ code blocks")

    generated_files = []

    for i, (code, line_num) in enumerate(code_blocks):
        if not is_complete_example(code):
            logging.debug(f"  Skipping incomplete example at line {line_num}")
            continue

        test_content = wrap_code_as_test(code, md_path.name, i)
        test_file_name = generate_test_file_name(md_path.name, i)
        test_path = output_dir / test_file_name

        with open(test_path, "w", encoding="utf-8") as f:
            f.write(test_content)

        generated_files.append(test_path)
        logging.info(f"  Generated {test_file_name}")

    return generated_files


def generate_bazel_build(test_files: List[Path], output_dir: Path) -> None:
    build_path = output_dir / "BUILD"

    build_content = """# Licensed to the Apache Software Foundation (ASF) under one
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

# Auto-generated BUILD file for documentation example tests
# Run: bazel test //:doc_example_tests

package(default_visibility = ["//visibility:public"])

"""

    test_names = []
    for test_file in sorted(test_files):
        test_name = test_file.stem
        test_names.append(test_name)

        # Determine additional deps based on test name
        deps = ['"//cpp/fory/serialization:fory_serialization"']
        if "row-format" in test_name:
            deps.append('"//cpp/fory/row:fory_row_format"')
            deps.append('"//cpp/fory/encoder:fory_encoder"')

        deps_str = ",\n        ".join(deps)

        build_content += f'''
cc_test(
    name = "{test_name}",
    srcs = ["{test_file.name}"],
    deps = [
        {deps_str},
    ],
)
'''

    if test_names:
        build_content += f"""
test_suite(
    name = "doc_example_tests",
    tests = [
{chr(10).join(f'        ":{name}",' for name in test_names)}
    ],
)
"""

    with open(build_path, "w", encoding="utf-8") as f:
        f.write(build_content)

    logging.info(f"Generated BUILD file with {len(test_names)} tests")


def main():
    parser = argparse.ArgumentParser(
        description="Extract C++ code examples from markdown documentation"
    )
    parser.add_argument(
        "--docs-dir",
        default="docs/guide/cpp",
        help="Directory containing markdown documentation files",
    )
    parser.add_argument(
        "--output-dir",
        default="cpp/doc_tests",
        help="Output directory for generated test files",
    )
    parser.add_argument(
        "--generate-build",
        action="store_true",
        help="Generate Bazel BUILD file",
    )
    parser.add_argument(
        "--verbose",
        "-v",
        action="store_true",
        help="Enable verbose output",
    )

    args = parser.parse_args()

    if args.verbose:
        logging.getLogger().setLevel(logging.DEBUG)

    script_dir = Path(__file__).parent
    project_root = script_dir.parent
    docs_dir = project_root / args.docs_dir
    output_dir = project_root / args.output_dir

    if not docs_dir.exists():
        logging.error(f"Documentation directory not found: {docs_dir}")
        sys.exit(1)

    output_dir.mkdir(parents=True, exist_ok=True)

    all_test_files = []

    for md_file in sorted(docs_dir.glob("*.md")):
        test_files = process_markdown_file(md_file, output_dir)
        all_test_files.extend(test_files)

    logging.info(f"\nTotal: Generated {len(all_test_files)} test files")

    if args.generate_build and all_test_files:
        generate_bazel_build(all_test_files, output_dir)

    print(f"\nGenerated files in {output_dir}:")
    for f in sorted(all_test_files):
        print(f"  {f.name}")


if __name__ == "__main__":
    main()
