using "./shapes.mm"

// A `forbid` block rejects the match as soon as its whole sub-pattern is found, a `require`
// block demands that its whole sub-pattern is found. Every block is matched on its own, so
// two blocks reject a match independently while the elements of one block only reject it
// together.
match {
    layer: Layer {
        index >= 0
    }
    rectangle: Rectangle { }

    create generated: Circle {
        name = rectangle.name
        visible = true
        colour = Colour.GREEN
        tags = []
        radius = 1.0
    }
    create layer.shapes -- generated

    // no circle may sit on this layer yet ...
    forbid noCircleOnLayer {
        circle: Circle { }
        layer.shapes -- circle
    }

    // ... and no invisible rectangle may exist anywhere in the model
    forbid noHiddenRectangle {
        hidden: Rectangle {
            visible == false
        }
    }

    // A `where` clause inside a block constrains that block: the condition only holds when its
    // graph is found *and* the clause is satisfied. It may compare the block's own objects with
    // each other and with everything the match has bound.
    forbid noWiderRectangle {
        wider: Rectangle { }
        where wider.width > rectangle.width
    }

    // the layer has to belong to a canvas
    require onCanvas {
        canvas: Canvas { }
        canvas.layers -- layer
    }
}
