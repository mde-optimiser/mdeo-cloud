using "./deployment.mm"

// Mutation: moves a component to another server. `maxLoad` has no default, so every
// configuration that lists this operator passes it in a `with { }` block.
in maxLoad: int

match {
    component: Component {
        load <= maxLoad
    }
    current: Server { }
    target: Server { }
    delete component.host -- current
    create component.host -- target
}
