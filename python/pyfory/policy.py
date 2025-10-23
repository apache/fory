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


class DeserializationPolicy:
    """Deserialization Security Policy for PyFory.

    DeserializationPolicy provides a comprehensive security layer for controlling deserialization
    behavior, similar to how pickle.Unpickler can be customized but with finer-grained
    control over the deserialization process.

    Comparison with pickle.Unpickler
    --------------------------------
    Python's pickle.Unpickler provides basic security through the find_class() method,
    which can be overridden to control class imports:

        >>> class SafeUnpickler(pickle.Unpickler):
        ...     def find_class(self, module, name):
        ...         # Only allow safe modules
        ...         if module in ('builtins', 'datetime'):
        ...             return super().find_class(module, name)
        ...         raise ValueError(f"Unsafe module: {module}")

    Fory's DeserializationPolicy provides MORE granular control:

    +---------------------------+----------------------+----------------------------+
    | Security Feature          | pickle.Unpickler     | Fory DeserializationPolicy           |
    +---------------------------+----------------------+----------------------------+
    | Class import control      | ✓ find_class()       | ✓ validate_class()         |
    | Function import control   | ✗ (via find_class)   | ✓ validate_function()      |
    | Method validation         | ✗                    | ✓ validate_method()        |
    | Module import control     | ✗                    | ✓ validate_module()        |
    | Instantiation control     | ✗                    | ✓ authorize_instantiation()|
    | __reduce__ interception   | ✗                    | ✓ intercept_reduce_call()  |
    | Post-reduce inspection    | ✗                    | ✓ inspect_reduced_object() |
    | __setstate__ interception | ✗                    | ✓ intercept_setstate()     |
    | Object replacement        | ✗                    | ✓ (return from validators) |
    | State sanitization        | ✗                    | ✓ (modify in-place)        |
    | Local class/function      | ✗                    | ✓ (is_local flag)          |
    +---------------------------+----------------------+----------------------------+

    Example: Blocking subprocess.Popen with pickle vs Fory:

        # pickle.Unpickler - only catches class imports
        class SafeUnpickler(pickle.Unpickler):
            def find_class(self, module, name):
                if module == 'subprocess' and name == 'Popen':
                    raise ValueError("Blocked")
                return super().find_class(module, name)

        # Problem: Can't catch Popen invoked via __reduce__!
        # A malicious payload can serialize: (subprocess.Popen, (["rm", "-rf", "/"],))

        # Fory DeserializationPolicy - catches both imports AND reduce invocations
        class SafeChecker(DeserializationPolicy):
            def validate_class(self, cls, is_local, **kwargs):
                if cls.__module__ == 'subprocess' and cls.__name__ == 'Popen':
                    raise ValueError("Blocked")
                return None

            def intercept_reduce_call(self, callable_obj, args, **kwargs):
                if callable_obj.__name__ == 'Popen':
                    raise ValueError("Blocked at invocation!")
                return None

    Security Context
    ----------------
    Deserialization of untrusted data is inherently dangerous. Malicious payloads can:
    - Import and instantiate arbitrary classes (e.g., subprocess.Popen)
    - Execute arbitrary code through __reduce__ or __setstate__
    - Access sensitive modules or perform unauthorized operations
    - Cause denial of service through resource exhaustion

    This DeserializationPolicy interface allows users to implement custom security policies by
    subclassing and overriding specific hook methods. Each hook is called at a critical
    point during deserialization, allowing inspection, replacement, or rejection of
    dangerous constructs.

    Hook Categories
    ---------------
    1. **Instantiation Authorization Hooks** (Guards)
       - Control which classes can be instantiated
       - Raise exception to block, return None to allow

    2. **Reference Validation Hooks** (Validators)
       - Validate deserialized type/function/module references
       - Return None to accept original, return object to replace, raise exception to block,

    3. **Protocol Interception Hooks** (Interceptors)
       - Intercept pickle protocol operations (__reduce__, __setstate__)
       - Return None to continue, return object to replace, or modify in-place, raise exception to block,

    Usage Example
    -------------
    >>> class SafeDeserializationPolicy(DeserializationPolicy):
    ...     ALLOWED_MODULES = {'builtins', 'datetime', 'decimal'}
    ...
    ...     def validate_module(self, module_name, **kwargs):
    ...         # Reject imports from disallowed modules
    ...         if module_name.split('.')[0] not in self.ALLOWED_MODULES:
    ...             raise ValueError(f"Module {module_name} is not allowed")
    ...         return None  # Accept
    ...
    ...     def validate_class(self, cls, is_local, **kwargs):
    ...         # Reject dangerous built-in classes
    ...         if cls.__name__ in ('eval', 'exec', 'compile'):
    ...             raise ValueError(f"Class {cls} is forbidden")
    ...         return None  # Accept
    ...
    ...     def intercept_reduce_call(self, callable_obj, args, **kwargs):
    ...         # Log all __reduce__ callables for audit
    ...         print(f"Reducing with {callable_obj.__name__}({args})")
    ...         return None  # Proceed normally
    ...
    >>> fory = Fory(checker=SafeDeserializationPolicy())

    Thread Safety
    -------------
    DeserializationPolicy instances should be thread-safe if shared across multiple Fory instances.
    The default implementation is stateless and thread-safe.

    Performance Considerations
    --------------------------
    - Hooks are called frequently during deserialization
    - Keep validation logic fast to avoid performance degradation
    - Cache validation results when possible (e.g., maintain allowed/blocked sets)
    - Avoid I/O operations in hooks unless necessary

    See Also
    --------
    - Python's pickle module security warnings: https://docs.python.org/3/library/pickle.html
    - Fory documentation on secure deserialization: docs/guide/security.md
    """

    # ============================================================================
    # Instantiation Authorization Hooks (Guards)
    # ============================================================================

    def authorize_instantiation(self, cls, **kwargs):
        """Authorize instantiation of a class during deserialization.

        This hook is called before creating an instance of any class during deserialization.
        It acts as a security gate to prevent instantiation of dangerous classes.

        When Called
        -----------
        - Before creating instances via cls.__new__(cls) in deserializers
        - For both dataclass and regular object deserialization

        Security Use Cases
        ------------------
        - Whitelist/blacklist specific classes by name or module
        - Reject classes that could execute code in __init__ or __new__
        - Prevent resource-exhausting classes (e.g., large buffers, threads)
        - Log instantiation attempts for security auditing

        Args:
            cls (type): The class about to be instantiated.
            **kwargs: Reserved for future extensions.

        Raises:
            Exception: Raise any exception to block instantiation. The exception
                      will propagate to the caller of Fory.deserialize().

        Returns:
            None: Always return None to authorize. This method is a guard, not a transformer.

        Example:
            >>> class WhitelistChecker(DeserializationPolicy):
            ...     ALLOWED = {'MyClass', 'SafeDataClass'}
            ...
            ...     def authorize_instantiation(self, cls, **kwargs):
            ...         if cls.__name__ not in self.ALLOWED:
            ...             raise ValueError(f"Class {cls.__name__} not whitelisted")

        Note:
            This method was previously named check_read_allowed and check_create_object.
            Those names are kept as aliases for backward compatibility.
        """
        pass

    # ============================================================================
    # Reference Validation Hooks (Validators)
    # ============================================================================

    def validate_class(self, cls, *, is_local: bool, **kwargs):
        """Validate a deserialized class reference.

        This hook is called after a class reference has been deserialized (either by
        importing from a module or reconstructing a local class), but before it is used.
        It allows inspection, replacement, or rejection of class references.

        When Called
        -----------
        - After importing global classes via importlib
        - After reconstructing local classes from serialized code
        - Before the class is stored or used in further deserialization

        Security Use Cases
        ------------------
        - Block dangerous classes (subprocess.Popen, os.system, etc.)
        - Replace untrusted classes with safe alternatives
        - Validate that local classes match expected signatures
        - Implement class versioning/migration logic

        Args:
            cls (type): The deserialized class object.
            is_local (bool): True if the class is a local class (defined in __main__
                           or within a function/method scope), False if it's a global
                           class from an importable module.
            **kwargs: Reserved for future extensions.

        Returns:
            None: Return None to accept the class as-is.
            type: Return a different class to replace the original. The replacement
                 class will be used instead for deserialization.

        Raises:
            Exception: Raise any exception to reject the class and abort deserialization.

        Example:
            >>> class MigrationChecker(DeserializationPolicy):
            ...     def validate_class(self, cls, is_local, **kwargs):
            ...         # Migrate old class to new class
            ...         if cls.__name__ == 'OldUserClass':
            ...             return NewUserClass
            ...         # Block dangerous classes
            ...         if cls.__module__ == 'subprocess':
            ...             raise ValueError("subprocess classes not allowed")
            ...         return None  # Accept

        Note:
            This method was previously named check_class. That name is kept as an
            alias for backward compatibility.
        """
        pass

    def validate_function(self, func, is_local: bool, **kwargs):
        """Validate a deserialized function reference.

        This hook is called after a function has been deserialized (either by importing
        from a module or reconstructing from serialized code), but before it is used.

        When Called
        -----------
        - After importing global functions via importlib
        - After reconstructing local functions/lambdas from marshalled code
        - Before the function is stored or called

        Security Use Cases
        ------------------
        - Block dangerous built-in functions (eval, exec, compile, __import__)
        - Validate that reconstructed functions have expected signatures
        - Replace untrusted functions with safe stubs
        - Audit function imports for security logging

        Args:
            func (function): The deserialized function object.
            is_local (bool): True if the function is local (defined in __main__ or
                           within a function scope), False if it's a global function.
            **kwargs: Reserved for future extensions.

        Returns:
            None: Return None to accept the function as-is.
            function: Return a different function to replace the original.

        Raises:
            Exception: Raise any exception to reject the function.

        Example:
            >>> class SafeFunctionChecker(DeserializationPolicy):
            ...     BLOCKED = {'eval', 'exec', 'compile', '__import__'}
            ...
            ...     def validate_function(self, func, is_local, **kwargs):
            ...         if func.__name__ in self.BLOCKED:
            ...             raise ValueError(f"Function {func.__name__} is forbidden")
            ...         return None

        Note:
            This method was previously named check_function. That name is kept as an
            alias for backward compatibility.
        """
        pass

    def validate_method(self, method, is_local: bool, **kwargs):
        """Validate a deserialized method reference.

        This hook is called after a method has been deserialized (either by importing
        or reconstructing), but before it is used.

        When Called
        -----------
        - After deserializing bound methods
        - After reconstructing local methods from serialized code
        - Before the method is stored or called

        Security Use Cases
        ------------------
        - Validate that methods belong to expected classes
        - Block methods that could perform dangerous operations
        - Replace methods with safer alternatives

        Args:
            method (method): The deserialized bound method object.
            is_local (bool): True if the method's class is local, False if global.
            **kwargs: Reserved for future extensions.

        Returns:
            None: Return None to accept the method as-is.
            method: Return a different method to replace the original.

        Raises:
            Exception: Raise any exception to reject the method.

        Example:
            >>> class MethodChecker(DeserializationPolicy):
            ...     def validate_method(self, method, is_local, **kwargs):
            ...         # Block methods from dangerous classes
            ...         if method.__self__.__class__.__name__ == 'FileRemover':
            ...             raise ValueError("FileRemover methods not allowed")
            ...         return None

        Note:
            This method was previously named check_method. That name is kept as an
            alias for backward compatibility.
        """
        pass

    def validate_module(self, module_name: str, **kwargs):
        """Validate a deserialized module reference.

        This hook is called after a module has been imported during deserialization,
        but before it is used.

        When Called
        -----------
        - After importing modules via importlib.import_module()
        - Before the module is stored or its contents accessed

        Security Use Cases
        ------------------
        - Whitelist/blacklist modules by name or prefix
        - Prevent imports of system modules (os, subprocess, sys, etc.)
        - Replace modules with safe alternatives or mocks
        - Audit module imports for security logging

        Args:
            module_name (str): The name of the imported module (e.g., 'os.path').
            **kwargs: Reserved for future extensions.

        Returns:
            None: Return None to accept the module as-is.
            module: Return a different module object to replace the original.

        Raises:
            Exception: Raise any exception to reject the module import.

        Example:
            >>> class ModuleWhitelistChecker(DeserializationPolicy):
            ...     ALLOWED = {'builtins', 'datetime', 'decimal', 'collections'}
            ...
            ...     def validate_module(self, module_name, **kwargs):
            ...         root = module_name.split('.')[0]
            ...         if root not in self.ALLOWED:
            ...             raise ValueError(f"Module {module_name} not whitelisted")
            ...         return None

        Note:
            This method was previously named check_module. That name is kept as an
            alias for backward compatibility.
        """
        pass

    # ============================================================================
    # Protocol Interception Hooks (Interceptors)
    # ============================================================================

    def intercept_reduce_call(self, callable_obj, args, **kwargs):
        """Intercept and validate __reduce__ protocol callable invocation.

        This hook is called when deserializing an object that was serialized using the
        __reduce__ or __reduce_ex__ protocol, right before the callable is invoked
        to reconstruct the object.

        When Called
        -----------
        - During deserialization of objects using __reduce__/__reduce_ex__
        - Before callable_obj(*args) is executed
        - After the callable and args have been deserialized

        Security Use Cases
        ------------------
        - Block dangerous callables (eval, exec, os.system, subprocess.Popen)
        - Validate that callables match expected signatures
        - Inspect arguments for malicious payloads
        - Return pre-constructed safe objects to skip callable invocation
        - Log reduce operations for auditing

        Args:
            callable_obj (callable): The callable that will be invoked to reconstruct
                                    the object (typically a class or factory function).
            args (tuple): The arguments that will be passed to the callable.
            **kwargs: Reserved for future extensions.

        Returns:
            None: Return None to proceed with normal callable invocation (callable_obj(*args)).
            object: Return an object to use directly, skipping the callable invocation.
                   This allows you to construct safe replacement objects.

        Raises:
            Exception: Raise any exception to reject the callable and abort deserialization.

        Example:
            >>> class ReduceChecker(DeserializationPolicy):
            ...     def intercept_reduce_call(self, callable_obj, args, **kwargs):
            ...         # Block subprocess.Popen
            ...         if callable_obj.__name__ == 'Popen':
            ...             raise ValueError("Popen not allowed")
            ...
            ...         # Audit all reduce operations
            ...         import logging
            ...         logging.info(f"Reducing with {callable_obj}({args})")
            ...
            ...         return None  # Proceed normally

        Note:
            This is one of the most critical security hooks, as __reduce__ is the primary
            vector for arbitrary code execution in pickle-based attacks.

            This method was previously named check_reduce_callable. That name is kept
            as an alias for backward compatibility.
        """
        pass

    # Backward compatibility aliases
    def check_reduce_callable(self, callable_obj, args, **kwargs):
        """Deprecated: Use intercept_reduce_call instead.

        This method is kept for backward compatibility. New code should use
        intercept_reduce_call for clarity.
        """
        return self.intercept_reduce_call(callable_obj, args, **kwargs)

    def inspect_reduced_object(self, obj, **kwargs):
        """Inspect and validate an object after __reduce__ protocol reconstruction.

        This hook is called after an object has been reconstructed using the __reduce__
        protocol, allowing final inspection, modification, or replacement.

        When Called
        -----------
        - After callable_obj(*args) has been executed
        - After state has been restored (if applicable)
        - After list/dict items have been added (if applicable)
        - Before the object is returned to the deserializer

        Security Use Cases
        ------------------
        - Validate reconstructed object's state
        - Replace objects that pass callable checks but are still unsafe
        - Sanitize object attributes
        - Audit reconstructed objects for security logging

        Args:
            obj (object): The reconstructed object.
            **kwargs: Reserved for future extensions.

        Returns:
            None: Return None to accept the object as-is.
            object: Return a different object to replace the original.

        Raises:
            Exception: Raise any exception to reject the object.

        Example:
            >>> class PostReduceChecker(DeserializationPolicy):
            ...     def inspect_reduced_object(self, obj, **kwargs):
            ...         # Validate that file handles are read-only
            ...         if isinstance(obj, io.IOBase) and obj.writable():
            ...             raise ValueError("Writable file handles not allowed")
            ...         return None

        Note:
            This hook provides a last line of defense after reduce reconstruction.

            This method was previously named check_restored_reduced_object. That name
            is kept as an alias for backward compatibility.
        """
        pass

    # Backward compatibility aliases
    def check_restored_reduced_object(self, obj, **kwargs):
        """Deprecated: Use inspect_reduced_object instead.

        This method is kept for backward compatibility. New code should use
        inspect_reduced_object for clarity.
        """
        return self.inspect_reduced_object(obj, **kwargs)

    def intercept_setstate(self, obj, state, **kwargs):
        """Intercept and validate __setstate__ protocol before state restoration.

        This hook is called when deserializing an object that implements __setstate__,
        right before the state is restored to the object. It allows inspection and
        modification of the state dictionary.

        When Called
        -----------
        - Before obj.__setstate__(state) is called
        - After the object has been instantiated (via __new__)
        - After the state dict has been deserialized

        Security Use Cases
        ------------------
        - Inspect state for malicious values
        - Sanitize or filter dangerous state attributes
        - Validate state against expected schema
        - Modify state to enforce security policies
        - Audit state restoration for logging

        Args:
            obj (object): The object whose state is about to be restored.
            state (dict or other): The state to be restored (typically a dict, but can
                                  be any object depending on __setstate__ implementation).
            **kwargs: Reserved for future extensions.

        Returns:
            None: Always return None. Modify the state dict in-place if needed.

        Raises:
            Exception: Raise any exception to reject the state and abort deserialization.

        Example:
            >>> class SetStateChecker(DeserializationPolicy):
            ...     def intercept_setstate(self, obj, state, **kwargs):
            ...         # Block if state contains dangerous attributes
            ...         if isinstance(state, dict):
            ...             dangerous_attrs = {'__code__', '__globals__', '_eval'}
            ...             if any(attr in state for attr in dangerous_attrs):
            ...                 raise ValueError("State contains dangerous attributes")
            ...
            ...             # Sanitize: remove private attributes
            ...             state.clear()
            ...             state.update({k: v for k, v in state.items()
            ...                          if not k.startswith('_')})

        Note:
            This hook can modify the state dict in-place. Changes will be reflected
            when __setstate__ is called.

            This method was previously named check_setstate. That name is kept as an
            alias for backward compatibility.
        """
        pass

    # Backward compatibility alias
    def check_setstate(self, obj, state, **kwargs):
        """Deprecated: Use intercept_setstate instead.

        This method is kept for backward compatibility. New code should use
        intercept_setstate for clarity.
        """
        return self.intercept_setstate(obj, state, **kwargs)


DEFAULT_POLICY = DeserializationPolicy()
