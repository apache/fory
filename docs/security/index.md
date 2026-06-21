---
title: Security
sidebar_position: 1
---

This directory documents Apache Fory security models and security invariants. It
is not a vulnerability disclosure area and does not list CVE details, exploit
samples, issue timelines, or implementation history.

Security model documents describe how Fory should classify and prevent security
risks while preserving the performance characteristics expected from Fory
serialization runtimes.

## Models

- [Threat Model](threat-model.md): project-level trust boundaries, non-goals,
  and downstream responsibilities.
- [Deserialization Security Model](deserialization.md): concrete rules for
  classifying and preventing untrusted deserialization risks.

For vulnerability reporting, see the repository
[security policy](../../SECURITY.md).
