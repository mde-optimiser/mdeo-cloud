# Condition Dependency Status Report

This document records the status of dependency tracking for the various constraint types
used in the `MatchPlanBuilder` ordering algorithm.

## Summary

All constraint types have their dependency nodes **computed** in the existing code.
However, two constraint types — **deferred property filters** and **where constraints** —
are not yet incorporated into the component-ordering tiebreaker, so they do not yet
benefit from early-evaluation scheduling in the inter-component sort.

---

## Constraint types and dependency status

### 1. PAC / NAC islands (positive and negative application conditions)

**Dependency computation:** ✅ Fully implemented and used in ordering.

- `IslandTraversalUtils.findAnchorNames()` identifies the set of main-pattern nodes
  whose coverage is required before the condition can be evaluated (the *anchors*).
- `computeInjectiveRequiredNodes()` adds any further main-pattern nodes needed for
  injective constraints embedded inside the condition.
- Both sets are returned by `pendingConditionRequiredNodes()` and used in
  `minConditionCostUnlockedBy()` — the component-ordering tiebreaker.
- Conditions are emitted inline (via `tryInlineConditions`) as soon as all their
  required nodes are covered.
- Disconnected conditions (no anchors) are emitted as soon as their injective
  siblings are covered, and they are always placed before multi-anchor conditions.

**Status:** No action needed.

---

### 2. Forbid / require orphan links

**Dependency computation:** ✅ Fully implemented and used in ordering.

- Orphan links have exactly two dependency nodes: their two main-pattern endpoints.
- `pendingConditionRequiredNodes()` returns both endpoints.
- Emitted inline as soon as both endpoints are covered.
- Treated as the cheapest possible condition (cost = 1) in the tiebreaker.

**Status:** No action needed.

---

### 3. Injective match constraints (PostMatchFilter.InjectiveConstraint)

**Dependency computation:** ✅ Implemented; constraints are placed as late as necessary.

- Injective constraints (`sprint1 != sprint2`, etc.) are added to `postMatchFilters`
  via `addInjectiveConstraints()` at the end of plan construction.
- Within conditions the injective requirements are tracked per-node via
  `buildConditionInjectiveConstraints()` and emitted immediately after the relevant
  node is reached in the island traversal.
- The single-node NAC optimisation (`canOmitNacInjectiveConstraint`) eliminates
  redundant injective guards when a forbid orphan link already provides the same
  guarantee.

**Status:** No action needed.

---

### 4. Deferred (non-inlined) property filters (BaseStep.DeferredPropertyConstraint)

**Dependency computation:** ⚠️ Computed but **not used in the inter-component ordering tiebreaker**.

- When a property filter references other instances or pattern variables that are not
  yet covered at the time the owning instance is first encountered, the filter is placed
  in `deferredProperties` via `addInlinePropertyConstraints()`.
  The referenced nodes are identified by `nodeAnalyzer.findReferencedNodes(property.value)`.
- These deferred constraints are currently emitted in a single batch via
  `addDeferredPropertyConstraints()` **after all instances are covered**, regardless of
  when their specific dependencies become satisfied.
- Their dependency sets are **not included** in `PendingCondition` / `allConditions`,
  so they have no influence on `minConditionCostUnlockedBy()` or the component ordering.

**Impact:** A deferred property filter whose dependencies are satisfied after the first
component is processed could, in principle, be emitted inline between components and prune
traversers earlier. In practice this matters only when the filter references nodes in
separate components and both components have been processed but the filter has not yet fired.

**Assumption applied:** Treat as depending on **all** matchable instances (i.e. emit only
after all components have been processed), which is the current behaviour.

---

### 5. Where-clause filters (BaseStep.WhereFilter / PostMatchFilter.CrossNodeWhereClause)

**Dependency computation:** ⚠️ Computed but **not used in the inter-component ordering tiebreaker**.

- `addWhereClauses()` computes `referencedMatchable` via
  `nodeAnalyzer.findReferencedNodes(clause.whereClause.expression)` for each where clause.
- Single-node where clauses are emitted as `BaseStep.WhereFilter` at the end of the plan.
- Multi-node where clauses are promoted to `PostMatchFilter.CrossNodeWhereClause` because
  their multi-instance dependencies cannot be expressed as a single inline step.
- Neither is included in `allConditions`, so neither influences component ordering.

**Impact:** A single-node where clause (e.g. `where workItem.effort > 5`) could be
inlined right after its owning instance is covered, rather than being deferred to the end
of all steps. Multi-node where clauses cannot be made earlier without restructuring the
plan into a post-join filter.

**Assumption applied:** Treat as depending on **all** matchable instances (i.e. emit at the
end), which is the current behaviour for both subtypes.

---

## Recommendation

The two gaps identified above (deferred property filters and where constraints) represent
potential future optimisations, not correctness issues:

1. **Deferred property filters** could be re-evaluated inline after each subsequent instance
   is covered (similar to how `tryInlineConditions` handles PAC/NAC). This would require
   iterating `deferredProperties` inside `applyInlineConstraintsAt()` in addition to the
   PAC/NAC check.

2. **Single-node where clauses** could be treated like deferred property filters and inlined
   as soon as their single dependent instance is covered.

3. Both types could be added to `allConditions` (as a new `PendingCondition` subtype) so
   that the component-ordering tiebreaker (`minConditionCostUnlockedBy`) can factor them in.

None of these changes are required for the current algorithm to be correct — they are
pure performance improvements.
