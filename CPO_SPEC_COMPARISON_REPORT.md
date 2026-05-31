# aCPSO Spec Comparison Report

## Scope

This report compares three sources for atomic consistency-preserving search operator generation:

- formal operator tables in `/home/devbox/workspace/spec.md`
- the original MDEOptimiser reference implementation in `/home/devbox/workspace/copilot/mde_optimiser`
- the current Kotlin implementation in `/home/devbox/workspace/platform/optimizer`

The analysis in this pass focused on node and edge mutation rule generation only.

## Reference Map

- Kotlin entry points:
  - `platform/optimizer/src/main/kotlin/com/mdeo/optimizer/rulegen/MutationRuleGenerator.kt`
  - `platform/optimizer/src/main/kotlin/com/mdeo/optimizer/rulegen/SpecsGenerator.kt`
  - `platform/optimizer/src/main/kotlin/com/mdeo/optimizer/rulegen/MutationAstBuilder.kt`
- Kotlin tests:
  - `platform/optimizer/src/test/kotlin/com/mdeo/optimizer/rulegen/MutationRuleGeneratorTest.kt`
- Java reference entry points:
  - `copilot/mde_optimiser/libraries/rulegen/src/main/java/uk/ac/kcl/inf/mdeoptimiser/libraries/rulegen/RulesGenerator.java`
  - `copilot/mde_optimiser/libraries/rulegen/src/main/java/uk/ac/kcl/inf/mdeoptimiser/libraries/rulegen/generator/specs/SpecsGenerator.java`

## Verified Comparison Findings

### 1. Fixed-source delete repair variants were over-generated in Kotlin

The formal delete-node tables mark `n = m` as `N/A` for fixed-back-reference repair variants. The Java reference also suppresses those repair variants when the deleted side itself is fixed-cardinality.

Before this pass, Kotlin generated `DELETE_REPAIR_SINGLE` and `DELETE_REPAIR_MULTI` whenever the opposite end had `k = l > 0`, even when the source reference itself had `n = m`.

Implemented fix:

- `SpecsGenerator.generateDelete()` now emits delete-repair variants only when the source side is not fixed-cardinality.

Added regression coverage:

- fixed-source selection test
- fixed-source end-to-end rule-name test

### 2. Delete repair ASTs were dropping lower-bound preservation for non-repaired references

Plain `DELETE` rules already guarded every affected reference whose target still needed a positive lower bound. `DELETE_REPAIR_SINGLE` and `DELETE_REPAIR_MULTI` did not carry over those guards for other references on the same source node.

That meant a repair rule could correctly re-home one fixed-cardinality neighbour while silently ignoring another outgoing reference that still needed lower-bound preservation.

Implemented fix:

- extracted shared delete-guard generation into `MutationAstBuilder.appendDeleteGuards()`
- reused it from plain `DELETE`, `DELETE_REPAIR_SINGLE`, and `DELETE_REPAIR_MULTI`
- repair builders now skip only the reference they are actively repairing and still preserve guards for the rest

Added regression coverage:

- mixed delete-repair test where one ref is repaired and another ref contributes a lower-bound guard

## Remaining High-Priority Gaps

### 1. Edge CHANGE selection is still narrower than the formal spec and Java reference

Kotlin currently classifies CHANGE only when the opposite lower bound is exactly `1`. Both the formal tables and the Java reference treat fixed opposite multiplicity `k = l` as the CHANGE case when the source side is not itself fixed and therefore not forced into SWAP.

Status:

- not changed in this pass
- requires a careful design decision because the current CHANGE AST is a one-edge retarget and may be too weak to produce useful mutations for exact `2..2`, `3..3`, etc. in already-valid models

### 2. Kotlin still flattens node repair selection instead of composing combinations across references

The Java reference generates cartesian products of per-reference repair choices for CREATE and DELETE. Kotlin emits one `RepairSpec` at a time.

Practical consequence:

- CREATE partially compensates via mandatory-reference creation, but still cannot repair two distinct outgoing references via donor-based repair in one operator
- DELETE repair rules now preserve lower-bound guards for non-repaired refs, but Kotlin still cannot express multi-reference repair combinations in one deletion rule

Status:

- not changed in this pass
- likely requires a structural change to carry combinations of `RepairSpec` through `MutationRuleGenerator`

### 3. Some formal-table rows still disagree with the Java reference

The attached `spec.md` is stricter than the Java reference in several areas, especially around omitted create-node cases and some delete-node cases. Not every mismatch is safe to "fix" in Kotlin until the intended precedence and support matrix are clarified.

## Validation Performed

Focused tests added and validated:

- `DELETE on class with fixed source and fixed back-ref emits only plain DELETE`
- `generate DELETE for fixed-source class suppresses repair variants`
- `DELETE_REPAIR_SINGLE preserves lower-bound guards for non-repaired references`

Focused post-edit regression run also passed for adjacent delete-rule generation and delete-repair AST tests, excluding one known pre-existing unrelated failure in `DELETE_REPAIR_MULTI with k=2 produces 2 neighbours, 2 others, 2 forbid and 2 create links`.

## Recommendation

If work continues immediately, the next step should be to decide whether fixed-opposite edge cases such as `2..2` should genuinely map to CHANGE in Kotlin, or whether the current CHANGE AST needs to be generalized first. That is the highest-value remaining spec/reference mismatch in the rule-selection layer.