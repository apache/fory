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

"""CLI entry point for the FDL compiler."""

import argparse
import sys
from pathlib import Path
from typing import Dict, List, Optional, Set

from fory_compiler.parser.lexer import Lexer, LexerError
from fory_compiler.parser.parser import Parser, ParseError
from fory_compiler.parser.ast import Schema
from fory_compiler.generators.base import GeneratorOptions
from fory_compiler.generators import GENERATORS


class ImportError(Exception):
    """Error during import resolution."""
    pass


def parse_fdl_file(file_path: Path) -> Schema:
    """Parse a single FDL file and return its schema."""
    source = file_path.read_text()
    lexer = Lexer(source, str(file_path))
    tokens = lexer.tokenize()
    parser = Parser(tokens)
    return parser.parse()


def resolve_import_path(
    import_stmt: str,
    importing_file: Path,
    import_paths: List[Path],
) -> Optional[Path]:
    """
    Resolve an import path by searching in multiple directories.

    Search order:
    1. Relative to the importing file's directory
    2. Each import path in order (from -I / --proto_path / --import_path)

    Args:
        import_stmt: The import path string from the import statement
        importing_file: The file containing the import statement
        import_paths: List of additional search directories

    Returns:
        Resolved Path if found, None otherwise
    """
    # First, try relative to the importing file
    relative_path = (importing_file.parent / import_stmt).resolve()
    if relative_path.exists():
        return relative_path

    # Then try each import path
    for search_path in import_paths:
        candidate = (search_path / import_stmt).resolve()
        if candidate.exists():
            return candidate

    return None


def resolve_imports(
    file_path: Path,
    import_paths: Optional[List[Path]] = None,
    visited: Optional[Set[Path]] = None,
    cache: Optional[Dict[Path, Schema]] = None,
) -> Schema:
    """
    Recursively resolve imports and merge all types into a single schema.

    Args:
        file_path: Path to the FDL file to parse
        import_paths: List of directories to search for imports
        visited: Set of already visited files (for cycle detection)
        cache: Cache of already parsed schemas

    Returns:
        Schema with all imported types merged in
    """
    if import_paths is None:
        import_paths = []
    if visited is None:
        visited = set()
    if cache is None:
        cache = {}

    # Normalize path
    file_path = file_path.resolve()

    # Check for circular imports
    if file_path in visited:
        raise ImportError(f"Circular import detected: {file_path}")

    # Return cached schema if available
    if file_path in cache:
        return cache[file_path]

    visited.add(file_path)

    # Parse the file
    schema = parse_fdl_file(file_path)

    # Process imports
    imported_enums = []
    imported_messages = []

    for imp in schema.imports:
        # Resolve import path using search paths
        import_path = resolve_import_path(imp.path, file_path, import_paths)

        if import_path is None:
            # Build helpful error message with search locations
            searched = [str(file_path.parent)]
            searched.extend(str(p) for p in import_paths)
            raise ImportError(
                f"Import not found: {imp.path}\n"
                f"  at line {imp.line}, column {imp.column}\n"
                f"  Searched in: {', '.join(searched)}"
            )

        # Recursively resolve the imported file
        imported_schema = resolve_imports(import_path, import_paths, visited.copy(), cache)

        # Collect types from imported schema
        imported_enums.extend(imported_schema.enums)
        imported_messages.extend(imported_schema.messages)

    # Create merged schema with imported types first (so they can be referenced)
    merged_schema = Schema(
        package=schema.package,
        imports=schema.imports,
        enums=imported_enums + schema.enums,
        messages=imported_messages + schema.messages,
    )

    cache[file_path] = merged_schema
    return merged_schema


def parse_args(args: Optional[List[str]] = None) -> argparse.Namespace:
    """Parse command line arguments."""
    parser = argparse.ArgumentParser(
        prog="fory",
        description="FDL (Fory Definition Language) compiler",
    )

    subparsers = parser.add_subparsers(dest="command", help="Available commands")

    # compile command
    compile_parser = subparsers.add_parser(
        "compile",
        help="Compile FDL files to language-specific code",
    )

    compile_parser.add_argument(
        "files",
        nargs="+",
        type=Path,
        metavar="FILE",
        help="FDL files to compile",
    )

    compile_parser.add_argument(
        "--lang",
        type=str,
        default="all",
        help="Comma-separated list of target languages (java,python,cpp,rust,go). Default: all",
    )

    compile_parser.add_argument(
        "--output",
        "-o",
        type=Path,
        default=Path("./generated"),
        help="Output directory. Default: ./generated",
    )

    compile_parser.add_argument(
        "--package",
        type=str,
        default=None,
        help="Override package name from FDL file",
    )

    compile_parser.add_argument(
        "-I",
        "--proto_path",
        "--import_path",
        dest="import_paths",
        action="append",
        type=Path,
        default=[],
        metavar="PATH",
        help="Add a directory to the import search path. Can be specified multiple times.",
    )

    # Language-specific output directories (protoc-style)
    compile_parser.add_argument(
        "--java_out",
        type=Path,
        default=None,
        metavar="DST_DIR",
        help="Generate Java code in DST_DIR",
    )

    compile_parser.add_argument(
        "--python_out",
        type=Path,
        default=None,
        metavar="DST_DIR",
        help="Generate Python code in DST_DIR",
    )

    compile_parser.add_argument(
        "--cpp_out",
        type=Path,
        default=None,
        metavar="DST_DIR",
        help="Generate C++ code in DST_DIR",
    )

    compile_parser.add_argument(
        "--go_out",
        type=Path,
        default=None,
        metavar="DST_DIR",
        help="Generate Go code in DST_DIR",
    )

    compile_parser.add_argument(
        "--rust_out",
        type=Path,
        default=None,
        metavar="DST_DIR",
        help="Generate Rust code in DST_DIR",
    )

    return parser.parse_args(args)


def get_languages(lang_arg: str) -> List[str]:
    """Parse the language argument into a list of languages."""
    if lang_arg == "all":
        return list(GENERATORS.keys())

    languages = [l.strip().lower() for l in lang_arg.split(",")]

    # Validate languages
    invalid = [l for l in languages if l not in GENERATORS]
    if invalid:
        print(f"Error: Unknown language(s): {', '.join(invalid)}", file=sys.stderr)
        print(f"Available: {', '.join(GENERATORS.keys())}", file=sys.stderr)
        sys.exit(1)

    return languages


def compile_file(
    file_path: Path,
    lang_output_dirs: Dict[str, Path],
    package_override: Optional[str] = None,
    import_paths: Optional[List[Path]] = None,
) -> bool:
    """Compile a single FDL file with import resolution.

    Args:
        file_path: Path to the FDL file
        lang_output_dirs: Dictionary mapping language name to output directory
        package_override: Optional package name override
        import_paths: List of import search paths
    """
    print(f"Compiling {file_path}...")

    # Parse and resolve imports
    try:
        schema = resolve_imports(file_path, import_paths)
    except OSError as e:
        print(f"Error reading {file_path}: {e}", file=sys.stderr)
        return False
    except (LexerError, ParseError) as e:
        print(f"Error: {e}", file=sys.stderr)
        return False
    except ImportError as e:
        print(f"Import error: {e}", file=sys.stderr)
        return False

    # Print import info
    if schema.imports:
        print(f"  Resolved {len(schema.imports)} import(s)")

    # Validate merged schema
    errors = schema.validate()
    if errors:
        for error in errors:
            print(f"Error: {error}", file=sys.stderr)
        return False

    # Generate code for each language
    for lang, lang_output in lang_output_dirs.items():
        options = GeneratorOptions(
            output_dir=lang_output,
            package_override=package_override,
        )

        generator_class = GENERATORS[lang]
        generator = generator_class(schema, options)
        files = generator.generate()
        generator.write_files(files)

        for f in files:
            print(f"  Generated: {lang_output / f.path}")

    return True


def cmd_compile(args: argparse.Namespace) -> int:
    """Handle the compile command."""
    # Build language -> output directory mapping
    # Language-specific --{lang}_out options take precedence
    lang_specific_outputs = {
        "java": args.java_out,
        "python": args.python_out,
        "cpp": args.cpp_out,
        "go": args.go_out,
        "rust": args.rust_out,
    }

    # Determine which languages to generate
    lang_output_dirs: Dict[str, Path] = {}

    # First, add languages specified via --{lang}_out (these use direct paths)
    for lang, out_dir in lang_specific_outputs.items():
        if out_dir is not None:
            lang_output_dirs[lang] = out_dir

    # Then, add languages from --lang that don't have specific output dirs
    # These use output_dir/lang pattern
    if args.lang != "all" or not lang_output_dirs:
        # Only use --lang if no language-specific outputs are set, or if --lang is explicit
        languages_from_arg = get_languages(args.lang)
        for lang in languages_from_arg:
            if lang not in lang_output_dirs:
                lang_output_dirs[lang] = args.output / lang

    if not lang_output_dirs:
        print("Error: No target languages specified.", file=sys.stderr)
        print("Use --lang or --{lang}_out options.", file=sys.stderr)
        return 1

    # Validate that all languages are supported
    invalid = [l for l in lang_output_dirs.keys() if l not in GENERATORS]
    if invalid:
        print(f"Error: Unknown language(s): {', '.join(invalid)}", file=sys.stderr)
        print(f"Available: {', '.join(GENERATORS.keys())}", file=sys.stderr)
        return 1

    # Resolve and validate import paths (support comma-separated paths)
    import_paths = []
    for p in args.import_paths:
        # Split by comma to support multiple paths in one option
        for part in str(p).split(","):
            part = part.strip()
            if not part:
                continue
            resolved = Path(part).resolve()
            if not resolved.is_dir():
                print(f"Warning: Import path is not a directory: {part}", file=sys.stderr)
            import_paths.append(resolved)

    # Create output directories
    for out_dir in lang_output_dirs.values():
        out_dir.mkdir(parents=True, exist_ok=True)

    success = True
    for file_path in args.files:
        if not file_path.exists():
            print(f"Error: File not found: {file_path}", file=sys.stderr)
            success = False
            continue

        if not compile_file(file_path, lang_output_dirs, args.package, import_paths):
            success = False

    return 0 if success else 1


def main(args: Optional[List[str]] = None) -> int:
    """Main entry point."""
    parsed = parse_args(args)

    if parsed.command is None:
        print("Usage: fory <command> [options]", file=sys.stderr)
        print("Commands: compile", file=sys.stderr)
        print("Use 'fory <command> --help' for more information", file=sys.stderr)
        return 1

    if parsed.command == "compile":
        return cmd_compile(parsed)

    return 0


if __name__ == "__main__":
    sys.exit(main())
