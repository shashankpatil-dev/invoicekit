// Test template that takes a moment to compile. Each payload is different, so Typst can't reuse a result.
#let request = json(bytes(sys.inputs.payload))
#let total = 0
#for number in range(800000) { total = number + request.id }
#total
