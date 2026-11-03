using "../architecture.mm"

// A layer-2 operator that tells its caller what it changed. The outputs are assigned inside
// the pattern; a non-nullable output must be assigned on every path that succeeds.
// `placement` is optional and pins the object of the same name: a caller that passes one moves
// that placement's component; with none passed (as a mutation operator) any placement matches.
in placement: Placement?
in maxCandidates: int = 0
out moved: Component
out source: Container
out target: Container

match {
    placement: Placement { }
    component: Component { }
    current: Container { }
    destination: Container { }
    placement.component -- component
    placement.candidates -- destination
    where maxCandidates == 0 || placement.candidates.size() <= maxCandidates

    delete component.host -- current
    create component.host -- destination

    moved = component
    source = current
    target = destination
}
