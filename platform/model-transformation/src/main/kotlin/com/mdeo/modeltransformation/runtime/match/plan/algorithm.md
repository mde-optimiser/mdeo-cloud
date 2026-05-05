# Match-Plan Building Algorithm

## 1. Introduction and Context

A **match plan** is an imperative sequence of `BaseStep` instructions that, when compiled to
an Apache TinkerPop Gremlin graph traversal, finds all matches of a transformation rule's
left-hand side (LHS) pattern inside a property graph database.  Each match is a total,
injective mapping from the pattern's typed object instances (pattern nodes) and typed links
(pattern edges) to concrete vertices and edges in the graph.

The plan is built once per rule and reused across multiple match operations.  It is fully
imperative — no Gremlin `match()` step is ever emitted — for two reasons.  First, Gremlin's
`match()` step operates declaratively and relies on an internal join planner whose ordering
decisions are opaque and generally sub-optimal for the structured patterns that arise in model
transformations (which carry rich class-hierarchy and multiplicity metadata).  Second, the
imperative approach allows the builder to interleave vertex scans, edge walks, and all
constraint types (property filters, application conditions, injective guards, where clauses)
in a single linear order that is optimised by the builder itself, placing every constraint at
the earliest point where it can prune the traverser set.

The central data structures are:

- **`PatternCategories`** (input) — the categorised elements of the pattern split into:
  matchable instances, matchable links, delete/create instances and links,
  forbid/require instances and links, pattern variable bindings, and where-clause filters.
- **`MatchPlan`** (output) — a wrapper around a single flat list `baseSteps: List<BaseStep>`.
- **`BaseStep`** variants — the atomic instructions that make up the plan (described in
  Section 2 below).

---

## 2. Input / Output Specification

### Input: `PatternCategories`

| Field | Description |
|---|---|
| `matchableInstances` | Object instances that must be matched (LHS nodes to be found in the graph). May include instances also present in the RHS delete set. |
| `matchableLinks` | Typed links between matchable instances. |
| `deleteInstances` / `deleteLinks` | LHS elements that are deleted by the rule (merged with matchable set during plan construction). |
| `createInstances` / `createLinks` | RHS-only elements (not matched; ignored by the plan builder). |
| `forbidInstances` / `forbidLinks` | Negative application condition (NAC) elements. |
| `requireInstances` / `requireLinks` | Positive application condition (PAC) elements. |
| `variables` | Pattern variable bindings (`let x = expr` declarations). |
| `whereClauses` | Predicate filters over matched values (`where expr`). |

Before plan construction, matchable instances are **merged by name**: when the same name
appears multiple times (e.g., once with a class constraint and once without for a property
re-reference), the first non-null class name is kept and properties from all occurrences are
combined.  The effective matchable set is
`matchableInstances ∪ deleteInstances` (after merging).

### Output: `MatchPlan`

```
MatchPlan:
    baseSteps : List<BaseStep>
```

The list is ordered and executed left-to-right by the traversal compiler.

### `BaseStep` Variants

| Variant | Brief description |
|---|---|
| `VertexScan(instanceName, className?, vertexId?)` | Scan the graph for all vertices of class `className`, or jump directly to a pre-bound vertex identified by `vertexId`. Starts a new connected component. |
| `EdgeWalk(link, isReversed, fromInstanceName, toInstanceName, toClassName?, toVertexId?, needsSelect)` | Navigate an edge from the already-bound vertex `fromInstanceName` to reach `toInstanceName`. When `isReversed`, the edge is traversed in the backward (in-edge) direction. When `needsSelect`, the traverser must first re-select `fromInstanceName` from the step-label scope. |
| `InlinePropertyConstraint(instanceName, className?, property, isConstant)` | Filter the current vertex by a property constraint. When `isConstant`, translates to a `.has(key, value)` predicate; otherwise to a `.filter(equalityExpr.is(true))` sub-traversal. |
| `EqualityFilter(instanceName)` | Assert that the current traverser vertex equals the already-bound vertex labelled `instanceName`. Used inside `ApplicationCondition.innerSteps` when a condition edge leads back to an outer pattern node. |
| `ApplicationCondition(isNegative, anchorName?, needsSelect, innerSteps, injectiveConstraints)` | A positive (PAC) or negative (NAC) application condition. The `innerSteps` list encodes the condition subgraph pattern. `anchorName` is the main-pattern vertex at which the sub-traversal starts; `null` when the condition is disconnected from the main pattern. `injectiveConstraints` maps each island-node step label to the list of step labels it must differ from. |
| `VariableBinding(variable, variableLabel)` | Evaluate a pattern variable expression and bind the result to `variableLabel`. |
| `DeferredPropertyConstraint(instanceName, className?, property)` | A property constraint that could not be inlined (because it references pattern variables or nodes not yet covered). Applied via a `select(instanceLabel).where(...)` sub-traversal. |
| `WhereFilter(whereClause)` | Emit a where-clause predicate filter as soon as all referenced nodes are bound. |
| `InjectiveConstraint(instanceNameA, instanceNameB)` | Assert that two matched vertices are distinct: `where(A, P.neq(B))`. Appended at the end of the plan for all same-class matchable instance pairs. |

---

## 3. Phase 0 — Class Priority Computation

Before any traversal ordering is performed, the builder computes an integer **priority score**
for every *instance* in the current pattern.  A higher score means "match this instance first".
This priority is used in Phase 1 to break ties between competing scan and walk candidates.

Priority computation is split into two sub-steps:

1. **Build a pseudo-composition DAG** from the metamodel (`computePseudoCompositionDag`).
2. **Derive per-instance scores** from the pattern graph using that DAG.

### Sub-step A — Pseudo-Composition DAG

A **pseudo-composition DAG** is a cycle-free directed graph whose edges `A → B` mean
"instances of `B` are structurally contained by (or pseudo-composited on) instances of `A`".
It is built once per metamodel and reused across all rules.

```
function computePseudoCompositionDag(metamodel):

    allClasses = { C.name | C ∈ metamodel.classes }

    // Classes that are real composition targets cannot be pseudo-composition children.
    realCompositionTargets = { assoc.target.className
                               | assoc ∈ metamodel.associations, assoc.operator == "<>->",
                                 assoc.target.className ∈ allClasses }

    // ── Step A1: seed edges from real compositions and inheritance ──────────
    seedEdges : Set<(ClassName, ClassName)> = {}

    for each association assoc where assoc.operator == "<>->":
        src = assoc.source.className;  tgt = assoc.target.className
        if src ∈ allClasses and tgt ∈ allClasses and src ≠ tgt:
            seedEdges.add((src, tgt))       // composition: src is parent

    for each class C in metamodel.classes:
        for each parent P in C.extends:
            if P ∈ allClasses and P ≠ C.name:
                seedEdges.add((P, C.name))  // inheritance: parent is "above" child

    // ── Step A2: make cycle-free (back-edges removed, cross-edges kept) ─────
    // DFS over the seed-edge adjacency list.
    //   - tree edges    (to UNVISITED nodes)  → kept
    //   - cross/forward (to DONE nodes)       → kept  (unlike the spanning-tree approach,
    //                                            a node may have multiple parents here)
    //   - back edges    (to IN_STACK nodes)   → discarded (would create a cycle)
    dagEdges = makeCycleFree(seedEdges, allClasses)

    // ── Step A3: extend with pseudo-composition edges ───────────────────────
    for each association assoc where assoc.operator ≠ "<>->"
            (sorted by source.className, target.className for determinism):
        src = assoc.source.className;  tgt = assoc.target.className
        if src ∉ allClasses or tgt ∉ allClasses or src == tgt: continue

        srcUpper = assoc.source.multiplicity.upper   // -1 = unbounded (*)
        tgtUpper = assoc.target.multiplicity.upper

        // An end qualifies as a pseudo-composition *child* iff:
        //   (a) its upper multiplicity is finite (≠ -1),
        //   (b) its upper multiplicity is strictly less than the other end's upper
        //       (or the other end is unbounded), and
        //   (c) its class is NOT already a real composition target.
        tgtIsChild = (tgtUpper ≠ -1) and (srcUpper == -1 or tgtUpper < srcUpper)
                     and (tgt ∉ realCompositionTargets)
        srcIsChild = (srcUpper ≠ -1) and (tgtUpper == -1 or srcUpper < tgtUpper)
                     and (src ∉ realCompositionTargets)

        if tgtIsChild and not srcIsChild:
            // src is pseudo-parent of tgt
            if not wouldCreateCycle(dagEdges, src, tgt):
                dagEdges.add((src, tgt))
        else if srcIsChild and not tgtIsChild:
            // tgt is pseudo-parent of src
            if not wouldCreateCycle(dagEdges, tgt, src):
                dagEdges.add((tgt, src))
        // if both or neither qualify, skip

    return dagEdges   // Set<(parentClass, childClass)>


function makeCycleFree(edges, allClasses):
    adj = adjacency list built from edges
    result = {}
    state = all UNVISITED

    function dfs(node):
        state[node] = IN_STACK
        for each child in adj[node]:
            if   state[child] == UNVISITED: result.add((node,child)); dfs(child)
            elif state[child] == DONE:      result.add((node,child))  // cross-edge: safe
            // IN_STACK → back-edge: discard
        state[node] = DONE

    for each C in allClasses (sorted alphabetically):
        if state[C] == UNVISITED: dfs(C)
    return result


function wouldCreateCycle(dag, from, to):
    // True iff there is already a directed path from `to` to `from` in dag.
    BFS from `to` over dag edges; return true if `from` is reached.
```

### Sub-step B — Per-Instance Priority

Given the pseudo-composition DAG, priorities are computed **per rule** from the actual
pattern graph.  Only **regular** (no-modifier matchable) and **PAC (require)** instances and
links participate; NAC (forbid) and create/delete elements are ignored.

```
function computeInstancePriorities(elements, dagEdges):

    priorityInstances = { inst.name | inst ∈ matchableInstances ∪ requireInstances }

    classOf : Map<InstanceName, ClassName> = {}
    for each inst ∈ matchableInstances ∪ requireInstances:
        if inst.className ≠ null: classOf[inst.name] = inst.className

    // Build a directed priority graph over pattern instances.
    // An edge A → B means "B is pseudo-composited on A in *this pattern*."
    priorityChildren : Map<InstanceName, Set<InstanceName>> = {} for each name in priorityInstances

    for each link ∈ matchableLinks ∪ requireLinks:
        srcName = link.source.objectName;  tgtName = link.target.objectName
        if srcName ∉ priorityInstances or tgtName ∉ priorityInstances: continue
        srcClass = classOf[srcName];  tgtClass = classOf[tgtName]
        if srcClass or tgtClass is unknown: continue

        if (srcClass, tgtClass) ∈ dagEdges:
            priorityChildren[srcName].add(tgtName)   // srcName is pseudo-parent
        else if (tgtClass, srcClass) ∈ dagEdges:
            priorityChildren[tgtName].add(srcName)   // tgtName is pseudo-parent

    // Priority = count of instances transitively pseudo-composited on this one.
    return { name → |reachable(name, priorityChildren)| - 1
             for each name in priorityInstances }
    // (subtract 1 to exclude the start node itself)
```

**Design rationale.**  A composition root (e.g. a `Plan`) is typically far less numerous than
its contained children.  Scanning for `Plan` first produces a smaller initial traverser set and
reduces fan-out.  The new instance-based scoring preserves this benefit when a composition link
is actually *present* in the matchable/PAC part of the pattern (the parent instance gets a
higher score), while assigning **equal priority** to both nodes when the link is absent (e.g.
it is a create-only link or not part of the pattern at all).  In the equal-priority case the
NAC-unlock-cost tiebreaker (Phase 1, key 2) then takes over, allowing a node that directly
enables a cheap NAC to be chosen first regardless of its class's position in the metamodel
hierarchy.

---

## 4. Phase 1 — Structural Order Construction (Greedy)

Phase 1 produces a sequence of intermediate **structural steps** — either `CoverByVertex` (a
free scan) or `CoverByWalk` (an edge walk from an already-covered node) — that collectively
cover every matchable instance exactly once.  The structural steps do not yet emit `BaseStep`
objects; that is done in Phase 3.  A separate set of local bookkeeping variables (`covered`,
`walkedLinks`) is used so that the shared `coveredInstances` / `coveredLinks` sets remain
clean for Phase 3 to repopulate.

```
function buildStructuralOrder(allMatchable, allMatchableLinks, allConditions, instancePriorities):

    uncovered    = copy of allMatchable as mutable list
    covered      = {}                 // names of instances placed so far
    walkedLinks  = {}                 // links that have already been assigned a walk step
    availableWalks = []               // WalkOption objects ready to be chosen
    result       = []                 // ordered list of StructuralStep

    while uncovered is not empty or availableWalks is not empty:

        // ── Absolute priority: pre-bound instances first ──────────────────────────
        preBound = first instance in uncovered where getVertexId(instance.name) ≠ null
        if preBound ≠ null:
            result.add(CoverByVertex(preBound.name, preBound, vertexId = getVertexId(preBound.name)))
            remove preBound from uncovered
            covered.add(preBound.name)
            addWalkOptions(preBound.name, availableWalks, covered, walkedLinks)
            continue

        // ── Build candidate list ──────────────────────────────────────────────────
        candidates = []

        // Scan candidates: each uncovered instance with a known class name.
        for each inst in uncovered where inst.className ≠ null:
            prio    = instancePriorities[inst.name]  (default 0 if absent)
            nacCost = minConditionCostUnlockedBy(covered ∪ {inst.name}, covered)
            candidates.add(ScanCandidate(inst, prio, nacCost))

        // Walk candidates: each available walk whose destination is not yet covered.
        for each walk in availableWalks where walk.toInstanceName ∉ covered:
            prio    = instancePriorities[walk.toInstanceName]  (default 0 if absent)
            nacCost = minConditionCostUnlockedBy(covered ∪ {walk.toInstanceName}, covered)
            candidates.add(WalkCandidate(walk, prio, nacCost))

        if candidates is empty: break

        // ── Selection criterion (lexicographic, lower = better) ───────────────────
        //   Key 1: -instancePriority       (higher instance priority = prefer)
        //   Key 2: nacUnlockCost           (lower NAC/PAC unlock cost = prefer)
        //   Key 3: isScan ? 1 : 0          (prefer constrained walk over free scan)
        best = argmin(candidates, key = (-instancePriority, nacUnlockCost, isScan ? 1 : 0))

        // ── Commit the chosen candidate ───────────────────────────────────────────
        if best is ScanCandidate(inst):
            result.add(CoverByVertex(inst.name, inst, getVertexId(inst.name)))
            remove inst from uncovered
            covered.add(inst.name)
            addWalkOptions(inst.name, availableWalks, covered, walkedLinks)

        else if best is WalkCandidate(walk):
            toInst = walk.toInstance
                     ?? first instance in uncovered where name == walk.toInstanceName
            result.add(CoverByWalk(
                link       = walk.link,
                isReversed = walk.isReversed,
                fromName   = walk.fromInstanceName,
                toName     = walk.toInstanceName,
                toInstance = toInst,
                toVertexId = getVertexId(walk.toInstanceName),
                needsSelect = false   // placeholder; recomputed at emit time in Phase 3
            ))
            walkedLinks.add(walk.link)
            remove all instances with name == walk.toInstanceName from uncovered
            covered.add(walk.toInstanceName)
            remove all walk options for walk.link from availableWalks
            addWalkOptions(walk.toInstanceName, availableWalks, covered, walkedLinks)

    return result


function addWalkOptions(newName, availableWalks, alreadyCovered, alreadyWalked):
    // Discover new edges that become walkable now that newName is covered.
    for each link in allMatchableLinks:
        if link in alreadyWalked: continue
        src = link.source.objectName;  tgt = link.target.objectName
        if src == newName and tgt ∉ alreadyCovered and tgt ∈ matchableNames:
            availableWalks.add(WalkOption(link, isReversed=false, from=src, to=tgt,
                                          toInstance=instanceMap[tgt]))
        if tgt == newName and src ∉ alreadyCovered and src ∈ matchableNames:
            availableWalks.add(WalkOption(link, isReversed=true, from=tgt, to=src,
                                          toInstance=instanceMap[src]))
```

### NAC/PAC Unlock Cost

The tiebreaker `nacUnlockCost` measures how cheaply a pending application condition becomes
evaluable if the given candidate is chosen next.  Choosing the candidate that unlocks the
cheapest condition first allows expensive NACs to prune the traversal at the earliest possible
moment.

```
function minConditionCostUnlockedBy(after, before):
    // Returns the minimum estimated cost over all conditions that are not yet
    // ready with `before` but become ready with `after`.
    min = +∞
    for each pending in allConditions:
        required = pendingConditionRequiredNodes(pending)
        if required ⊆ before: continue      // already was ready; not newly unlocked
        if required ⊄ after:  continue      // still not ready
        cost = estimatePendingConditionCost(pending)
        min  = min(min, cost)
    return min   // returns +∞ when no condition is newly unlocked

function pendingConditionRequiredNodes(pending):
    // Returns the set of main-pattern node names that must be covered for `pending`
    // to be evaluable (anchors + injective-required nodes, minus omittable injective guards).
    island     = pending.island
    islandNames = { inst.name | inst ∈ island.instances }
    anchors    = findAnchorNames(island.links, islandNames, matchableNames)

    isSingleNodeNac = |island.instances| == 1
    injectiveRequired = computeInjectiveRequiredNodes(island)

    if isSingleNodeNac:
        // For single-node conditions, remove injective requirements covered by
        // a cheaper forbid orphan link (see Section 7 for canOmitInjective).
        islandNode = island.instances[0].name
        injectiveRequired = { Z ∈ injectiveRequired |
                               not canOmitNacInjectiveConstraint(islandNode, Z, island.links) }

    return anchors ∪ injectiveRequired

function computeInjectiveRequiredNodes(island):
    // Every main-pattern node whose class matches any island instance class must be
    // covered before the condition is emitted (to resolve injective constraints).
    result = {}
    for each islandInst in island.instances:
        cls = islandInst.className; if cls is null: continue
        for each mainInst in allMatchable:
            if mainInst.className == cls:
                result.add(mainInst.name)
    return result

function estimatePendingConditionCost(pending):
    island    = pending.island
    if island.instances is empty: return 1   // orphan-link island: single edge check
    islandNames = { inst.name | inst ∈ island.instances }
    anchors   = findAnchorNames(island.links, islandNames, matchableNames)
    edgeCount = |island.links|
    if anchors is empty:
        return 1000 + edgeCount × 10         // disconnected island: very expensive
    return edgeCount × 10                     // anchored island: scales with size
```

**Design rationale.**  The greedy criterion orders candidates first by instance priority
(pseudo-composition depth in the actual pattern graph, see Phase 0), then by the cost of the
condition that would be newly unlocked (opportunistic pruning), and finally by step type
(an edge walk is preferred over a free scan because it is already structurally constrained).
The three-key lexicographic ordering ensures a deterministic, repeatable plan.

---

## 5. Phase 2 — Post-Reordering ("1-Side Demotion")

After Phase 1 produces the structural step list, a single linear pass attempts to improve the
plan by **swapping** certain `CoverByVertex(A) + CoverByWalk(A→B)` pairs into
`CoverByVertex(B) + CoverByWalk(B→A)`, provided that the traversed association has
upper-bound multiplicity 1 on B's side (every B has exactly one A).

```
function applyPostReordering(structuralSteps):
    // Index associations by (sourcePropName, targetPropName) for O(1) lookup.
    assocByProps = metamodel.associations indexed by (source.name → target.name) pair

    for k = 0 to |structuralSteps| - 1:
        step = structuralSteps[k]
        if step is not CoverByWalk: continue

        fromName = step.fromName;  toName = step.toName
        link     = step.link

        // Determine if toName is at the "1-side" of the traversed association.
        assoc = assocByProps[link.source.propertyName → link.target.propertyName]
        if assoc is null: continue

        toUpperBound = if step.isReversed then assoc.source.multiplicity.upper
                       else               assoc.target.multiplicity.upper
        if toUpperBound ≠ 1: continue   // not a 1-side; demotion not beneficial

        // Locate the unconditional free scan for fromName strictly before position k.
        scanIdx = index of first structuralSteps[i] (0 ≤ i < k) such that
                  structuralSteps[i] is CoverByVertex
                  and structuralSteps[i].name == fromName
                  and structuralSteps[i].vertexId == null
        if scanIdx not found: continue   // fromName was reached via a walk or pre-bound; skip

        // Blocking check: if another walk uses fromName as its source between the scan and
        // this walk, then deferring the scan of fromName would invalidate that intermediate walk.
        if any structuralSteps[j] (scanIdx < j < k) is CoverByWalk with fromName == fromName:
            continue

        // Injective safety check: no pending condition may require fromName but not toName.
        // Such a condition was unlocked solely by the scan of fromName; deferring fromName
        // would delay it unnecessarily and could change which conditions are inlined where.
        safetyBlocked = exists pending in allConditions such that
                            fromName ∈ pendingConditionRequiredNodes(pending)
                            and toName ∉ pendingConditionRequiredNodes(pending)
        if safetyBlocked: continue

        // Perform the swap.
        structuralSteps[scanIdx] = CoverByVertex(
            name     = toName,
            instance = instanceMap[toName],
            vertexId = getVertexId(toName)     // null if not pre-bound
        )
        structuralSteps[k] = CoverByWalk(
            link        = step.link,
            isReversed  = NOT step.isReversed,
            fromName    = toName,
            toName      = fromName,
            toInstance  = instanceMap[fromName],
            toVertexId  = getVertexId(fromName),
            needsSelect = false   // recomputed at emit time in Phase 3
        )
```

**Design rationale.**  When `B.a[1]` (B has a single-valued reference to A), every vertex B
in the graph is guaranteed to have exactly one A reachable via the reverse walk.  If we scan
for B first, property constraints on B and NACs anchored at B can immediately prune traversers
*before* the walk to A is performed.  This is especially beneficial when B is a composition
child (A is the root); there can be many A's but each B points back to exactly one A, so no
fan-out occurs on the reverse walk.  The three guard conditions ensure correctness: (1) the
scan must be a free (unconditional) scan, not a pre-bound jump; (2) no intermediate walk
depends on fromName being the "current node"; (3) no condition becomes harder to schedule
as a side-effect of the swap.

---

## 6. Phase 3 — Plan Emission

Phase 3 iterates the (possibly reordered) structural step list in order and converts each
structural step into one or more `BaseStep` objects appended to `baseSteps`.  After each node
is covered, all constraints that have become satisfiable are immediately inlined.

```
function emitPlanFromStructuralOrder(structuralSteps):
    // Reset shared mutable state so this phase starts with a clean slate.
    coveredInstances = {}
    coveredLinks     = {}
    currentNode      = null    // the most recently covered node

    for each step in structuralSteps:

        if step is CoverByVertex(name, instance, vertexId):
            emit VertexScan(name, instance?.className, vertexId)
            coveredInstances.add(name)
            currentNode = name
            applyInlineConstraintsAt(name, instance)

        else if step is CoverByWalk(link, isReversed, fromName, toName, toInstance, toVertexId):
            // Recompute needsSelect: true iff the traverser is not already at fromName.
            needsSelect = (fromName ≠ currentNode)
            emit EdgeWalk(link, isReversed, fromName, toName,
                          toInstance?.className, toVertexId, needsSelect)
            coveredLinks.add(link)
            coveredInstances.add(toName)
            currentNode = toName
            applyInlineConstraintsAt(toName, toInstance)


function applyInlineConstraintsAt(instanceName, instance):

    // ── 1. Property constraints for the newly covered instance ───────────────────
    if instance ≠ null:
        for each property in instance.properties where property.operator == "==":
            referencedNodes = nodeAnalyzer.findReferencedNodes(property.value)
            referencedVars  = referencedNodes ∩ variableNames
            isConstant      = (referencedNodes is empty)
                              and (not isCollectionExpression(property.value))
            canInline       = (referencedVars is empty)
                              and (isConstant
                                   or (referencedNodes ⊆ coveredInstances
                                       and not isCollectionExpression(property.value)))
            if canInline:
                emit InlinePropertyConstraint(instanceName, instance.className, property, isConstant)
            else:
                deferredProperties.add(DeferredPropertyInfo(instanceName, instance.className, property))

    // ── 2. Previously-deferred property constraints now satisfiable ──────────────
    for each info in deferredProperties (iterate with removal):
        referencedNodes = nodeAnalyzer.findReferencedNodes(info.property.value)
        referencedVars  = referencedNodes ∩ variableNames
        if referencedVars is not empty: continue   // still needs variable binding; skip
        if referencedNodes ⊄ coveredInstances: continue
        if isCollectionExpression(info.property.value): continue  // keep for deferred phase
        emit InlinePropertyConstraint(info.instanceName, info.className, info.property, isConstant=false)
        remove info from deferredProperties

    // ── 3. Where-clause filters now satisfiable ──────────────────────────────────
    for each clause in pendingWhereClauses (iterate with removal):
        referenced = nodeAnalyzer.findReferencedNodes(clause.expression) ∩ matchableNames
        if referenced ⊄ coveredInstances: continue
        emit WhereFilter(clause)
        remove clause from pendingWhereClauses

    // ── 4. Application conditions (NAC/PAC) now ready ────────────────────────────
    readyConditions = []
    for each (index, pending) in allConditions (with index), index ∉ emittedConditionIndices:
        ac = tryBuildIslandCondition(pending.island, pending.isNegative, currentNode)
        if ac ≠ null:
            readyConditions.add((index, ac))
    sort readyConditions ascending by computeConditionCost(ac)
    for each (index, ac) in readyConditions:
        emit ac
        emittedConditionIndices.add(index)
```

**Design rationale.**  Inlining constraints as early as possible is the core performance
principle of the plan builder.  Every `InlinePropertyConstraint` emitted right after a
`VertexScan` acts as a filter before any further edge walks are performed, pruning the
traverser multiset at the smallest possible cardinality.  The same applies to application
conditions: a NAC that fires immediately after the second of its two required nodes is
covered eliminates entire match candidates before subsequent expensive walks.  Where-clauses
and deferred property constraints are promoted to inline status the moment their dependencies
are covered, avoiding deferral to the tail sweep.

---

## 7. Condition Building

### 7.1 `tryBuildIslandCondition`

```
function tryBuildIslandCondition(island, isNegative, currentNode):
    // Returns an ApplicationCondition if all required nodes are already covered,
    // or null if the condition is not yet ready.

    // Case A: Island with no instances (orphan-link island).
    // Both endpoints of the single link are main-pattern anchors.
    if island.instances is empty:
        // The link has no condition-exclusive nodes; just anchors.
        islandNames = {}
        anchors = findAnchorNames(island.links, islandNames, matchableNames)
        // (For an orphan-link island, anchors == both endpoints of the link.)
        injectiveRequired = computeInjectiveRequiredNodes(island)  // empty (no island instances)
        if not (anchors ∪ injectiveRequired) ⊆ coveredInstances: return null
        bestAnchor = selectBestAnchor(anchors, island.links, metamodel)
        if bestAnchor is null: return null
        needsSelect = (bestAnchor ≠ currentNode)
        return buildApplicationCondition(island, isNegative, bestAnchor, needsSelect)

    islandNames = { inst.name | inst ∈ island.instances }
    anchors     = findAnchorNames(island.links, islandNames, matchableNames)

    // Case B: Island with no links to the main pattern (fully disconnected).
    if anchors is empty:
        injectiveRequired = computeInjectiveRequiredNodes(island)
        if injectiveRequired ⊄ coveredInstances: return null
        return buildApplicationCondition(island, isNegative, anchorName=null, needsSelect=false)

    // Case C: Anchored island.
    isSingleNodeNac = |island.instances| == 1
    injectiveRequired = computeInjectiveRequiredNodes(island)
    if isSingleNodeNac:
        islandNode = island.instances[0].name
        injectiveRequired = { Z ∈ injectiveRequired |
                               not canOmitNacInjectiveConstraint(islandNode, Z, island.links) }
    required = anchors ∪ injectiveRequired
    if required ⊄ coveredInstances: return null

    bestAnchor  = selectBestAnchor(anchors, island.links, metamodel)
    if bestAnchor is null: return null
    needsSelect = (bestAnchor ≠ currentNode)
    return buildApplicationCondition(island, isNegative, bestAnchor, needsSelect)
```

**Anchor selection.**  `selectBestAnchor` picks the anchor whose first outgoing edge into the
island has the lowest upper-bound multiplicity.  Starting the sub-traversal from the anchor
with the most constrained first step minimises fan-out inside the condition chain.

**Cost function for ordering ready conditions.**

```
function computeConditionCost(ac):
    if ac.anchorName is null:
        // Disconnected condition: scan the entire graph for condition instances.
        return 1000 + |{ s ∈ ac.innerSteps | s is EdgeWalk }| × 10
    edgeCount    = |{ s ∈ ac.innerSteps | s is EdgeWalk }|
    selectPenalty = if ac.needsSelect then 1 else 0
    return edgeCount × 10 + selectPenalty
```

### 7.2 `buildApplicationCondition`

```
function buildApplicationCondition(island, isNegative, anchorName, needsSelect):
    islandNames      = { inst.name | inst ∈ island.instances }
    islandInstanceMap = island.instances indexed by name
    innerSteps       = []
    currentInner     = anchorName    // tracks traverser position within the condition chain

    // Disconnected island: start with a VertexScan for the first island instance.
    if anchorName is null:
        startInst  = island.instances[0]
        startNode  = startInst.name
        innerSteps.add(VertexScan(startNode, startInst.className, null))
        innerSteps.addAll(buildConditionPropertySteps(startInst))
        currentInner = startNode

    // BFS-order the island links from the start node (or anchor).
    // When metamodel data is available, links are sorted at each BFS level by the
    // outgoing multiplicity at the from-node end (ascending), minimising fan-out.
    orderedLinks = orderLinksByBFS(island.links, startNode = currentInner, metamodel)

    for each (link, isReversed) in orderedLinks:
        fromName   = if isReversed then link.target.objectName else link.source.objectName
        toName     = if isReversed then link.source.objectName else link.target.objectName
        toIsIsland = (toName ∈ islandNames)
        toInst     = if toIsIsland then islandInstanceMap[toName] else null

        needsSelectInner = (fromName ≠ currentInner)
        innerSteps.add(EdgeWalk(
            link            = link,
            isReversed      = isReversed,
            fromInstanceName = fromName,
            toInstanceName  = toName,
            toClassName     = toInst?.className,
            toVertexId      = null,
            needsSelect     = needsSelectInner
        ))

        // When the destination is an outer (main-pattern) node other than the primary anchor,
        // an EqualityFilter verifies we reached the exact already-matched vertex.
        if not toIsIsland and toName ≠ anchorName:
            innerSteps.add(EqualityFilter(toName))

        // Property constraints for island-internal destination nodes.
        if toIsIsland and toInst ≠ null:
            innerSteps.addAll(buildConditionPropertySteps(toInst))

        currentInner = toName

    injectiveConstraints = buildConditionInjectiveConstraints(island, orderedLinks, anchorName ?? island.instances[0].name)

    return ApplicationCondition(isNegative, anchorName, needsSelect, innerSteps, injectiveConstraints)


function buildConditionPropertySteps(instance):
    // Returns InlinePropertyConstraint steps for all == properties of `instance`.
    result = []
    for each property in instance.properties where property.operator == "==":
        referencedNodes = nodeAnalyzer.findReferencedNodes(property.value)
        isConstant = (referencedNodes is empty) and (not isCollectionExpression(property.value))
        result.add(InlinePropertyConstraint(instance.name, instance.className, property, isConstant))
    return result
```

### 7.3 Injective Constraint Map for Conditions

```
function buildConditionInjectiveConstraints(island, orderedLinks, startName):
    // Produces a map: islandNodeStepLabel → list of step labels it must differ from.
    islandInstanceMap = island.instances indexed by name
    constraints       = {}   // Map<String, List<String>>

    // Collect island-internal nodes in BFS order (excluding the start node).
    bfsOrder = []
    visited  = { startName }
    for each (link, isReversed) in orderedLinks:
        toNode = if isReversed then link.source.objectName else link.target.objectName
        if toNode ∈ islandInstanceMap and toNode ∉ visited:
            bfsOrder.add(toNode)
            visited.add(toNode)

    isSingleNodeNac = |island.instances| == 1

    for each (i, islandNode) in enumerate(bfsOrder):
        islandClass = islandInstanceMap[islandNode]?.className; if null: continue
        nodeLabel   = stepLabel(islandNode)

        // Constraint against each main-pattern node of the same class.
        for each mainInst in allMatchable:
            if mainInst.className ≠ islandClass: continue
            // Optimisation: omit X != Z when a forbid orphan link makes it redundant.
            if isSingleNodeNac and canOmitNacInjectiveConstraint(islandNode, mainInst.name, island.links):
                continue
            constraints[nodeLabel].add(stepLabel(mainInst.name))

        // Constraint against earlier island nodes in BFS order of the same class.
        for j = 0 to i - 1:
            prevNode  = bfsOrder[j]
            prevClass = islandInstanceMap[prevNode]?.className; if null: continue
            if prevClass == islandClass:
                constraints[nodeLabel].add(stepLabel(prevNode))

    return constraints
```

### 7.4 Single-Node NAC Injective Omission

```
function canOmitNacInjectiveConstraint(xName, zName, nacIslandLinks):
    // Returns true when "xName ≠ zName" is redundant because a forbid orphan link
    // (zName -- Yi) with the same edge label and direction already rejects any match
    // where xName = zName (as both would connect to Yi via the matching edge).
    for each nacLink in nacIslandLinks:
        xIsSrc  = (nacLink.source.objectName == xName)
        yiName  = if xIsSrc then nacLink.target.objectName else nacLink.source.objectName
        srcProp = nacLink.source.propertyName
        tgtProp = nacLink.target.propertyName

        for each orphanIsland in forbidIslands where orphanIsland.instances is empty:
            orphanLink = orphanIsland.links[0]   // orphan islands have exactly one link
            if orphanLink.source.propertyName ≠ srcProp: continue
            if orphanLink.target.propertyName ≠ tgtProp: continue
            // Verify Z occupies the same role (source or target) as X in the NAC link.
            zOnSameSide  = if xIsSrc then (orphanLink.source.objectName == zName)
                                     else (orphanLink.target.objectName == zName)
            yiOnOtherSide = if xIsSrc then (orphanLink.target.objectName == yiName)
                                      else (orphanLink.source.objectName == yiName)
            if zOnSameSide and yiOnOtherSide: return true
    return false
```

**Design rationale.**  If `X = Z` (the NAC node and the main-pattern node collapse to the
same vertex V), the NAC fires precisely when there exists a vertex Yi such that V–Yi satisfies
the NAC edge.  But if such a V–Yi edge exists, the simpler orphan-link forbid condition
`forbid Z–Yi` (which uses the identical edge label) already rejects the entire match.
Therefore the explicit guard `X ≠ Z` is redundant — omitting it reduces the size and
evaluation cost of the injective constraint map without sacrificing correctness.

---

## 8. Phase 4 — Tail Sweep

After the structural order has been emitted, Phase 4 handles any remaining elements that were
not covered during the greedy traversal.  The phases are executed in a fixed sequence:

```
function runTailSweep():

    // ── 8a. Matchable instances not yet covered ───────────────────────────────────
    // Possible when an instance has no class name and no pre-bound ID (would have
    // thrown during buildStructuralOrder) or was simply left uncovered by the loop.
    for each instance in allMatchable where instance.name ∉ coveredInstances:
        name    = instance.name
        cls     = instance.className
        vertexId = getVertexId(name)
        if vertexId ≠ null:  emit VertexScan(name, cls, vertexId)
        else if cls ≠ null:  emit VertexScan(name, cls, null)
        else: raise error("Instance has no class constraint and no pre-bound vertex ID")
        coveredInstances.add(name)
        applyInlineConstraintsAt(name, instance)

    // ── 8b. Externally referenced instances (pre-bound IDs, not in matchable set) ─
    for each refName in referencedInstances:
        if refName ∈ coveredInstances or refName ∈ instanceMap: continue
        vertexId = getVertexId(refName); if null: skip
        emit VertexScan(refName, null, vertexId)
        coveredInstances.add(refName)

    // ── 8c. Matchable links not yet walked ────────────────────────────────────────
    for each link in allMatchableLinks where link ∉ coveredLinks:
        srcName = link.source.objectName
        tgtName = link.target.objectName
        if srcName ∈ coveredInstances and tgtName ∈ coveredInstances:
            // Both endpoints already matched; emit a PAC to verify the edge exists.
            emit ApplicationCondition(
                isNegative = false,
                anchorName = srcName,
                needsSelect = true,
                innerSteps = [
                    EdgeWalk(link, isReversed=false, srcName, tgtName, null, null, needsSelect=false),
                    EqualityFilter(tgtName)
                ]
            )
        else if srcName ∈ coveredInstances:
            toInst = instanceMap[tgtName]
            emit EdgeWalk(link, isReversed=false, srcName, tgtName,
                          toInst?.className, getVertexId(tgtName), needsSelect=true)
            coveredInstances.add(tgtName)
        else if tgtName ∈ coveredInstances:
            fromInst = instanceMap[srcName]
            emit EdgeWalk(link, isReversed=true, tgtName, srcName,
                          fromInst?.className, getVertexId(srcName), needsSelect=true)
            coveredInstances.add(srcName)
        else:
            toInst = instanceMap[tgtName]
            emit EdgeWalk(link, isReversed=false, srcName, tgtName,
                          toInst?.className, getVertexId(tgtName), needsSelect=true)
            // srcName is not covered here; the walk will fail at runtime if src is unbound.

    // ── 8d. Deferred conditions (not yet emitted during traversal) ────────────────
    deferredAcs = []
    for each (index, pending) in allConditions where index ∉ emittedConditionIndices:
        island = pending.island
        if island.links is empty or anchors(island) is empty:
            ac = buildApplicationCondition(island, pending.isNegative, null, false)
        else:
            anchor = selectBestAnchor(anchors(island), island.links, metamodel)
            if anchor is null:
                ac = buildApplicationCondition(island, pending.isNegative, null, false)
            else:
                ac = buildApplicationCondition(island, pending.isNegative, anchor, needsSelect=true)
        deferredAcs.add(ac)
    sort deferredAcs ascending by computeConditionCost(ac)
    for each ac in deferredAcs: emit ac

    // ── 8e. Variable bindings ─────────────────────────────────────────────────────
    for each varElement in elements.variables:
        emit VariableBinding(varElement, variableLabel(varElement.variable.name))

    // ── 8f. Remaining deferred property constraints ───────────────────────────────
    for each info in deferredProperties:
        emit DeferredPropertyConstraint(info.instanceName, info.className, info.property)

    // ── 8g. Remaining where-clause filters ───────────────────────────────────────
    for each clause in pendingWhereClauses:
        emit WhereFilter(clause)

    // ── 8h. Global injective constraints ─────────────────────────────────────────
    for each pair (A, B) of distinct instances in allMatchable
            where A.className == B.className and A.className ≠ null:
        emit InjectiveConstraint(A.name, B.name)
```

**Design rationale.**  The tail sweep is a correctness backstop: it guarantees that every
element of the pattern is represented in the plan even if it could not be reached during the
greedy traversal (e.g., an isolated node with no links, an externally referenced vertex, or a
condition whose anchor never became the current node).  Variable bindings are placed after all
structural steps because their expressions may reference any matched vertex.  Deferred property
constraints are placed after variable bindings because they may reference pattern variables.
Global injective constraints are placed absolutely last to avoid interfering with the label
scoping of `select()` / `where()` operations earlier in the traversal.

---

## 9. Gremlin Compilation

The `MatchTraversalBuilder` translates the abstract `MatchPlan` into a concrete TinkerPop
Gremlin traversal by applying each `BaseStep` in order to a mutable traversal object.

The traversal is initialised as:

```
inject(emptyMap).as("_")
```

The empty-map injection creates a single traverser that carries no vertex binding; the anchor
label `"_"` is used as the root reference inside sub-traversals.  Each subsequent step
extends or filters this traversal:

| `BaseStep` | Gremlin translation |
|---|---|
| `VertexScan(name, cls, id ≠ null)` | `.V(id).as(label(name))` |
| `VertexScan(name, cls, id = null)` | `.V().hasLabel(cls[, subtypes…]).as(label(name))` |
| `EdgeWalk(…, needsSelect=false, isReversed=false)` | `.out(edgeLabel)[.hasId(id) \| .hasLabel(cls)].as(label(to))` |
| `EdgeWalk(…, needsSelect=false, isReversed=true)` | `.in(edgeLabel)[.hasId(id) \| .hasLabel(cls)].as(label(to))` |
| `EdgeWalk(…, needsSelect=true)` | `.select(label(from)).out/in(edgeLabel)[…].as(label(to))` |
| `InlinePropertyConstraint(isConstant=true)` | `.has(graphKey, value)` |
| `InlinePropertyConstraint(isConstant=false)` | `.filter(equalityExpr.is(true))` |
| `EqualityFilter(name)` | `.where(P.eq(label(name)))` |
| `ApplicationCondition(negative=true, anchor=null, needsSelect=false)` | `.not(chain)` |
| `ApplicationCondition(negative=true, anchor=A, needsSelect=false)` | `.not(chain)` |
| `ApplicationCondition(negative=true, anchor=A, needsSelect=true)` | `.not(select(label(A)).where(chain))` |
| `ApplicationCondition(negative=false, anchor=null, needsSelect=false)` | `.where(chain)` |
| `ApplicationCondition(negative=false, anchor=A, needsSelect=false)` | `.where(chain)` |
| `ApplicationCondition(negative=false, anchor=A, needsSelect=true)` | `.where(select(label(A)).where(chain))` |
| `VariableBinding(var, lbl)` | `.map(compiledExpr).as(lbl)` |
| `DeferredPropertyConstraint(name, …, prop)` | `.where(select(label(name)).has(key, value))` (constant) or `.where(equalityExpr.is(true))` (expression) |
| `WhereFilter(clause)` | `.where(compiledExpr.is(true))` |
| `InjectiveConstraint(A, B)` | `.where(label(A), P.neq(label(B)))` |

The condition chain (the anonymous traversal built from `innerSteps`) is constructed using
`__.identity()` as a base when the traverser is already at the anchor, or `__.V(…)` when the
island is disconnected.  Node labels are only assigned inside a condition chain when they are
genuinely needed — either for backtracking (`needsSelect=true` on a later `EdgeWalk` inside
the chain) or for within-island injective constraints — because assigning unnecessary labels
inside a `where(traversal)` sub-traversal causes TinkerPop to throw a
`KeyNotFoundException` when the label is not present in the outer traversal scope.

Edge labels are derived from the association's source-property name and target-property name
using a deterministic concatenation (the concrete scheme is encapsulated in `EdgeLabelUtils`);
a reader of the plan does not need to know this encoding to understand the algorithm.

---

## 10. Correctness and Complexity

### Correctness

**Plan completeness.**  Every matchable instance appears in exactly one `CoverByVertex` or
`CoverByWalk` structural step; the tail sweep (Phase 4, step 8a) provides a fallback for any
instance not covered during the greedy phase.  Every matchable link is either assigned a
`CoverByWalk` step in Phase 1 or emitted in the tail sweep (Phase 4, step 8c).

**NAC/PAC semantic equivalence.**  Each application condition is emitted at the earliest point
where all its required nodes (anchors and injective siblings) are bound.  The semantics are
equivalent to the standard double-pushout (DPO) semantics because:

1. All anchor values are fixed at the time the condition fires.  The condition therefore
   evaluates the same predicate as if it were evaluated at the end of the traversal —
   only the timing (and hence the traverser cardinality) differs.
2. Firing a NAC earlier cannot produce false positives: if the NAC condition holds at an early
   checkpoint, it will still hold at the end (the anchor bindings do not change between the
   checkpoint and the end).
3. Firing a PAC earlier cannot produce false negatives: if the PAC condition holds at an early
   checkpoint, the partial match is accepted and continues correctly.

**Injective constraint omission (soundness).**  The omission of `X ≠ Z` when a forbid orphan
link `Z–Yi` covers the same case is sound by case analysis.  If `X = Z` maps both to the same
vertex V, the NAC fires only when some Yi vertex exists such that V–Yi satisfies the NAC edge
X–Yi.  But the identical V–Yi edge also triggers the forbid orphan link `Z–Yi`, which rejects
the match unconditionally.  Therefore the explicit injective guard `X ≠ Z` is never the sole
reason a match is rejected, and its omission leaves the set of accepted matches unchanged.

**Post-reordering correctness.**  Swapping `CoverByVertex(A) + CoverByWalk(A→B)` to
`CoverByVertex(B) + CoverByWalk(B→A)` is correct because:

1. The association has upper bound 1 on B's side, so the reverse walk `B→A` is total: every
   B vertex has exactly one A vertex.  No matches are lost by starting from B instead of A.
2. The set of (A, B) pairs produced by the two orderings is identical.
3. Conditions are re-evaluated using the same readiness criterion applied to the new order, so
   every condition that was emitted in the original order is also emitted in the swapped order
   (possibly at a different position, but always before the first step that requires the
   condition's anchor to have been passed).
4. The three guard conditions (free scan, no blocking walk, injective safety) together ensure
   that no other step's `needsSelect` flag or condition-unlock timing is adversely affected.

### Complexity

Let $M = |$matchable instances$|$, $L = |$matchable links$|$, and $C = |$allConditions$|$.

- **Phase 0** (class priority): the DFS spanning forest runs in $O(K + E)$ where $K$ is the
  number of metamodel classes and $E$ the number of composition/inheritance/association edges.
  The BFS depth assignment is also $O(K + E)$.  Total: $O(K + E)$.

- **Phase 1** (greedy structural order): the outer loop runs $M + L$ iterations in the worst
  case (one per instance or link).  At each iteration, building the candidate list is
  $O(M + L)$, and evaluating `minConditionCostUnlockedBy` for each candidate is
  $O(C \times \text{requiredNodes per condition})$.  Total:
  $O\!\left((M + L)^2 \times C\right)$.

- **Phase 2** (post-reordering): a single linear scan over the $M + L$ structural steps;
  for each `CoverByWalk` step a linear scan of earlier steps and an $O(C)$ safety check.
  Total: $O((M + L) \times C)$.

- **Phase 3** (emission): each instance is processed once; for each instance the deferred
  property list and pending where-clause list are scanned in $O(M \times P)$ (where $P$ is
  the number of properties per instance) and each condition is checked in $O(C)$.
  Total: $O(M \times (P + C))$.

- **Phase 4** (tail sweep): $O(M + L + C)$.

The dominant cost is Phase 1: $O\!\left((M + L)^2 \times C\right)$, which is acceptable
because $M$, $L$, and $C$ are all bounded by the size of the rule's LHS pattern and its
application conditions — quantities that are fixed at rule-compilation time and typically small
(single-digit to low tens for realistic transformation rules).
