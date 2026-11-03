using "./deployment.mm"

// Crossover of two parents. It runs on a copy of one parent, the child, and reads the other
// parent as the input model `donor`: every component of one tier moves to the server that
// hosts its namesake in the donor. Objects of different models are matched by their names.
in donor: model
in tier: Tier = Tier.BACKEND

for match {
    in donor {
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
    where c.tier == tier
    delete c.host -- current
    create c.host -- target
} do {
}
