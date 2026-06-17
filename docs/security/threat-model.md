---
title: Threat Model
sidebar_position: 2
---

This document describes Apache Fory's project-level security boundaries and
non-goals. It is the high-level entry point for Fory security models; concrete
untrusted deserialization classification rules live in the
[deserialization security model](deserialization.md).

Fory is an in-process serialization library. Applications link Fory into their
own process, configure serializers and type policies, and call Fory APIs to
serialize application-owned objects or deserialize encoded Fory data. Fory does
not provide a standalone network service, daemon, authentication system, or
transport protocol.

Fory can generate service companions for application-provided gRPC runtimes.
Those companions provide Fory serialization for request and response objects;
the application and gRPC stack still own listeners, channels, credentials,
authentication, authorization, deadlines, retries, and transport lifecycle.

## Trust Boundaries

Fory's primary security boundary is encoded bytes or streams passed to
deserialization APIs from untrusted or partially trusted sources. The embedding
application owns where those bytes come from and which Fory configuration,
registered types, schemas, and policies are used to read them.

Fory security boundaries include:

- Runtime safety, including avoiding crashes, panics, undefined behavior, and
  out-of-bounds memory access.
- Resource ownership, including memory, CPU progress, stream buffers, native
  allocations, callbacks, and retained read-side state.
- Explicit Fory policy checks, such as class, type, function, method,
  registration, or deserialization policies that restrict what may be
  materialized.
- Cleanup boundaries, where state created during a failed root operation must
  not leak into later operations.

The [deserialization security model](deserialization.md) defines how to
classify these boundaries for untrusted deserialization paths.

## Non-Goals

Fory does not provide:

- Encoded-data authenticity, integrity, confidentiality, signing, MACs, or
  encryption.
- Transport security or protection for bytes while they are stored or moved
  outside Fory, including transport security for generated service companions.
- Application-level authorization or validation for the business meaning of a
  successfully deserialized value.
- A sandbox for user-registered classes, functions, constructors, setters,
  finalizers, or other application-owned logic.

Applications that receive Fory data from untrusted sources should authenticate
or integrity-check those bytes before passing them to Fory when authenticity or
tamper resistance matters.

## Downstream Responsibilities

Applications are responsible for:

- Choosing whether a byte source is trusted enough for the configured
  deserialization mode.
- Keeping class or type registration enabled for untrusted data unless another
  explicit Fory policy owns the accepted type surface.
- Registering only types and serializers that are safe for the application's
  trust boundary.
- Configuring depth and resource limits for the largest data shape the
  application intends to accept.
- Treating cross-language peers and schemas as part of the application's trust
  relationship.

Disabling registration or using dynamic deserialization on trusted data is a
configuration choice. For untrusted data, bypassing an explicit Fory policy,
crashing, leaking resources, retaining attacker-controlled state, or allocating
disproportionately remains security-relevant as described in the
[deserialization security model](deserialization.md).
