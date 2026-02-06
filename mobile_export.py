import torch
import torch.nn as nn
import os
import argparse
from prior_depth_anything import PriorDepthAnything

def export_to_onnx(output_path="prior_depth_anything_vits.onnx"):
    print("Initializing model...")
    device = "cpu" # Export on CPU for simplicity
    
    # Initialize the model with mobile-optimized settings
    # coarse_only=False (we want the full pipeline)
    # frozen_model_size='vits' (smallest backbone)
    # conditioned_model_size='vits' (matching size)
    # double_global=True is set during forward/inference, not init, but we need to ensure the graph follows it.
    
    model = PriorDepthAnything(
        device=device,
        version='1.0',
        frozen_model_size='vits',
        conditioned_model_size='vits',
        coarse_only=False
    )
    
    import types
    # Monkey patch unify_format to bypass type casting
    def mobile_unify_format(self, images, sparse_depths, sparse_masks, cover_masks, prior_depths, geometric_depths):
        return images, sparse_depths, sparse_masks, cover_masks, prior_depths, geometric_depths
    
    model.completion.unify_format = types.MethodType(mobile_unify_format, model.completion)

    # Monkey patch raw2input to bypass numpy transforms and normalization
    def mobile_raw2input(self, raw_image, input_size=518, device='cpu'):
        return raw_image, (raw_image.shape[-2], raw_image.shape[-1])
        
    model.completion.depth_model.raw2input = types.MethodType(mobile_raw2input, model.completion.depth_model)
    if hasattr(model, 'model'):
        model.model.raw2input = types.MethodType(mobile_raw2input, model.model)
        
    # Monkey patch calc_scale_shift to avoid linalg.lstsq (unsupported in ONNX)
    def mobile_calc_scale_shift(self, k_sparse_targets, k_pred_targets, currk_dists=None, knn=False):
        # Implementation of Simple Linear Regression: y = scale * x + shift
        # We assume knn=False (global alignment) as double_global=True usage
        
        x = k_pred_targets.view(-1)
        y = k_sparse_targets.view(-1)
        n = x.numel()
        
        sum_x = torch.sum(x)
        sum_y = torch.sum(y)
        sum_xy = torch.sum(x * y)
        sum_xx = torch.sum(x * x)
        
        denominator = n * sum_xx - sum_x * sum_x
        # Avoid division by zero
        denominator = denominator + 1e-8
        
        scale = (n * sum_xy - sum_x * sum_y) / denominator
        shift = (sum_y - scale * sum_x) / n
        
        return scale, shift

    model.completion.calc_scale_shift = types.MethodType(mobile_calc_scale_shift, model.completion)
        
    model.eval()

    # Mock inputs
    # Android standard input size might be smaller, e.g., 518x518 or similar.
    # The model usually expects resizing to happen before.
    H, W = 518, 518
    
    dummy_image = torch.randn(1, 3, H, W).to(device)
    dummy_prior = torch.randn(1, H, W).to(device) # Prior depth
    
    # Needs sparse inputs for the forward pass, even if we are mocking.
    # We will construct a wrapper module to handle the preprocessing logic if needed, 
    # but PriorDepthAnything.forward takes specific tailored inputs.
    
    # Let's look at PriorDepthAnything.infer_one_sample to see how it calls forward.
    # It calls model.sampler(...) then model.forward(...)
    # The sampler does data preparation.
    # For ONNX, we ideally want to include as much as possible, OR just the heavy lifting model.forward.
    # Given the complexity of `sampler` (handling sparse masks etc from raw input), 
    # it might be better to export the core `forward` and implement `sampler` logic in Kotlin/C++ 
    # OR create a wrapper that does basic sampling.
    
    # Let's verify `forward` signature:
    # forward(images, sparse_depths, sparse_masks, cover_masks, prior_depths, geometric_depths, pattern)
    
    # Ideally for mobile, we want: Input(RGB, Prior) -> Output(Depth)
    # So we should wrap the sampler + forward.
    
    class MobileWrapper(nn.Module):
        def __init__(self, parent_model):
            super().__init__()
            self.model = parent_model
            # Hardcode args for mobile
            self.model.args.double_global = True 
            self.model.args.K = 100 # Default K
             
            # Pre-compute or fix parameters to avoid dynamic control flow if possible
            
        def forward(self, rgb, prior_depth):
            # RGB: [1, 3, H, W]
            # Prior: [1, H, W] or [1, 1, H, W]
            
            # Replicating minimal logic from PriorDepthAnything.infer_one_sample -> sampler -> forward
            # We assume inputs are already resized to [518, 518] for simplicity in this wrapper,
            # or the app handles resizing.
            
            # --- Sampler Logic Simplified ---
            # In infer_one_sample:
            # data = self.sampler(image, prior, ...)
            # We need to see what sampler does.
            # It seems to generate sparse_depths, sparse_masks, cover_masks from 'prior'.
            
            # Accessing internal functions of sampler from the parent model
            
            # Mocking the outputs of sampler for the export to work strictly on the core model first?
            # No, user wants "Process Flow".
            
            # Let's try to trace 'infer_one_sample' directly? 
            # infer_one_sample has non-tensor logic.
            
            # Let's stick to exporting 'forward' and provide the 'sampler' logic as separate code or simplified wrapper.
            # However, `sampler` seems to generate the sparse inputs which are critical.
            
            # Let's look at what forward expects:
            # images: [B, 3, H, W]
            # sparse_depths: [B, 1, H, W]
            # sparse_masks: [B, 1, H, W]
            # cover_masks: [B, 1, H, W]
            # prior_depths: [B, 1, H, W]
            
            # And `double_global=True` means we use Global Alignment.
            # Extra condition is spmask (version 1.1)
            
            # Let's assume for the mobile app, we just pass the PRIOR as the "Sparse Depth" source.
            # If the user provides a "Prior Depth", we can treat it as the sparse source.
            
            # To make it robust, let's export the `forward` function. 
            # The App will prepare these 4-5 tensors. It's not too hard.
            # But wait, `forward` has a lot of logic.
            
            B, _, H, W = rgb.shape
            
            # Dummy placeholders matching shapes
            sparse_depths = prior_depth # [1, H, W]
            sparse_masks = (prior_depth > 0).bool() # [1, H, W]
            cover_masks = torch.zeros_like(sparse_masks)
            prior_depths_input = prior_depth.unsqueeze(1) # Prior depth might need 4D or 3D?
            # unify_format: if prior_depths len==4 -> squeeze.
            # My patch preserves input. 
            # depth_completion uses prior_disparities = depth2disparity(prior_depths).
            # depth2disparity(x): return 1/x. shape agnostic.
            # scaled_preds[cover_masks] = prior_disparities[cover_masks].
            # scaled_preds is [1, 518, 518]. cover_masks [1, 518, 518].
            # so prior_disparities must be [1, 518, 518].
            # So prior_depths_input should be 3D.
            prior_depths_input = prior_depth
            
            # Fix double_global
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
    
    # Inputs for tracing
    dummy_rgb = torch.randn(1, 3, 518, 518).to(device)
    dummy_depth_prior = torch.rand(1, 518, 518).to(device)
    
    print("Exporting to ONNX...")
    torch.onnx.export(
        wrapper,
        (dummy_rgb, dummy_depth_prior),
        output_path,
        export_params=True,
        opset_version=17,
        do_constant_folding=True,
        input_names=['rgb', 'prior_depth'],
        output_names=['refined_depth'],
        dynamic_axes={
            'rgb': {0: 'batch_size', 2: 'height', 3: 'width'},
            'prior_depth': {0: 'batch_size', 1: 'height', 2: 'width'},
            'refined_depth': {0: 'batch_size', 2: 'height', 3: 'width'}
        }
    )
    print(f"Export completed: {output_path}")

if __name__ == "__main__":
    export_to_onnx()
