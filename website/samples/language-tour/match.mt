using "./shapes.mm"

// A plain match rewrites the model once. Every element of the pattern has to be found
// before any of the marked changes are applied.
//
// * an unmarked element has to exist and stays untouched
// * `create` adds an object or a link
// * `delete` removes one
// * `where` adds an arbitrary boolean condition
// * `var` binds a value that can be used further down the pattern
// * `forbid` / `require` blocks state what must not / must be findable — see
//   application-conditions.mt
match {
    layer: Layer {
        index >= 0
    }
    rectangle: Rectangle {
        visible == true
    }
    var label = rectangle.name

    create generated: Circle {
        name = label
        visible = true
        colour = Colour.GREEN
        tags = []
        radius = 1.0
    }
    create layer.shapes -- generated
    where rectangle.width > rectangle.height

    forbid noCircleYet {
        circle: Circle { }
    }
    require onCanvas {
        canvas: Canvas { }
        canvas.layers -- layer
    }
}
