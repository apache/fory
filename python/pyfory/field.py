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
Field metadata support for Fory serialization.

This module provides the `field()` function for fine-grained control over
serialization behavior per field, following the pattern established by
Rust (`#[fory(...)]` attributes) and Go (`fory:"..."` struct tags).

Example:
    @dataclass
    class User:
        id: int32 = pyfory.field(id=0)                          # Tag ID 0
        name: str = pyfory.field(id=1)                          # Tag ID 1
        email: Optional[str] = pyfory.field(id=2, nullable=True) # Tag ID 2, nullable
        friends: List["User"] = pyfory.field(id=3, ref=True)    # Tag ID 3, ref tracking
        nickname: Optional[str] = pyfory.field(nullable=True)   # Use field name, nullable
        _cache: dict = pyfory.field(ignore=True)                # Use field name, ignored
"""

import dataclasses
from dataclasses import MISSING
from typing import Any, Callable, Dict, Mapping, Optional


# Key used to store Fory metadata in field.metadata
FORY_FIELD_METADATA_KEY = "__fory__"
FORY_OBJECT_METADATA_KEY = "__fory_object__"


@dataclasses.dataclass(frozen=True)
class ForyFieldMeta:
    """
    Fory field metadata extracted from field.metadata.

    Attributes:
        id: Field tag ID. -1 means use field name encoding, >=0 means use tag ID.
        nullable: Whether null flag is written. Default False.
        ref: Whether reference tracking is enabled for this field. Default False.
        ignore: Whether to ignore this field during serialization. Default False.
        dynamic: Whether type info is written for this field. None means auto-detect.
            - None (default): Auto-detect based on type (abstract=True, concrete=mode-dependent)
            - True: Always write type info (support runtime subtypes)
            - False: Never write type info (use declared type's serializer)
    """

    id: int
    nullable: bool = False
    ref: bool = False
    ignore: bool = False
    dynamic: Optional[bool] = None

    def uses_tag_id(self) -> bool:
        """Returns True if this field uses tag ID encoding (id >= 0)."""
        return self.id >= 0


@dataclasses.dataclass(frozen=True)
class ForyObjectMeta:
    """Fory object metadata stored on dataclass types."""

    evolving: bool = True


def dataclass(_cls=None, *, evolving: bool = True, slots: bool = False, **kwargs):
    """Create a dataclass with Fory-specific metadata."""

    def wrap(cls):
        if slots:
            import inspect

            supports_slots = "slots" in inspect.signature(dataclasses.dataclass).parameters
            if supports_slots:
                dc = dataclasses.dataclass(cls, slots=True, **kwargs)
            else:
                dc = dataclasses.dataclass(cls, **kwargs)
                from pyfory.type_util import dataslots

                dc = dataslots(dc)
        else:
            dc = dataclasses.dataclass(cls, **kwargs)
        setattr(dc, FORY_OBJECT_METADATA_KEY, ForyObjectMeta(evolving=evolving))
        return dc

    if _cls is None:
        return wrap
    return wrap(_cls)


def field(
    id: int = -1,
    *,
    nullable: bool = False,
    ref: bool = False,
    ignore: bool = False,
    dynamic: Optional[bool] = None,
    # Standard dataclass.field() options (passthrough)
    default: Any = MISSING,
    default_factory: Optional[Callable[[], Any]] = MISSING,
    init: bool = True,
    repr: bool = True,
    hash: Optional[bool] = None,
    compare: bool = True,
    metadata: Optional[Mapping[str, Any]] = None,
    **kwargs,
) -> Any:
    """
    Create a dataclass field with Fory-specific serialization metadata.

    This wraps dataclasses.field() and stores Fory configuration in field.metadata.

    Args:
        id: Field tag ID (optional, default -1).
            - -1 (default): Use field name with meta string encoding
            - >=0: Use numeric tag ID (more compact, stable across renames)
            Must be unique within the class (except -1).

        nullable: Whether to write null flag for this field.
            - False (default): Skip null flag, field cannot be None
            - True: Write null flag (1 byte overhead), field can be None
            Note: For Optional[T] fields, nullable=True is required.
            Setting nullable=False on Optional[T] raises ValueError.

        ref: Whether to enable reference tracking for this field.
            - False (default): No tracking, skip IdentityMap overhead
            - True: Track references (handles circular refs, shared objects)
            Note: If Fory(ref=False), all fields use ref=False
            regardless of this setting.

        ignore: Whether to ignore this field during serialization.
            - False (default): Field is serialized
            - True: Field is excluded from serialization

        dynamic: Whether to write type info for this field.
            - None (default): Auto-detect based on type and mode
              - Abstract classes: always True (type info must be written)
              - Native mode: True for all types
              - Xlang mode: False for concrete types
            - True: Always write type info (support runtime subtypes)
            - False: Never write type info (use declared type's serializer)

        default, default_factory, init, repr, hash, compare, metadata:
            Standard dataclass.field() parameters, passed through.

        **kwargs: Additional arguments forwarded to dataclasses.field().

    Returns:
        A dataclass field descriptor with Fory metadata attached.

    Example:
        @dataclass
        class User:
            name: str = pyfory.field(0)                                    # Tag ID 0
            email: Optional[str] = pyfory.field(1, nullable=True)          # Tag ID 1
            friends: List["User"] = pyfory.field(2, ref=True, default_factory=list)
            nickname: Optional[str] = pyfory.field(nullable=True)          # Use field name
            _cache: dict = pyfory.field(ignore=True, default_factory=dict) # Use field name
            shape: Shape = pyfory.field(3, dynamic=True)                   # Force type info
    """
    # Validate id
    if not isinstance(id, int):
        raise TypeError(f"id must be an int, got {type(id).__name__}")
    if id < -1:
        raise ValueError(f"id must be >= -1, got {id}")

    # Build Fory metadata
    fory_meta = ForyFieldMeta(
        id=id,
        nullable=nullable,
        ref=ref,
        ignore=ignore,
        dynamic=dynamic,
    )

    # Merge with user-provided metadata
    combined_metadata = dict(metadata) if metadata else {}
    combined_metadata[FORY_FIELD_METADATA_KEY] = fory_meta

    # Create dataclass field with combined metadata
    return dataclasses.field(
        default=default,
        default_factory=default_factory,
        init=init,
        repr=repr,
        hash=hash,
        compare=compare,
        metadata=combined_metadata,
        **kwargs,
    )


def extract_field_meta(dataclass_field: dataclasses.Field) -> Optional[ForyFieldMeta]:
    """
    Extract ForyFieldMeta from a dataclass field.

    Args:
        dataclass_field: A dataclass Field object.

    Returns:
        ForyFieldMeta if present, None otherwise.
    """
    if dataclass_field.metadata is None:
        return None
    return dataclass_field.metadata.get(FORY_FIELD_METADATA_KEY)


def extract_object_meta(cls: type) -> Optional[ForyObjectMeta]:
    """Extract ForyObjectMeta from a dataclass type if present."""
    return getattr(cls, FORY_OBJECT_METADATA_KEY, None)


def validate_field_metas(
    cls: type,
    field_metas: Dict[str, ForyFieldMeta],
    type_hints: Dict[str, type],
) -> None:
    """
    Validate field metadata for a dataclass.

    Checks:
    - Tag IDs are unique (no duplicate IDs >= 0)
    - Optional[T] fields have nullable=True

    Args:
        cls: The dataclass type.
        field_metas: Dict mapping field name to ForyFieldMeta.
        type_hints: Dict mapping field name to type hint.

    Raises:
        ValueError: If validation fails.
    """
    from pyfory.type_util import is_optional_type

    # Check tag ID uniqueness
    tag_ids_seen: Dict[int, str] = {}
    for field_name, meta in field_metas.items():
        if meta.id >= 0:
            if meta.id in tag_ids_seen:
                raise ValueError(
                    f"Duplicate tag ID {meta.id} in class {cls.__name__}: fields '{tag_ids_seen[meta.id]}' and '{field_name}' have the same ID"
                )
            tag_ids_seen[meta.id] = field_name

    # Check nullable consistency with Optional types
    for field_name, meta in field_metas.items():
        if field_name not in type_hints:
            continue
        type_hint = type_hints[field_name]
        if is_optional_type(type_hint) and not meta.nullable:
            raise ValueError(
                f"Field '{field_name}' in class {cls.__name__} is Optional[T] but nullable=False. Optional fields must have nullable=True."
            )
