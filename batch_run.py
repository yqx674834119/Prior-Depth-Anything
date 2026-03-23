import os
import h5py
import numpy as np
import cv2
import torch
import matplotlib.pyplot as plt
from tqdm import tqdm
from prior_depth_anything import PriorDepthAnything

def normalize_depth(depth, vmin=None, vmax=None):
    valid_mask = depth > 0.0001
    if not valid_mask.any():
        return np.zeros_like(depth, dtype=np.uint8)
    
    if vmin is None:
        vmin = depth[valid_mask].min()
    if vmax is None:
        vmax = depth[valid_mask].max()
    
    norm = np.zeros_like(depth)
    if vmax > vmin:
        norm = (depth - vmin) / (vmax - vmin)
        norm = np.clip(norm, 0, 1)
    
    norm_uint8 = (norm * 255.0).astype(np.uint8)
    vis = cv2.applyColorMap(norm_uint8, cv2.COLORMAP_MAGMA)
    # Set invalid pixels to black (only mask if the array is large enough, avoid breaking on single 1x1 pixels)
    if vis.shape[0] > 1 and vis.shape[1] > 1:
        vis[~valid_mask] = 0
    return vis

def save_ply(depth, rgb, filename, fx=500.0, fy=500.0, cx=320.0, cy=240.0):
    H, W = depth.shape
    i, j = np.meshgrid(np.arange(W), np.arange(H), indexing='xy')
    z = depth
    valid = z > 0.0001
    x = (i - cx) * z / fx
    y = (j - cy) * z / fy
    points = np.stack((x[valid], y[valid], z[valid]), axis=-1)
    colors = rgb[valid]
    with open(filename, 'w') as f:
        f.write("ply\n")
        f.write("format ascii 1.0\n")
        f.write(f"element vertex {len(points)}\n")
        f.write("property float x\n")
        f.write("property float y\n")
        f.write("property float z\n")
        f.write("property uchar red\n")
        f.write("property uchar green\n")
        f.write("property uchar blue\n")
        f.write("end_header\n")
        for p, c in zip(points, colors):
            f.write(f"{p[0]:.4f} {p[1]:.4f} {p[2]:.4f} {c[0]} {c[1]} {c[2]}\n")

def process_single_h5(priorda_refined, priorda_baseline, h5_path, out_dir):
    filename = os.path.basename(h5_path)
    prefix = os.path.splitext(filename)[0]
    
    sample_out_dir = os.path.join(out_dir, prefix)
    os.makedirs(sample_out_dir, exist_ok=True)
    
    # 1. Read H5 data
    with h5py.File(h5_path, 'r') as f:
        rgb = f['rgb'][:]
        gt_depth = f['depth'][:]
        fr = f['fr'][:]
        hist_data = f['hist_data'][:]
        mask = f['mask'][:]
        
    rgb_bgr = cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
    image_path = os.path.join(sample_out_dir, "rgb.png")
    cv2.imwrite(image_path, rgb_bgr)
    
    # 2. Construct 8x8 sparse prior
    L5_depth_sparse = np.zeros([480, 640], dtype=np.float32)
    for i in range(mask.shape[0]):
        if not mask[i]: continue
        if fr[i, 2] < 0 or fr[i, 3] < 0: continue
        
        sy, sx, ey, ex = fr[i]
        sy, sx = np.clip(sy, 0, 480), np.clip(sx, 0, 640)
        ey, ex = np.clip(ey, 0, 480), np.clip(ex, 0, 640)
        center_y, center_x = int((sy + ey) / 2), int((sx + ex) / 2)
        if center_y >= 480: center_y = 479
        if center_x >= 640: center_x = 639
        L5_depth_sparse[center_y, center_x] = hist_data[i, 0]
        
    sparse_npy_path = os.path.join(sample_out_dir, "sparse_prior_8x8.npy")
    np.save(sparse_npy_path, L5_depth_sparse)
    
    # 3. Model Inference (Baseline zero-prior + Refined 8x8 prior)
    print(f"\n[{prefix}] Running inference...")
    with torch.no_grad():
        # A. Baseline zero-prior inference
        # In prior_depth_anything, passing zero prior or arbitrary pattern without mask usually fails if 
        # pattern matching isn't satisfied. However, we can use completion directly or pass dense zero map.
        dummy_prior = np.zeros_like(L5_depth_sparse)
        dummy_prior_path = os.path.join(sample_out_dir, "dummy_prior.npy")
        np.save(dummy_prior_path, dummy_prior)
        
        # When PriorDepthAnything is instanced with coarse_only=True, it just returns coarse depths.
        # It still runs the preprocess so it accepts dummy prior as long as it handles empty masks.
        # But we must be careful with 'K' minimum point constraints.
        # Actually, using the DepthCompletion backbone directly circumvents the SparseSampler crash.
        int_image = torch.from_numpy(rgb).permute(2,0,1).unsqueeze(0).to(torch.uint8).to(priorda_baseline.device)
        # Calling depth_model from completion skips PriorSampler constraint crashes.
        # heit = 518 from depth_always_v2
        baseline_pred_disparities = priorda_baseline.completion.depth_model(int_image, 518, device=priorda_baseline.device)
        from prior_depth_anything.utils import disparity2depth
        baseline_depth_tensor = disparity2depth(baseline_pred_disparities)
        pred_depth_baseline = baseline_depth_tensor.squeeze().cpu().numpy()
        
        # B. Refined inference
        pred_depth_refined_tensor = priorda_refined.infer_one_sample(
            image=image_path, 
            prior=sparse_npy_path, 
            visualize=False
        )
        pred_depth_refined = pred_depth_refined_tensor.cpu().numpy()
    
    # Generate custom 6-column comparison visualization
    print(f"[{prefix}] Generating visualizations & pointclouds...")
    plt.figure(figsize=(36, 6))
    
    valid_gt = gt_depth > 0.0001
    vmin = gt_depth[valid_gt].min() if valid_gt.any() else 0
    vmax = gt_depth[valid_gt].max() if valid_gt.any() else 4.0
    
    # Col 1: RGB
    plt.subplot(1, 6, 1)
    plt.imshow(rgb)
    plt.title("RGB")
    plt.axis("off")
    
    # Col 2: GT
    plt.subplot(1, 6, 2)
    gt_vis = normalize_depth(gt_depth, vmin, vmax)
    plt.imshow(cv2.cvtColor(gt_vis, cv2.COLOR_BGR2RGB))
    plt.title("GT Depth (RealSense)")
    plt.axis("off")
    
    # Col 3: Sparse Prior Thicken
    plt.subplot(1, 6, 3)
    sparse_vis = np.zeros_like(gt_vis)
    sparse_pts = np.nonzero(L5_depth_sparse)
    for y, x in zip(*sparse_pts):
        color = normalize_depth(np.array([[[L5_depth_sparse[y, x]]]]), vmin, vmax)[0, 0]
        cv2.circle(sparse_vis, (x, y), 3, color.tolist(), -1)
    plt.imshow(cv2.cvtColor(sparse_vis, cv2.COLOR_BGR2RGB))
    plt.title("Sparse Prior (8x8 ToF)")
    plt.axis("off")
    
    # Col 4: Baseline Zero-Prior
    # The baseline depth is unconditioned so its metric scale (e.g., 0~1m) might not align with GT (e.g. 1~3m). 
    # To visualize its structure fairly, we apply a median scalar matching to the GT scale.
    if np.median(gt_depth[valid_gt]) > 0 and np.median(pred_depth_baseline) > 0:
        scale_factor = np.median(gt_depth[valid_gt]) / np.median(pred_depth_baseline)
        pred_depth_baseline_aligned = pred_depth_baseline * scale_factor
    else:
        pred_depth_baseline_aligned = pred_depth_baseline

    baseline_vis = normalize_depth(pred_depth_baseline_aligned, vmin, vmax)
    plt.subplot(1, 6, 4)
    plt.imshow(cv2.cvtColor(baseline_vis, cv2.COLOR_BGR2RGB))
    plt.title("Baseline (Aligned Scale)")
    plt.axis("off")
    
    # Col 5: Refined Depth
    plt.subplot(1, 6, 5)
    refined_vis = normalize_depth(pred_depth_refined, vmin, vmax)
    plt.imshow(cv2.cvtColor(refined_vis, cv2.COLOR_BGR2RGB))
    plt.title("Refined Depth (with 8x8 Prior)")
    plt.axis("off")
    
    # Col 6: Absolute Error Map (Refined vs GT)
    plt.subplot(1, 6, 6)
    err_plot = np.full_like(gt_depth, np.nan)
    err_plot[valid_gt] = np.abs(pred_depth_refined[valid_gt] - gt_depth[valid_gt])
    im = plt.imshow(err_plot, cmap='inferno')
    plt.colorbar(im, fraction=0.046, pad=0.04, label="Absolute Error (Refined - GT)")
    plt.title("Absolute Error Map")
    plt.axis("off")
    
    plt.tight_layout()
    comp_path = os.path.join(sample_out_dir, "comparison_6col.png")
    plt.savefig(comp_path, dpi=150, bbox_inches='tight')
    plt.close()
    
    # Export numpy outputs
    np.save(os.path.join(sample_out_dir, "pred_depth_baseline.npy"), pred_depth_baseline)
    np.save(os.path.join(sample_out_dir, "pred_depth_refined.npy"), pred_depth_refined)
    np.save(os.path.join(sample_out_dir, "gt_depth.npy"), gt_depth)

    # Automatically generate 3D colored point clouds (PLY)
    save_ply(gt_depth, rgb, os.path.join(sample_out_dir, "pointcloud_gt.ply"))
    save_ply(pred_depth_baseline, rgb, os.path.join(sample_out_dir, "pointcloud_baseline.ply"))
    save_ply(pred_depth_refined, rgb, os.path.join(sample_out_dir, "pointcloud_refined.ply"))


def main():
    target_files = [
        r"C:\Users\Shawn\StudioProjects\Prior-Depth-Anything\resource\DataFusion\demo\demo\room\1647156045.486036.h5",
        r"C:\Users\Shawn\StudioProjects\Prior-Depth-Anything\resource\DataFusion\demo\demo\room\1647156050.207249.h5",
        r"C:\Users\Shawn\StudioProjects\Prior-Depth-Anything\resource\DataFusion\demo\demo\room\1647156055.714385.h5",
        r"C:\Users\Shawn\StudioProjects\Prior-Depth-Anything\resource\DataFusion\demo\demo\room\1647156060.441652.h5",
        r"C:\Users\Shawn\StudioProjects\Prior-Depth-Anything\resource\DataFusion\demo\demo\room\1647156062.410617.h5"
    ]
    
    out_dir = r"C:\Users\Shawn\StudioProjects\Prior-Depth-Anything\resource\DataFusion\demo\batch_output_v2"
    os.makedirs(out_dir, exist_ok=True)
    
    device = "cuda:0" if torch.cuda.is_available() else "cpu"
    print(f"Loading Models on {device}...")
    
    # Model configuration
    priorda_refined = PriorDepthAnything(device=device)
    # Using coarse_only=True is explicitly instructed by the README for first-stage baseline tests
    priorda_baseline = PriorDepthAnything(device=device, coarse_only=True)
    
    for count, h5_path in enumerate(target_files, 1):
        if not os.path.exists(h5_path):
            print(f"Error: File not found {h5_path}")
            continue
        print(f"\n--- Processing {count}/{len(target_files)}: {os.path.basename(h5_path)} ---")
        process_single_h5(priorda_refined, priorda_baseline, h5_path, out_dir)

    print(f"\nPhase 2 Batch processing complete! All outputs saved to {out_dir}")

if __name__ == '__main__':
    main()
