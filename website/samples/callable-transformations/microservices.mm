// Layer 3: microservice concepts on top of layer 2. A copy feed keeps a copy of data, owned by
// the store component, for its reader. The store has to stay with its reader.

import "./architecture.mm"

class Service extends Component {
}

class CopyFeed {
}

CopyFeed.reader[1] --> Component
CopyFeed.store[1] --> Component
