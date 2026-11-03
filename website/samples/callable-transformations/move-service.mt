using "./microservices.mm"
import "./generic/move-component.mt" as moveComponent
import "./sync/follow-reader.mt" as followReader

// Moves a service, not just any component: the placement found here is passed on and pins
// moveComponent's placement. If it has no candidate to move to, nothing changes.
match {
    p: Placement { }
    service: Service { }
    p.component -- service
}
if call moveComponent(placement = p) -> (target: b) then {
    call followReader(moved = service, target = b) -> (followed: stores)
} else {
    stop
}
