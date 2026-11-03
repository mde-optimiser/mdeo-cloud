# Config MDEO plugin

Contributes the `search`, `solver` and `runtime` sections: which moves the search may make, which
algorithm makes them, and what resources the run may use. It also owns the executable section, so this
is the plugin that actually starts an optimisation.

## At a glance

| | |
| --- | --- |
| **Plugin id** | `config-mdeo-service` |
| **Display name** | Config MDEO |
| **Description** | Language support for config MDEO sections (search and solver) |
| **Default URL** | `/plugin/config-mdeo` |
| **Source** | `app/packages/service-config-mdeo`, `app/packages/language-config-mdeo` |
| **Depends on** | The [Config](/plugins/config) and [Config Optimization](/plugins/config-optimization) plugins; executions go to `optimizer-execution` |

## Languages contributed

| Language id | Name | Extension | Textual editor | Graphical editor | Generated |
| --- | --- | --- | --- | --- | --- |
| `config-mdeo` | Config MDEO | — | ❌ | ❌ | ✅ |

### The `config-mdeo` language

A generated language with no extension and no editor, used the same way as
[`config-optimization`](/plugins/config-optimization#the-config-optimization-language): the config
service sends it the fragment of a `.config` file containing this plugin's sections, and it parses
them with a standalone grammar built from the same rules.

## Contribution plugins contributed

| Target language | Sections | Executable |
| --- | --- | --- |
| `config` | `search`, `solver`, `runtime` | `solver` |

| | |
| --- | --- |
| **Contribution plugin id** | `config-mdeo` |
| **Short name** | `mdeo` |
| **Grammar dependencies** | `config-optimization` |
| **Section dependencies** | `problem` from `optimization` |

The section dependency matters: before this plugin can resolve `create Annotation` or
`mutate Canvas.layers` it has to know which metamodel the problem uses. The config service therefore
computes the `problem` section first and passes its result along.

### The `search` section

The operators available to the search: **mutation operators**, which change one model, and
**crossover operators**, which combine several.

```mdeo-config
search {
    mutations {
        using "./match.mt"

        create Annotation
        delete Annotation
        mutate Circle

        add Layer.shapes
        remove Layer.shapes
        mutate Canvas.layers
    }
}
```

| Entry | Meaning |
| --- | --- |
| `using "path.mt"` | Use a hand-written model transformation as a mutation operator |
| `using "path.mt" with { … }` | The same, with values for its inputs — see [Operator arguments](#operator-arguments) |
| `create Class` | Generate operators that add an object of the class, or of each concrete subclass |
| `delete Class` | Generate an operator that removes an object of the class or of a subclass |
| `add Class.edge` | Generate operators that link objects through the association end |
| `remove Class.edge` | Generate operators that unlink them |
| `mutate Class.edge` | Both of the above |
| `mutate Class` | `create`, `delete` and `mutate Class.edge` for every association end of the class |

`edge` is an association end of the class, declared on it or inherited; attributes are never changed.

A `using` entry may name a transformation whose metamodel is the problem's metamodel or one the
problem's metamodel imports. A [callable transformation](/plugins/model-transformation#callable-transformations)
is a mutation operator if it has no [model inputs](/plugins/model-transformation#model-inputs) and
every required input — an input without default and with a non-nullable type — is passed by the
entry's `with { }` block. List the transformation that calls the others, not the callees: each
listed file is one operator, whatever it calls.
Entries may be mixed and repeated in any order. Generated operators are written into the result tree
as `.mt_gen` files, so you can read exactly what the search was allowed to do.

#### Generated operators

Generated operators never break a multiplicity: applied to a model in which every object has an
allowed number of links through each association end, they produce a model in which it still does.
Refinements from the [goal section](/plugins/config-optimization#the-goal-section) count as part of
the metamodel, so the operators keep the refined bounds too.

Which operators an entry produces depends on the multiplicities at both ends of the association:

| Operator | Generated for | What it does |
| --- | --- | --- |
| `ADD_A_e`, `REMOVE_A_e` | ends where neither side is fixed | Link or unlink an `A` and a `B` |
| `CHANGE_A_e` | a fixed end, or a fixed opposite | Move a link to another partner |
| `SWAP_A_e` | a fixed end, or ends whose bounds can all be reached at once | Two `A`s exchange a partner |
| `CREATE_A_…` | every container and combination of repairs | Add an object and link it as its bounds require |
| `DELETE_A` | the class | Remove an object, moving partners that need a fixed number of links |

[Generated mutation operators](/guide/generated-operators) explains each operator, the cases in which
none can exist, and how the generator differs from the papers it is based on. Operators that could not
be generated, or are limited, are listed in `generated-mutations/warnings.md` in the results.

#### Operator arguments

A `using` entry, in `mutations` and in `crossovers`, may pass values to the `in` declarations of its
transformation in a `with { }` block, one `name = value` per line. One file can so serve as several
differently tuned operators:

```mdeo-config
search {
    mutations {
        using "./move.mt" with {
            maxLoad = 4
        }
        using "./move.mt" with {
            maxLoad = 100
        }
        using "./swap.mt"
    }
}
```

The values are literals:

| Literal | Examples | Passed to |
| --- | --- | --- |
| `int` | `4`, `-1` | `int`, `long`, `float`, `double` |
| `long` | `42L` | `long`, `float`, `double` |
| `float` | `2.5f`, `2.5E-3f` | `float`, `double` |
| `double` | `2.5`, `1e3`, `2.5d` | `double` |
| `string`, `boolean` | `"fast"`, `true` | `string`, `boolean` |
| enum literal | `Tier.FRONTEND` | an input of that enum of the problem's metamodel |
| list | `[1, 2, 3]`, `[Tier.FRONTEND, Tier.BACKEND]`, `[]` | `List<T>` and `Set<T>` inputs, each element as above |

Numbers convert as in the transformation language — `int` to `long` to `float` to `double` — and
never the other way round, so a `float` input takes `2.5f`, not `2.5`. An `int` literal must fit an
`int` (write `3000000000L` for a `long` input), and a `long` literal must lie within ±2<sup>53</sup>,
since the configuration travels as JSON numbers. A `Set` keeps one of equal values. There are no
expressions, no references to model elements and no `null`: leave the argument out instead, and the
input takes its default, or null when it is optional.

The validator checks each block against the transformation's declarations: every name must be an
input — outputs and unknown names are errors, and so is a name set twice — and only scalar, enum and
`List` / `Set`-of-scalar or enum inputs can be set. Element inputs (`Class`, `Class?`,
`List<Class>`) and model inputs cannot; the optimizer binds the models of a crossover itself. An
input the block sets counts as supplied, so `in maxLoad: int` without default is fine in an
operator as long as every entry of the file sets it; an entry that does not is an error naming the
input. An empty `with { }` is redundant and reported as a warning. Completion inside the block offers
the inputs that can still be set, and hovering a name shows its declaration.

Arguments are bound exactly as the arguments of a [`call`](/plugins/model-transformation#callable-transformations),
before the first statement. Each `using` entry is an operator of its own — two entries of the same
file are two operators in the random choice of operators.
The results show each with its arguments, e.g. `move.mt (maxLoad = 4)`.

#### Crossover operators

A `crossovers` block lists **crossover operators**: transformations that read further models through
[model inputs](/plugins/model-transformation#model-inputs). It only takes `using` entries, with or
without `with { }`; there are no generated crossover operators.

```mdeo-config
search {
    crossovers {
        using "./take-tier.mt"                 // in donor: model         → 2 parents
        using "./take-tier.mt" with {
            tier = Tier.FRONTEND
        }
        using "./follow-donors.mt"             // in donors: List<model>  → any number of parents
    }
}
```

A crossover operator runs on a copy of one parent, the *primary*, which becomes the child, and reads
the other parents of its group, the *donors*, through its model inputs. A transformation is a
crossover operator when

- it has at least one model input: either single `model` inputs — then it takes one parent more
  than it has model inputs, and the donors are bound to them in declaration order — or exactly one
  `List<model>` input and no single one — then it takes any number of parents, and the list holds
  all donors;
- every other required input is passed by its `with { }` block; outputs are ignored;
- it accepts the configured [`crossover { parents }`](#crossover): a fixed-arity operator needs
  exactly `parents - 1` model inputs.

A file with model inputs under `mutations`, or without under `crossovers`, is an error that names the
block it belongs in. Crossover operators only run with [`variation = genetic`](#how-variation-genetic-makes-offspring);
otherwise the block is reported as unused.

### The `solver` section

The search algorithm and when to stop. This is the **executable** section: a config file containing it
gets a run action.

```mdeo-config
solver {
    algorithm = NSGAII

    parameters {
        population = 40
        variation = genetic

        crossover {
            rate = 0.9
            parents = 2
            strategy = random
        }

        mutation {
            step = interval(1, 5)
            strategy = random
        }
    }

    termination {
        evolutions = 500
    }

    batches = 3
}
```

| Key | Values | Meaning |
| --- | --- | --- |
| `algorithm` | `NSGAII`, `IBEA`, `SPEA2`, `SMSMOEA` (SMS-EMOA), `VEGA`, `PESA2`, `PAES` | The multi-objective algorithm |
| `batches` | integer | Run the same configuration this many times independently |

#### `parameters`

| Key | Values | Meaning |
| --- | --- | --- |
| `population` | integer | Population size |
| `variation` | `mutation`, `genetic` | How offspring are produced: by mutation alone, or by crossover and mutation |
| `bisections` | integer | PESA2 / PAES only |
| `crossover { … }` | block | Crossover tuning for `variation = genetic`, see below |
| `mutation { … }` | block | Mutation tuning, see below |
| `archive { size = n }` | block | PESA2 / PAES only |

#### `mutation`

| Key | Values | Meaning |
| --- | --- | --- |
| `step` | `n`, `fixed`, `fixed(n)`, `interval(lo, hi)` | How many operators to apply per mutation |
| `strategy` | `random`, `repetitive` | How operators are chosen |
| `selection` | `random` | How matches are selected |
| `application` | `random` | How an operator is applied |
| `credit` | `random` | Operator credit assignment |
| `repair` | `default` | Repair strategy after a mutation |

`step = interval(1, 5)` draws uniformly from `[1, 5)` on every call; `fixed` with no argument means
one. `step = 0` (or `fixed(0)`) applies no operator: offspring are then copies of their parents, or
with `variation = genetic` exactly what the crossover made. The initial population is made by
mutating the start model with the same step, so with `step = 0` it consists of copies of the start
model, and crossovers between them change nothing until the models differ.

#### `crossover`

| Key | Values | Default | Meaning |
| --- | --- | --- | --- |
| `rate` | number in `[0, 1]` | `0.9` | Probability that a group of parents is crossed over |
| `parents` | integer ≥ 2 | `2` | Parents per crossover; every crossover yields one child per parent |
| `strategy` | `random` | `random` | How a crossover operator is chosen |

Each key may appear once. The block only applies with `variation = genetic` and is reported as
unused otherwise; without it, `genetic` uses the defaults.

#### How `variation = genetic` makes offspring

`variation = mutation` produces every offspring by mutating a copy of one parent. `variation =
genetic` needs a non-empty `crossovers` block, and makes offspring from groups of `parents` parents
that the algorithm selects:

1. **Rate.** Whether a group is crossed over is drawn once per group, with probability `rate`, on the
   node that coordinates the run. A group that is not crossed over yields a copy of each parent.
2. **One child per parent.** A group of `parents` parents yields `parents` children — two for the
   default. Child *i* has parent *i* as its primary parent and the others as donors, in cyclic order
   starting after *i*: with parents `A, B, C`, the children are `A ← (B, C)`, `B ← (C, A)` and
   `C ← (A, B)`.
3. **Operator choice.** Each child picks a crossover operator on its own, uniformly among the
   operators that accept the group's parent count, and runs it on a copy of its primary parent with
   the donors as model inputs. An operator applies when the transformation succeeds or `stop`s —
   so a `for match` that finds nothing applies without changing the child. When it fails, or
   `kill`s, its changes are undone and another untried operator is tried; when all fail, the child
   is a copy of its primary parent.
4. **Mutation.** Every child — crossed over or copied — is then mutated with the `mutation { step,
   strategy }` settings, exactly as an offspring of `variation = mutation`.
5. **Evaluation.** The child's objectives and constraints are evaluated as usual.

Neither parent is changed. The optimizer remembers which mutation operators failed on a model so it
can skip them on unchanged copies (the *skipped* transformations of the metrics); a crossover's
outcome depends on its donors as much as on the child, so crossover failures are never remembered.
With `rate = 0`, `genetic` behaves like `mutation`. In a run spread over several nodes, a child is
made on the node holding its primary parent; donors held by other nodes are copied there for that
generation and dropped afterwards.

How the children enter the population depends on the algorithm. When `population` is not a multiple
of `parents`, the last group of a generation makes up to `parents - 1` children more than the
population size — the *overshoot*.

| Algorithm | Children of a generation |
| --- | --- |
| `NSGAII` | Groups are selected by binary tournament until at least `population` children exist. All are evaluated — the overshoot too — and compete with the population; non-dominated sorting and crowding distance cut the union back to `population`. |
| `SPEA2` | As NSGA-II; the union is cut back by strength fitness and density. |
| `IBEA` | As NSGA-II; the indicator-worst solution is removed until `population` remain. |
| `SMSMOEA` | Steady state: each step inserts one child and removes the worst solution. The children of a group are evaluated together and inserted over `parents` consecutive steps, so every child enters the population (the parents selected in those steps are not used). Children still waiting when the run ends were evaluated but not inserted. |
| `VEGA` | Not elitist: the population is rebuilt from the children of consecutive groups, and parents do not survive. Children beyond `population` are dropped before they are evaluated. |
| `PESA2` | The population is rebuilt every generation from groups selected from the archive by region, until at least `population` children exist; all are evaluated and offered to the archive, whose `size` bounds what survives. |
| `PAES` | Not supported: PAES evolves one solution with one mutation per step, so `variation = genetic` with `PAES` is an error. |

The population size stays `population` for every algorithm. The `evolutions` of the termination
block are counted in evaluations — `evolutions × population` of them — so overshooting children
count too.

In the results, `summary.md` lists the mutation and the crossover operators separately, each with its
arguments, and plots per generation how many children crossed over successfully and how many fell
back to a copy, and how often each crossover operator applied. `report.json` holds the same under
`operators.crossovers` and, per generation, `crossover { attempted, succeeded, fellBack, perOperator }`,
counted per child; `perOperator` is keyed by the operator's id in `operators.crossovers`.

#### `termination`

| Key | Meaning |
| --- | --- |
| `evolutions` | Stop after this many generations (`evolutions × population` evaluations) |
| `time` | Stop after this many seconds |
| `delta` | Stop when the improvement stays below this threshold |
| `iterations` | Number of non-improving iterations tolerated before stopping |

Several conditions combine with OR — the first one to trigger ends the run.

### The `runtime` section

Execution limits.

```mdeo-config
runtime {
    timeout {
        script = 1000
        transformation = 1000
    }

    backend = MDEO

    resources {
        threads = 10
        nodes = 4
        threadsPerNode = 3
    }
}
```

| Key | Meaning |
| --- | --- |
| `timeout.script` | Milliseconds a single objective or constraint evaluation may take |
| `timeout.transformation` | Milliseconds a single transformation application may take |
| `backend` | `MDEO` (default) or `Tinker` — the graph representation used during search |
| `resources.threads` | Upper bound on total worker threads |
| `resources.nodes` | Upper bound on execution nodes taking part |
| `resources.threadsPerNode` | Upper bound on threads per node |

All fields are optional and act as upper bounds against what the deployment actually offers.

### Full example

<<< @/../samples/language-tour/full.config{mdeo-config}

### Example with crossover

A deployment of components on servers, optimised with mutation and crossover operators. The
crossover operators are shown under [Model inputs](/plugins/model-transformation#model-inputs).

<<< @/../samples/crossover/optimize.config{mdeo-config}

<<< @/../samples/crossover/move.mt{mt}

## Server-side capabilities

| Key | Kind | Contents |
| --- | --- | --- |
| `ast` | file data | The serialised AST of the standalone language |
| `config` | request | Computes the section data for `search`, `solver` and `runtime` |
| `config-execution` | request | Starts an optimisation run |
| `config-execution-get-summary` | request | The markdown summary of a run |
| `config-execution-get-file-tree` | request | The result file tree |
| `config-execution-get-file` / `-get-files` | request | Result file contents |
| `config-execution-cancel` | request | Cancel a running optimisation |
| `config-execution-delete` | request | Delete a finished run and its results |

Execution requests are forwarded to the `optimizer-execution` service, which is the component that
scales across nodes. See [Reading the results](/guide/results) for what comes back.
