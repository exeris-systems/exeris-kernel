---
name: community-tck-first-review
description: 'Review Exeris changes with TCK-first discipline for SPI/observable behavior impact, abstract TCK requirements, binding tests, and semantic coverage.'
argument-hint: 'SPI change, provider semantics, or lifecycle behavior change'
---

Review this change with Exeris TCK-first discipline.

Treat TCK as the contract judge.

For this change:
- classify contract impact as NO_CONTRACT_CHANGE, CONTRACT_EXTENSION, or CONTRACT_BREAKING_CHANGE,
- identify affected SPI contracts or observable lifecycle semantics,
- determine whether Abstract*Tck must be added or updated,
- determine whether Core/Community bindings must be added or updated,
- verify that tests prove contract semantics, not only happy path,
- call out zero-alloc, ref-count, leak, or stable error-semantics coverage where relevant.

Output:
1. Contract impact class
2. Affected contracts/symbols
3. Abstract TCK requirements
4. Binding test requirements
5. Weak/missing semantics coverage
6. Verdict: APPROVE / CONDITIONAL / REJECT
7. Minimal merge-blocking fixes
