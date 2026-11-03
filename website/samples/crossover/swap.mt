using "./deployment.mm"

// Mutation: two components on different servers exchange their servers.
match {
    a: Component { }
    b: Component { }
    first: Server { }
    second: Server { }
    delete a.host -- first
    delete b.host -- second
    create a.host -- second
    create b.host -- first
}
