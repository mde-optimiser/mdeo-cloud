using "./microservices.mm"
import "./generic/move-component.mt" as moveComponent
import "./sync/stores-of.mt" as storesOf
import "./sync/follow-host.mt" as followHost

// The operator a layer-3 configuration lists. It counts as one operator: the generic move and
// the repair always run together. If moveComponent fails, the whole operator fails.
call moveComponent(maxCandidates = 4) -> (moved: m, source: a, target: b)
call storesOf(reader = m) -> (stores: s)
for store in s do {
    call followHost(component = store, host = b)
}

// the outputs are bound like the objects of a match
match {
    m { }
    where a.name != b.name
}
