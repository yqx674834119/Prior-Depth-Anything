import torch
import torch.nn as nn
import coremltools as ct
from prior_depth_anything import PriorDepthAnything
import types

def export_to_coreml(output_path="PriorDepthAnythingVits.mlpackage"):
    print("Initializing model for Core ML export...")
    device = "cpu"
    
    # Initialize the model with mobile-optimized settings (same as Android)
    model = PriorDepthAnything(
        device=device,
        version='1.0',
        frozen_model_size='vits',
        conditioned_model_size='vits',
        coarse_only=False
    )
    
    # --- Monkey Patches (Crucial for Mobile/Export compatibility) ---
    
    # 1. Unify Format patch: bypass complex type casting
    def mobile_unify_format(self, images, sparse_depths, sparse_masks, cover_masks, prior_depths, geometric_depths):
        return images, sparse_depths, sparse_masks, cover_masks, prior_depths, geometric_depths
    model.completion.unify_format = types.MethodType(mobile_unify_format, model.completion)

    # 2. Raw2Input patch: bypass numpy transforms
    def mobile_raw2input(self, raw_image, input_size=518, device='cpu'):
        return raw_image, (raw_image.shape[-2], raw_image.shape[-1])
    model.completion.depth_model.raw2input = types.MethodType(mobile_raw2input, model.completion.depth_model)
    if hasattr(model, 'model'):
        model.model.raw2input = types.MethodType(mobile_raw2input, model.model)
        
    # 3. Calc Scale Shift patch: Avoid linalg.lstsq
    def mobile_calc_scale_shift(self, k_sparse_targets, k_pred_targets, currk_dists=None, knn=False):
        x = k_pred_targets.view(-1)
        y = k_sparse_targets.view(-1)
        n = x.numel()
        sum_x = torch.sum(x)
        sum_y = torch.sum(y)
        sum_xy = torch.sum(x * y)
        sum_xx = torch.sum(x * x)
        denominator = n * sum_xx - sum_x * sum_x + 1e-8
        scale = (n * sum_xy - sum_x * sum_y) / denominator
        shift = (sum_y - scale * sum_x) / n
        return scale, shift
    model.completion.calc_scale_shift = types.MethodType(mobile_calc_scale_shift, model.completion)
    
    model.eval()

    # --- Wrapper for Input/Output Definition ---
    class MobileWrapper(nn.Module):
        def __init__(self, parent_model):
            super().__init__()
            self.model = parent_model
            self.model.args.double_global = True 
             
        def forward(self, rgb, prior_depth):
            # Input: rgb [1, 3, 518, 518], prior_depth [1, 518, 518]
            sparse_depths = prior_depth # [1, H, W]
            sparse_masks = (prior_depth > 0).bool() # [1, H, W]
            cover_masks = torch.zeros_like(sparse_masks)
            prior_depths_input = prior_depth.unsqueeze(1)
            
            self.model.args.double_global = True 
            
            return self.model.forward(
                images=rgb,
                sparse_depths=sparse_depths,
                sparse_masks=sparse_masks,
                cover_masks=cover_masks,
                prior_depths=prior_depths_input,
                geometric_depths=None,
                pattern=None
            )

    wrapper = MobileWrapper(model)
    
    # Trace the model first using TorchScript
    print("Tracing model with TorchScript...")
    example_input_rgb = torch.randn(1, 3, 518, 518)
    example_input_prior = torch.rand(1, 518, 518)
    traced_model = torch.jit.trace(wrapper, (example_input_rgb, example_input_prior))
    
    # Convert to Core ML
    print("Converting to Core ML...")
    mlmodel = ct.convert(
        traced_model,
        inputs=[
            ct.TensorType(name="rgb", shape=(1, 3, 518, 518)), # Can optimize to ImageType later layout flexibility
            ct.TensorType(name="prior_depth", shape=(1, 518, 518))
        ],
        outputs=[
            ct.TensorType(name="refined_depth")
        ],
        minimum_deployment_target=ct.target.iOS16 # 确保较新的支持
    )
    
    # Save the model
    mlmodel.save(output_path)
    print(f"Core ML model saved to {output_path}")

if __name__ == "__main__":
    export_to_coreml()
