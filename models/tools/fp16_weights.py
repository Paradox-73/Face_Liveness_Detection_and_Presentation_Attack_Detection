"""Store the float32 weights of an ONNX model as float16, keeping float32 compute.

Every float32 initializer with 16 or more values is replaced by a float16 copy plus a Cast node that turns it
back into float32 under its original name. ONNX Runtime folds these Cast nodes when it creates the session, so
the model runs exactly as the float32 original does, at the original speed, from a file about half the size.

Usage: python fp16_weights.py <source.onnx> <target.onnx> "<note stored in the model doc_string>"

Needs the `onnx` package (tested with onnx 1.17.0). The output is byte-for-byte reproducible.
"""
import sys

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper


def convert(source, target, note):
    model = onnx.load(source)
    graph = model.graph
    initializer_names = {t.name for t in graph.initializer}
    # Older exports also list initializers as graph inputs; drop those so they stay constants.
    real_inputs = [i for i in graph.input if i.name not in initializer_names]
    del graph.input[:]
    graph.input.extend(real_inputs)

    new_initializers = []
    casts = []
    for tensor in graph.initializer:
        values = numpy_helper.to_array(tensor)
        if values.dtype == np.float32 and values.size >= 16:
            new_initializers.append(numpy_helper.from_array(values.astype(np.float16), tensor.name + "_fp16"))
            casts.append(helper.make_node("Cast", [tensor.name + "_fp16"], [tensor.name],
                                          to=TensorProto.FLOAT, name=tensor.name + "_to_fp32"))
        else:
            new_initializers.append(tensor)
    del graph.initializer[:]
    graph.initializer.extend(new_initializers)
    nodes = list(graph.node)
    del graph.node[:]
    graph.node.extend(casts + nodes)
    model.doc_string = note
    onnx.checker.check_model(model)
    onnx.save(model, target)
    print(f"{target}: {len(casts)} weight tensors stored as float16")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    convert(sys.argv[1], sys.argv[2], sys.argv[3])
