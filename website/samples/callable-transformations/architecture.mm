// Layer 2 of a layered architecture metamodel: components placed on containers. A placement
// lists the containers a component may move to.

class Component {
    name: string
}

class Container {
    name: string
}

class Placement {
}

Component.host[1] --> Container
Placement.component[1] --> Component
Placement.candidates[1..*] --> Container
