# Reading the results

An optimisation run does not produce *the* answer. Multi-objective search produces a **Pareto front**:
the set of solutions where you cannot improve one objective without making another worse. Choosing
among them is your job, not the algorithm's.

## The result tree

When a run finishes, the execution exposes a tree of files. For a run with a single batch and no
auto-generated mutations, that tree is flat:

```
summary.md
report.json
solution_0.m_gen
solution_1.m_gen
...
```

With several batches, or when the run generated mutation rules itself, results are grouped:

```
summary.md
batch_1/report.json
batch_1/solution_0.m_gen
batch_2/report.json
batch_2/solution_0.m_gen
generated-mutations/<rule>.mt_gen
generated-mutations/warnings.md
```

`generated-mutations/warnings.md` only appears when some requested operators could not be generated
or are limited; see [Generated mutation operators](/guide/generated-operators#warnings-and-limitations).

## `summary.md`

The human-readable report, rendered directly in the workbench. It contains:

- **Execution resources** — which graph backend was used and how many threads each node contributed.
- **Per batch**: the duration, the number of solutions found, and the **Pareto front** as a table of
  objective values and constraint values, one row per solution.
- **Solution models** — links to the generated models, which open in the model diagram editor.
- **Operators** — the mutation operators and, with `variation = genetic`, the crossover operators
  of the run, each with its [arguments](/plugins/config-mdeo#operator-arguments).
- **Metrics** — plots over the generations of the run: total models produced, transformations
  executed versus skipped, and the hypervolume of the front, which is the usual single number for
  "how good is this front". With crossover, also the children that crossed over versus those that
  fell back to a copy of their parent, and how often each crossover operator applied.

The metrics plots are the quickest way to tell whether a run was worth its time. A hypervolume curve
that flattens early means you could have stopped sooner; one still climbing at the end means the
termination criterion cut the search short.

## `report.json`

The same data in machine-readable form, for scripting comparisons across runs.

## `solution_*.m_gen`

One generated model per solution on the front, in the *generated model* language. These files have no
textual editor — they are machine output — but they open in the same diagram editor as hand-written
models, so you can inspect the assignment a solution proposes.

For the [task allocation walkthrough](/guide/walkthrough), each solution model shows a different
trade-off: one leaves nothing unassigned but overloads Bob, another spreads the work evenly but
leaves the migration task for later.

## Interpreting the front

A few rules of thumb:

- **Objectives are listed in the order they appear in the `goal` section.** The summary table does not
  repeat their names, so keep that order in mind when reading the columns.
- **Constraint columns should be zero.** A non-zero constraint value means the solution violates the
  constraint by that amount; such solutions are ranked behind all feasible ones but can still appear
  if the search never found a feasible solution.
- **A front with one entry** usually means the objectives are not actually in conflict, or that the
  search collapsed early — check the metrics plots.
- **A very large front** can mean the objectives are nearly independent, or that the population never
  converged.

## Batches

`batches = n` in the `solver` section runs the same configuration `n` times independently.
Evolutionary search is stochastic, so a single run tells you little about whether a result is typical.
Comparing the fronts of several batches is the cheapest way to find out.

## Cancelling and cleaning up

Running executions can be cancelled from the executions panel, and finished ones can be deleted along
with their result files.
