using "./deployment.mm"

web : Component {
    name = "web"
    load = 3
    tier = Tier.FRONTEND
}

admin : Component {
    name = "admin"
    load = 1
    tier = Tier.FRONTEND
}

orders : Component {
    name = "orders"
    load = 5
    tier = Tier.BACKEND
}

billing : Component {
    name = "billing"
    load = 4
    tier = Tier.BACKEND
}

search : Component {
    name = "search"
    load = 6
    tier = Tier.BACKEND
}

database : Component {
    name = "database"
    load = 8
    tier = Tier.STORAGE
}

alpha : Server {
    name = "alpha"
    capacity = 10
}

beta : Server {
    name = "beta"
    capacity = 10
}

gamma : Server {
    name = "gamma"
    capacity = 12
}

web.host -- alpha
admin.host -- alpha
orders.host -- alpha
billing.host -- alpha
search.host -- alpha
database.host -- alpha
