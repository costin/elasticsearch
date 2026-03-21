#!/usr/bin/env bash

gh pr create \
  --repo elastic/elasticsearch \
  --head costin:esql/distributed-execution-hardening \
  --base main \
  --title "[ESQL] Distributed execution hardening" \
  --body "$(cat <<'EOF'
Adds resilience tests and makes HTTP 500 retryable in the external
source pipeline. S3 intermittent 500s are common in production and
should be retried like 429/503 errors — without this, a single
transient 500 during a distributed query fails the entire query.

This PR covers the unit-level hardening layer: retry policy fix,
drain error propagation tests, fault injection infrastructure
(slow responses, truncated transfers, connection resets), and
buffer lifecycle tests. Multi-node integration resilience tests
(node failure mid-query, 1000+ split stress, heterogeneous splits,
partial results under failure) are planned as follow-up work.

Developed with AI-assisted tooling
EOF
)" \
  --label "v9.4.0" --label ":Analytics/ES|QL" --label ">enhancement" --label "ES|QL|DS" \
  --reviewer bpintea

# After creation, enable auto-merge with:
# gh pr merge <pr-number> --repo elastic/elasticsearch --auto --squash
