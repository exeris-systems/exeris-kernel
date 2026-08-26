# Licensing

This file is the answer to "what licence is this, exactly" — the question a legal
review asks before a dependency is approved. `LICENSE` is the operative text;
this is the map.

## This repository

| What | Licence | SPDX |
|---|---|---|
| Everything in `exeris-systems/exeris-kernel` — all reactor modules, tests, TCK, tools, docs | Apache License, Version 2.0 | `Apache-2.0` |

The text in `LICENSE` is the **unmodified** Apache License 2.0. There is no addendum,
no additional condition, and no field-of-use restriction. Every source file carries
`SPDX-License-Identifier: Apache-2.0`, so an automated scan and a human reading the
repository root reach the same answer.

Published Maven coordinates under the `eu.exeris` namespace that originate from this
repository carry the same licence, declared in `exeris-kernel-parent/pom.xml`:

    eu.exeris:exeris-kernel-spi
    eu.exeris:exeris-kernel-core
    eu.exeris:exeris-kernel-community
    eu.exeris:exeris-kernel-community-kafka
    eu.exeris:exeris-kernel-community-testkit
    eu.exeris:exeris-kernel-tck
    eu.exeris:exeris-kernel-diagnostics-cli
    eu.exeris:exeris-kernel-bom
    eu.exeris:exeris-kernel-parent
    eu.exeris:exeris-kernel-build-config

## History — read this if you are auditing an older version

Releases up to and including **v0.11.0** were distributed under *Apache License 2.0
with the Commons Clause*, which is **not** an open-source licence: it withheld the
right to sell the software as a competing product. If your scan flagged an earlier
Exeris artifact as non-permissive, that finding was correct for that version.

Two things changed together, and the second is not cosmetic:

1. The Commons Clause condition was removed.
2. The Apache 2.0 text itself was replaced with the canonical one. The text shipped
   through v0.11.0 was an abridged paraphrase — it dropped, among other clauses, the
   definition of "submitted" in section 1, the two NOTICE sentences in section 4(d),
   and the whole "Notwithstanding the above" sentence in section 5. It was labelled
   "Apache License Version 2.0" while not being it. `LICENSE` now matches the text
   published at <https://www.apache.org/licenses/LICENSE-2.0.txt> byte for byte.

## Not in this repository

The enterprise tier (`exeris-kernel-enterprise` — `io_uring`, QUIC, slab pools, and
the native FFM extensions) is a **separate closed-source distribution** under a
commercial licence. It is not published to any public repository, no artifact in the
`eu.exeris` public namespace contains it, and nothing here depends on it. Its terms
are in `LICENSE-ENTERPRISE`, kept at this root so the boundary is documented where a
reader looks for it, not because the code is here.

Implementing the published SPI — including a competing transport, a QUIC engine, or
a native driver — is permitted and intended. The SPI is a contract, not a moat.

## Trademarks

Apache-2.0 section 6 grants no rights in trade names, trademarks, service marks, or
product names. Dropping the Commons Clause therefore does **not** put the name
"Exeris" into the grant. The marks are held by Arkadiusz Przychocki, trading as
Exeris Systems, and will transfer to the company on incorporation. See
`TRADEMARK.md` for what use is permitted without a separate agreement — the short
version is that naming the software to refer to it needs no permission, and shipping
something else under its name does.

## Third-party dependencies

Every published artifact carries a CycloneDX SBOM, attached under classifier
`cyclonedx` and published beside the jar, listing the exact dependency set that
artifact resolves. Read that rather than a transcribed table — a table is a claim
about the dependency set, and the SBOM is generated from it.

## Contributions

Contributions are accepted under a Contributor Licence Agreement. See
`CONTRIBUTING.md` for what it asks for and why.

## Inquiries

legal@exeris.eu
