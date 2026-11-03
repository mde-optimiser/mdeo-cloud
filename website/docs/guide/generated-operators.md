# Generated mutation operators

A search can only find models its mutation operators can reach, and every model an operator
produces costs an evaluation. Writing operators by hand that never produce an invalid model is
tedious and easy to get wrong. The `search` section can therefore
[generate them](/plugins/config-mdeo#the-search-section) from the metamodel.

This page explains what is generated, why each operator keeps the model consistent, and how the
generator differs from the approach it is based on.

## The guarantee

A generated operator never breaks a multiplicity. Applied to a model in which every object has an
allowed number of links through each association end, and is contained by at most one whole, it
produces a model in which this still holds.

The bounds that count are those of the metamodel with the goal section's
[refinements](/plugins/config-optimization#the-goal-section) applied. Other constraints — script
constraints of the goal section, attribute values, anything a metamodel cannot express — are not
part of the guarantee: the search evaluates them as usual.

Operators never change attribute values of existing objects; they create and delete objects and add,
remove and move links.

## Background

The generator follows the *atomic consistency-preserving search operators* of Burdusel et al.,
first implemented in MDEOptimiser:

- A. Burdusel, S. Zschaler, S. John: *Automatic Generation of Atomic Consistency Preserving Search
  Operators for Search-Based Model Engineering*, MODELS 2019,
  [doi:10.1109/MODELS.2019.00-10](https://doi.org/10.1109/MODELS.2019.00-10)
- A. Burdusel, S. Zschaler, S. John: *Automatic generation of atomic multiplicity-preserving search
  operators for search-based model engineering*, Software and Systems Modeling 20 (2021),
  [doi:10.1007/s10270-021-00914-w](https://doi.org/10.1007/s10270-021-00914-w)
- A. Burdusel: *Optimisation Methods for Model-Driven Engineering*, PhD thesis, King's College
  London, 2021 — chapter 5 and appendix C hold the generation algorithm and its case tables.

The idea: every edit (create an object, delete one, add a link, remove one) is paired with the
smallest *repair* that keeps both ends of the affected association within their bounds. Which repair
is needed depends only on the two ends' multiplicities, so a table of multiplicity patterns decides
which operators exist. [Differences](#differences-from-the-papers-and-mdeoptimiser) lists where this
implementation deviates from the papers and from MDEOptimiser, and why.

## Notation

Every operator is about one association end, written `A.e[n..m]`: each object of class `A` is linked
to between `n` and `m` objects of class `B`. Its opposite end says how many `A`s each `B` may have,
written `[k..l]`. `*` stands for an unbounded upper bound, and an end is **fixed** when its lower and
upper bound are equal.

```mm
// n..m = 1..*     k..l = 0..1
Sprint.committedItems[+] <--> WorkItem.isPlannedFor[?]
```

- An end without a property name cannot be navigated and the language gives it no multiplicity, so
  it counts as `[0..*]`: `AllocationContext.resourceContainer[?] --> ResourceContainer` lets any
  number of allocation contexts use one container.
- Ends are inherited: an end declared on a superclass applies to every subclass, on both sides.
- In a composition the whole's end holds the parts, and the part's end, which always has a name,
  points at the whole. An object is contained by at most one whole across all compositions.

The examples below use this metamodel:

```mm
class Plan { }
class Sprint { }
class WorkItem { }
class Team { }
class Person { }

Plan.sprints[*] *--> Sprint.plan
Plan.backlog[*] *--> WorkItem.plan
Sprint.committedItems[+] <--> WorkItem.isPlannedFor[?]
Team.members[*] <--> Person.teams[+]
```

## From config entries to operators

| Entry | Generates |
| --- | --- |
| `add Class.e` | The add family for the end `e` |
| `remove Class.e` | The remove family for the end `e` |
| `mutate Class.e` | Both families |
| `create Class` | Create operators for the class, or for each concrete subclass if it is abstract |
| `delete Class` | One delete operator, which also deletes objects of subclasses |
| `mutate Class` | `create`, `delete`, and both edge families for every end the class holds |

Selecting an end never creates or deletes objects. Operators asked for more than once are generated
once. Every operator is written to the results as `generated-mutations/<name>.mt_gen`, which is the
best way to see exactly what the search may do.

## Edge operators

The pattern of the two ends decides which operators an end gets. Fixed ends take precedence:

| Pattern | Add family | Remove family |
| --- | --- | --- |
| neither end fixed | `ADD_A_e` | `REMOVE_A_e` |
| `k = l`, `n < m` | `CHANGE_A_e` (move a `B` to another `A`) | same |
| `n = m`, `k < l` | `SWAP_A_e`, `CHANGE_A_e` (an `A` replaces one of its `B`s) | same |
| `n = m`, `k = l` | `SWAP_A_e` | same |

With a fixed end, adding or removing a single link would always break it, so both families consist of
operators that move links instead.

`mutate A.e` also gets `SWAP_A_e` when one end has an upper bound and the other a lower bound above
zero (`m` finite and `k > 0`, or `l` finite and `n > 0`). Such ends can be **tight**: with
`Sprint.committedItems[+] <--> WorkItem.isPlannedFor[?]`, two sprints and two work items, each
sprint must keep its one item and no item can take a second sprint, so no single link can be added
or removed, although the two sprints could exchange their items. A swap keeps every object's number
of links, so it is always possible, and it connects such states.

### Add and remove

`ADD_A_e` links an `A` and a `B` that are not linked yet, provided neither is full. `REMOVE_A_e`
unlinks them, provided both keep their lower bound:

```mt
// ADD_Sprint_committedItems
match {
    source: Sprint { }
    target: WorkItem { }
    forbid alreadyLinked {
        source.committedItems -- target.isPlannedFor
    }
    create source.committedItems -- target.isPlannedFor
    where target.isPlannedFor == null
}

// REMOVE_Sprint_committedItems
match {
    source: Sprint { }
    target: WorkItem { }
    delete source.committedItems -- target.isPlannedFor
    where source.committedItems.size() > 1
}
```

A guard appears only for a bound that can be violated: `ADD` checks `m` and `l` when they are finite,
`REMOVE` checks `n` and `k` when they are not zero. The language treats an end declared with an upper
bound of 1 as a single object rather than a list, so its bound is checked with `== null`. Adding a
link of a composition also requires that the part is not contained by another whole yet.

### Change

When the opposite end is fixed (`k = l`), every `B` must keep exactly `k` partners, so a `B` is moved
from one `A` to another. With `refine WorkItem.isPlannedFor[1]`:

```mt
// CHANGE_Sprint_committedItems
match {
    moved: WorkItem { }
    oldSource: Sprint { }
    newSource: Sprint { }
    delete oldSource.committedItems -- moved.isPlannedFor
    forbid alreadyLinked {
        newSource.committedItems -- moved.isPlannedFor
    }
    create newSource.committedItems -- moved.isPlannedFor
    where oldSource.committedItems.size() > 1
}
```

When the selected end itself is fixed (`n = m`) and the opposite is not, the `A` keeps its number of
links and replaces one partner by another. This is what moving a component to another server needs,
with `refine AllocationContext.resourceContainer[1]`:

```mt
// CHANGE_AllocationContext_resourceContainer
match {
    source: AllocationContext { }
    oldTarget: ResourceContainer { }
    newTarget: ResourceContainer { }
    delete source.resourceContainer -- oldTarget
    forbid alreadyLinked {
        source.resourceContainer -- newTarget
    }
    create source.resourceContainer -- newTarget
}
```

### Swap

For a fixed end, two `A`s exchange one partner each; every count stays the same:

```mt
// SWAP_AllocationContext_resourceContainer
match {
    source: AllocationContext { }
    otherSource: AllocationContext { }
    target: ResourceContainer { }
    otherTarget: ResourceContainer { }
    delete source.resourceContainer -- target
    delete otherSource.resourceContainer -- otherTarget
    forbid alreadyLinked {
        source.resourceContainer -- otherTarget
    }
    forbid otherAlreadyLinked {
        otherSource.resourceContainer -- target
    }
    create source.resourceContainer -- otherTarget
    create otherSource.resourceContainer -- target
}
```

## Create operators

A new object has to satisfy the lower bound `n` of every end it holds. For each such end the
generator picks one of three repairs:

| Repair | What it does | Offered when |
| --- | --- | --- |
| `CONNECT` | Link `n` existing `B`s that still have room for another `A` | `l = *`, or `k < l` |
| `LBREPAIR` | Take all `n` `B`s from one other `A`, which keeps at least `n` | `n < m`, `l` finite, and `m ≥ 2n` or `m = *` |
| `LBREPAIR_MULTI` | Take one `B` from each of `n` other `A`s, each keeping at least `n` | `1 < n < m`, `l` finite |

There is one create operator for every combination of the required ends' repairs (the papers'
*iterative repair*), and for each composition that can contain the new object. The name lists both:

```mt
// CREATE_Sprint_in_Plan_via_sprints_committedItems_LBREPAIR
match {
    create newNode: Sprint { }
    container: Plan { }
    create container.sprints -- newNode.plan
    committedItems_donor: Sprint { }
    where committedItems_donor.committedItems.size() > 1
    committedItems_1: WorkItem { }
    delete committedItems_donor.committedItems -- committedItems_1.isPlannedFor
    create newNode.committedItems -- committedItems_1.isPlannedFor
}
```

`CREATE_Sprint_in_Plan_via_sprints_committedItems_CONNECT` instead matches a work item with
`isPlannedFor == null` and links it. A container's own upper bound is checked as well. Required
single-valued attributes of the new object get a default: `""`, `0`, `false` or the enum's first
entry.

When an end of the class and its opposite are both fixed (`n = m` and `k = l`), no repair exists:
every candidate partner is already full, and making room would mean changing further objects in
turn. The class then gets no create operator, and a warning says so.

A whole whose end holds a fixed number of parts, such as `Plan.backlog[1]`, can never take another
one, so no object is created inside it. If the new object may stand alone, it is then created
without a container, like an object of a class no composition contains; if it must be contained and
no other whole can take it, the class gets no create operator. Both cases come with a warning.

## Delete operators

Deleting an object removes all of its links. For every end through which another object is linked to
it, that object loses one partner:

- If the other object's end has lower bound `k = 0`, nothing has to happen.
- If `0 < k < l` or `l = *`, the delete only happens when **every** linked object keeps at least `k`
  partners afterwards.
- If the end is fixed (`k = l`), every linked object is **moved** to another object of the deleted
  one's class that has room, so it keeps exactly `k`. If one of them cannot be placed, the operator
  does not apply and the model is left unchanged.

```mt
// DELETE_Team: every person must stay in at least one team
match {
    node: Team { }
    forbid teams_keepsLowerBound_0 {
        neighbour_0: Person { }
        node.members -- neighbour_0.teams
        where neighbour_0.teams.size() <= 1
    }
}
match {
    delete node
}
```

The object is matched first and deleted by a second statement, since a `forbid` block may not refer
to an object its own pattern deletes.

With `refine WorkItem.isPlannedFor[1]`, deleting a sprint moves each of its work items to another
sprint:

```mt
// DELETE_Sprint
match {
    node: Sprint { }
}
for match {
    node { }
    moved_0: WorkItem { }
    delete node.committedItems -- moved_0.isPlannedFor
} do {
    if match {
        moved_0 { }
        newPartner_0: Sprint { }
        where newPartner_0 != node
        forbid alreadyLinked_0 {
            newPartner_0.committedItems -- moved_0.isPlannedFor
        }
        create newPartner_0.committedItems -- moved_0.isPlannedFor
    } then {
    } else {
        kill
    }
}
match {
    delete node
}
```

Compositions follow the same rule through the part's end: deleting a whole whose parts must be
contained moves the parts to another whole, and deleting one whose parts may stand alone leaves them
uncontained. Nothing is deleted in cascade.

When every object of the class holds an end that is fixed together with its opposite, no object
could ever be deleted, and no delete operator is generated.

## Refinements

A refinement replaces the bounds of one association end for the search:

```mdeo-config
goal {
    refine WorkItem.isPlannedFor[1]
}
```

Operators are generated against the metamodel with every refinement applied, so they keep the
refined bounds as well. In the example, no operator can leave a work item outside a sprint: `ADD` and
`REMOVE` for `committedItems` turn into `CHANGE`, and deleting a sprint moves its work items.

- A refinement names the class that declares the end, and can only narrow its bounds.
- The initial model must already satisfy every refinement; otherwise the run fails before it starts
  and lists the violations.
- Hand-written `using` operators are not affected.

## Warnings and limitations

Generation fails with an error for an unknown class or end, an attribute named as an end, or an
invalid refinement. It produces warnings, collected in `generated-mutations/warnings.md` in the
results, when:

- a class gets no create or no delete operator because of ends fixed at both sides;
- a created object has a required multi-valued attribute, which is left empty;
- operators move objects within a composition whose parts may contain each other, such as
  `Node.children[*] *--> Node.parent[?]`. A pattern can only look a fixed number of levels up, so
  these operators do not prevent an object from ending up inside one of its own descendants.

Operators choose their match at random, like every operator of the search. An operator that is
applicable in principle may still fail on a given model, for example when a delete cannot place all
moved partners; the search then tries another operator.

Matching is injective: the objects of one operator are distinct. So an operator never links an
object to itself or removes such a link, and a create operator needs a different partner for each
required end and a different donor for each repair, even where the metamodel would allow one object
to play several of these roles. A model that has only one suitable object may therefore not offer a
create that a larger one would.

## Differences from the papers and MDEOptimiser

The papers describe the generation as prose, as case tables, and in the thesis also as pseudocode
(Algorithms 4 and 11–14). These do not always agree, and MDEOptimiser in turn deviates from both.
mdeo-cloud follows the case tables, takes over MDEOptimiser's corrections, and departs from both
where the platform allows something better.

### Reading the papers

| Point | Papers | mdeo-cloud |
| --- | --- | --- |
| Add/remove edge cases | The pseudocode's `k = l` and `n = m` branches can never be reached, so it would add and remove single links for fixed ends; the tables and prose give change and swap instead | Tables: fixed ends get change and swap |
| Remove edge, `n > 0` and `k > 0` | Pseudocode checks only `B`; table and figure check `A` and `B` | Both |
| Remove edge condition | Prose says "NAC", tables and figures use PACs | Lower bounds are required (PAC) |
| Change edge on a bounded `A` | Figure 5.11a omits the upper-bound check on the new `A` | Checked |
| Create, `k = l` and `n > 1` | Table lists "add `n` `B`" besides both repairs, which would overflow a full `B`; pseudocode lists the single repair only | Both repairs, as MDEOptimiser |
| Selecting only `add` or `remove` | Change and swap belong to both subsets (thesis §5.4.4) | Same |

### Changes to the approach

| Point | Papers | MDEOptimiser | mdeo-cloud |
| --- | --- | --- | --- |
| Refinements | Generate for the problem metamodel and the refined one, keep both rule sets | Same (`P_` and `S_` rules) | Refined metamodel only. Rules for the unrefined metamodel can break the refinements, so the search could leave the refined space it is meant to stay in. Instead the initial model must satisfy the refinements |
| Fixed end, open opposite | Swap only | Swap only | Swap and change. Swap only exchanges partners, so it can never use a `B` nobody uses yet, and the operators would not be complete in the sense the SoSyM paper claims |
| Ends that can be tight | Add and remove | Same | Add, remove and swap. Without the swap, a fixed set of objects can be stuck in one consistent linking while others exist; the papers rely on creating and deleting objects to get out, which a search that only selects an end cannot do |
| Single-donor create repair | For every `n ≥ 1`, `m > n` | Same | Only when the donor can keep `n` after giving `n` (`m ≥ 2n`); otherwise the operator could never apply |
| Delete | Removes `A` with the edges to exactly the `B`s in the rule; repairs assume exactly `n` attached `B`s | Henshin's dangling check: only objects with exactly the partners in the rule can be deleted | Guards cover every linked object, and every object needing exactly `k` partners is moved, so objects with any number of partners can be deleted. The papers' "all to one other `A`" and "each to a different `A`" repairs are both special cases |
| Several constrained ends | Combinations of repairs, plus single-end rules | Combinations only | Combinations only; a single-end rule would break the other ends |
| Ends without an opposite | Not covered | Treated as unconstrained, some cases crash | Unconstrained `[0..*]` |
| Containment | Created objects are placed in a container; its bounds are not checked | One identical rule copy per container for edge operators; root classes crash | One create operator per container that can take another part, container bounds checked, an object is never contained twice |
| Inheritance | Not covered | Declared ends only; abstract classes are instantiated | Inherited ends count; abstract classes are created as their concrete subclasses |
| Attributes of new objects | Shown as rule parameters in some figures | Bound to parameters that are never set | Defaults for required single-valued attributes |
| Impossible cases | "N/A" in the tables | Silently no rules | No operator, with a warning |
| Names | `createA_IN_Context`, `addToA_e_B`, … | `P_ALL_A_e_edge_ADD`, … | `CREATE_A_in_Context_via_as_…`, `ADD_A_e`, … — unique per operator |

Rules in this platform are model transformations rather than Henshin rules: application conditions
are `forbid` blocks, counting conditions are `where … .size()` clauses, and the delete operator's
moves use `for match` with `kill` for the case where a partner cannot be placed.

## Where it lives

The generator is in `platform/optimizer/src/main/kotlin/com/mdeo/optimizer/rulegen/`:

| File | Role |
| --- | --- |
| `MetamodelInfo.kt` | Classes, inheritance and association ends with refinements applied |
| `SpecsGenerator.kt` | Decides which operators an entry stands for (the tables above) |
| `OperatorSpec.kt` | What one operator does, and its name |
| `MutationAstBuilder.kt`, `RuleAstWriter.kt` | Turn an operator into a transformation |
| `MultiplicityChecker.kt` | Checks a model against the bounds; used for the initial model and in tests |
| `MutationRuleGenerator.kt` | Entry point used by the optimizer execution service |

The tests next to it, in `platform/optimizer/src/test/kotlin/com/mdeo/optimizer/rulegen/`, check the
operators from several sides. Most run for every pair of the multiplicities `*`, `?`, `+`, `1`, `2`,
`1..2`, `2..3` and `2..*`, and on both graph backends:

| Test | Checks |
| --- | --- |
| `RuleConsistencyTest`, `ExampleMetamodelRuleTest` | Applied to random consistent models, every operator keeps them consistent (bounds, one container, no duplicate link), changes them only as its kind may, and applies to some model. Covers compositions, inheritance, self-associations, refinements and the metamodels of MDEOptimiser's examples |
| `EdgeOperatorExactnessTest` | An edge operator produces exactly the models one consistent change of its kind leads to, found by brute force, and an end without add or remove admits none |
| `EdgeOperatorCompletenessTest` | For a few objects, the edge operators of an end lead from any consistent linking to every other one |
| `OperatorTableTest` | The operators for every pair of multiplicities match the reviewed table in `operator-table.txt` |
| `GeneratedOperatorSearchTest` | In the optimizer's own mutation loop, every solution stays consistent and parents stay unchanged |
| `GeneratedOperatorLanguageTest`, `GeneratedOperatorSerializationTest` | Operators are valid `.mt` transformations and reach the workers unchanged |
| `MutationRuleGeneratorTest`, `MetamodelInfoTest`, `MultiplicityCheckerTest`, `PreparationTest` | Single behaviours, refinement errors and the check of the initial model |
