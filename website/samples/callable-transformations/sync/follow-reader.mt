using "../microservices.mm"

// A layer-3 repair. Its element inputs are bound nodes of its patterns: `moved { }` is the
// component the caller passed, not a new match. Finding nothing to repair is a success.
// `+=` adds every store it moves to the collection output.
in moved: Component
in target: Container
out followed: Set<Component>

for match {
    moved { }
    target { }
    feed: CopyFeed { }
    store: Component { }
    old: Container { }
    feed.reader -- moved
    feed.store -- store
    delete store.host -- old
    create store.host -- target
    forbid {
        store.host -- target
    }
    followed += store
} do {
}
