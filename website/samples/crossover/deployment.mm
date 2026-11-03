// Components deployed on servers. Every component runs on exactly one server; the
// optimisation looks for a deployment that balances the load on few servers.

enum Tier {
    FRONTEND
    BACKEND
    STORAGE
}

class Component {
    name: string
    load: int
    tier: Tier
}

class Server {
    name: string
    capacity: int
}

Component.host[1] <--> Server.components[*]
