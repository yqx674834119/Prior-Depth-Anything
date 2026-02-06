import onnxruntime as ort
import numpy as np
import os

def verify(model_path):
    if not os.path.exists(model_path):
        print(f"Error: {model_path} not found.")
        return

    print(f"Verifying {model_path}...")
    try:
        session = ort.InferenceSession(model_path)
        print("Session created successfully.")
        
        # Check inputs
        for input_meta in session.get_inputs():
            print(f"Input: {input_meta.name}, Shape: {input_meta.shape}, Type: {input_meta.type}")
            
        # Run dummy inference
        rgb = np.random.randn(1, 3, 518, 518).astype(np.float32)
        prior = np.random.randn(1, 1, 518, 518).astype(np.float32) # Exported wrapper expects this shape if I did it right?
        # Wait, in export script:
        # dummy_rgb = torch.randn(1, 3, 518, 518)
        # dummy_depth_prior = torch.rand(1, 518, 518)
        # So prior expects [1, 518, 518]
        
        prior = np.random.randn(1, 518, 518).astype(np.float32)

        inputs = {
            session.get_inputs()[0].name: rgb,
            session.get_inputs()[1].name: prior
        }
        
        outputs = session.run(None, inputs)
        print("Inference successful.")
        print(f"Output Name: {session.get_outputs()[0].name}")
        print(f"Output Shape: {outputs[0].shape}")
        
    except Exception as e:
        print(f"Verification failed: {e}")

if __name__ == "__main__":
    verify("prior_depth_anything_vits.onnx")
