import onnx
from onnxruntime.quantization import quantize_dynamic, QuantType
import os

def quantize_models(input_model_path):
    print(f"Quantizing {input_model_path}...")
    
    # 1. Float16 Quantization
    fp16_model_path = input_model_path.replace(".onnx", "_fp16.onnx")
    print(f"Generating Float16 model: {fp16_model_path}")
    try:
        from onnxconverter_common import float16
        model = onnx.load(input_model_path)
        fp16_model = float16.convert_float_to_float16(model, keep_io_types=True)
        onnx.save(fp16_model, fp16_model_path)
        print("Float16 conversion complete.")
    except ImportError:
        print("onnxconverter_common not found, trying via onnxruntime (experimental for fp16) or skipping.")
        # Fallback if onnxconverter_common is missing, though it's the standard way.
        # usually installed with onnxmltools or onnxconverter-common
        pass
    except Exception as e:
        print(f"Float16 conversion failed: {e}")

    # 2. INT8 Dynamic Quantization
    int8_model_path = input_model_path.replace(".onnx", "_int8.onnx")
    print(f"Generating INT8 model: {int8_model_path}")
    try:
        quantize_dynamic(
            model_input=input_model_path,
            model_output=int8_model_path,
            weight_type=QuantType.QUInt8 # QUInt8 is generally safer for mobile, QInt8 is also an option
        )
        print("INT8 conversion complete.")
    except Exception as e:
        print(f"INT8 conversion failed: {e}")

if __name__ == "__main__":
    input_model = "prior_depth_anything_vits.onnx"
    if not os.path.exists(input_model):
        print(f"Error: {input_model} not found.")
    else:
        quantize_models(input_model)
