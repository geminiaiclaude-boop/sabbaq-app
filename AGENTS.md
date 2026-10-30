# AGENTS.md — Sabbaq (سبّاق)

Any AI agent or developer working in this repository must read
[`docs/MAINTAINERS_HANDBOOK.md`](docs/MAINTAINERS_HANDBOOK.md) first: rules, layout, how to edit,
check, merge, build and release. It is written in Arabic (the owner's language); answer the owner
in Arabic.

The non-negotiable rules, in short:

1. Never merge or build a release without the owner's literal go-ahead ("ادمج" / "ابنِ").
2. All current fixes go on the one open working branch and its pull request.
3. The product name is Sabbaq / سبّاق only. The reference app's name never appears in the
   repository, commits, branches or keys (`BrandNameTest`).
4. The accept engine and floating control match the reference app's behaviour; close every
   difference, read the decompiled reference (kept outside the repository) before claiming any.
5. Missing data never rejects an offer; an unreachable server never stops a paying subscriber.
6. Never edit `.github/workflows/`; never commit secrets; never run Gradle on the owner's shared
   host. Check locally with `scripts/core_engine_test.sh`, `scripts/app_compile_check.sh` and
   `scripts/resource_check.py`; full builds happen in CI.
7. New server changes are new numbered migrations in `supabase/migrations/`, with tests.
8. Fix every defect you find in the same round, and state plainly what was not verified.
