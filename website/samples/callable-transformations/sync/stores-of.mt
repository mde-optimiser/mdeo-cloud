using "../microservices.mm"

// Collects the stores that keep a copy for a reader. A collection output starts empty.
in reader: Component
out stores: List<Component>

for match {
    reader { }
    feed: CopyFeed { }
    store: Component { }
    feed.reader -- reader
    feed.store -- store
} do {
    stores += store
}
