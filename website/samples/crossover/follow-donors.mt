using "./deployment.mm"

// Crossover of any number of parents: the other parents arrive as the list `donors`. Each
// donor passes on the placement of one component that it hosts differently from the child.
in donors: List<model>

for d in donors do {
    if match {
        in d {
            dc: Component { }
            ds: Server { }
            dc.host -- ds
        }
        c: Component {
            name == dc.name
        }
        current: Server { }
        target: Server {
            name == ds.name
        }
        delete c.host -- current
        create c.host -- target
    } then {
    }
}
