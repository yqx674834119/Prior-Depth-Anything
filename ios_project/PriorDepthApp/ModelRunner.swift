import CoreML
import UIKit
import Vision

class ModelRunner: ObservableObject {
    @Published var status: String = "Ready"
    @Published var outputImage: UIImage? = nil
    
    // Core ML Model wrapper class provided by Xcode will be named 'PriorDepthAnythingVits'
    // However, since we are writing raw swift without Xcode's auto-gen, we simulate the usage.
    // In reality, user drags .mlpackage into Xcode, Xcode generates the class.
    
    func runInference(rgb: UIImage, prior: UIImage) {
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            DispatchQueue.main.async { self?.status = "Processing..." }
            
            do {
                // Initialize model (assuming generic loading for demo purposes if class not avail)
                // In production, user will perform: let model = try PriorDepthAnythingVits(configuration: config)
                // Here we show how to load compilation needed if not pre-compiled,
                // But typically we assume the class exists.
                
                let config = MLModelConfiguration()
                config.computeUnits = .all // Use Neural Engine
                
                // Assuming Xcode generated class 'PriorDepthAnythingVits'
                // let model = try PriorDepthAnythingVits(configuration: config)
                
                // For this example code to be valid Swift without the generated class,
                // we'll rely on generic MLModel structure or instructions.
                // But to make it "runnable" in Xcode by the user, we should write efficient code assuming the class keys.
                
                guard let modelURL = Bundle.main.url(forResource: "PriorDepthAnythingVits", withExtension: "mlmodelc") else {
                    throw NSError(domain: "App", code: 1, userInfo: [NSLocalizedDescriptionKey: "Model not found"])
                }
                let model = try MLModel(contentsOf: modelURL, configuration: config)
                
                // Preprocess
                // Resizing to 518x518
                guard let rgbBuffer = resizePixelBuffer(image: rgb, width: 518, height: 518),
                      let priorBuffer = resizePixelBuffer(image: prior, width: 518, height: 518) else {
                    throw NSError(domain: "App", code: 2, userInfo: [NSLocalizedDescriptionKey: "Image processing failed"])
                }
                
                // Create Standard inputs
                let inputFeatures = try MLDictionaryFeatureProvider(dictionary: [
                    "rgb": MLFeatureValue(pixelBuffer: rgbBuffer),
                    "prior_depth": MLFeatureValue(pixelBuffer: priorBuffer)
                ])
                
                // Run Prediction
                let output = try model.prediction(from: inputFeatures)
                
                // Get Output
                guard let outputTensor = output.featureValue(for: "refined_depth")?.multiArrayValue else {
                    throw NSError(domain: "App", code: 3, userInfo: [NSLocalizedDescriptionKey: "Output error"])
                }
                
                // Postprocess (MultiArray to UI Image)
                let resultImage = self.multiArrayToImage(outputTensor)
                
                DispatchQueue.main.async {
                    self?.outputImage = resultImage
                    self?.status = "Done"
                }
                
            } catch {
                DispatchQueue.main.async {
                    self?.status = "Error: \(error.localizedDescription)"
                }
                print(error)
            }
        }
    }
    
    private func resizePixelBuffer(image: UIImage, width: Int, height: Int) -> CVPixelBuffer? {
        // Simple resizing logic using Core Graphics
        let size = CGSize(width: width, height: height)
        UIGraphicsBeginImageContextWithOptions(size, false, 1.0)
        image.draw(in: CGRect(origin: .zero, size: size))
        let resizedImage = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        
        guard let resized = resizedImage, let cgImage = resized.cgImage else { return nil }
        
        var pixelBuffer: CVPixelBuffer?
        let attrs = [kCVPixelBufferCGImageCompatibilityKey: kCFBooleanTrue,
                     kCVPixelBufferCGBitmapContextCompatibilityKey: kCFBooleanTrue] as CFDictionary
        CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA, attrs, &pixelBuffer)
        
        guard let pb = pixelBuffer else { return nil }
        
        CVPixelBufferLockBaseAddress(pb, [])
        let context = CGContext(data: CVPixelBufferGetBaseAddress(pb),
                                width: width,
                                height: height,
                                bitsPerComponent: 8,
                                bytesPerRow: CVPixelBufferGetBytesPerRow(pb),
                                space: CGColorSpaceCreateDeviceRGB(),
                                bitmapInfo: CGImageAlphaInfo.noneSkipFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        
        context?.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
        CVPixelBufferUnlockBaseAddress(pb, [])
        
        return pb
    }
    
    private func multiArrayToImage(_ multiArray: MLMultiArray) -> UIImage? {
        // Assuming output shape [1, 1, 518, 518] or [1, 518, 518]
        // This is a simplified visualizer usually.
        // Needs robust conversion.
        let width = 518
        let height = 518
        let ptr = UnsafeMutablePointer<Float32>(OpaquePointer(multiArray.dataPointer))
        
        // Find min/max
        var minVal: Float = Float.greatestFiniteMagnitude
        var maxVal: Float = -Float.greatestFiniteMagnitude
        let count = multiArray.count
        
        for i in 0..<count {
            let val = ptr[i]
            if val < minVal { minVal = val }
            if val > maxVal { maxVal = val }
        }
        
        let range = maxVal - minVal > 1e-6 ? maxVal - minVal : 1.0
        
        let bytesPerPixel = 4
        let bytesPerRow = bytesPerPixel * width
        var pixels = [UInt8](repeating: 0, count: height * bytesPerRow)
        
        for i in 0..<count {
            let val = ptr[i]
            let norm = (val - minVal) / range
            let gray = UInt8(max(0, min(255, norm * 255)))
            
            // BGRA
            let offset = i * 4
            pixels[offset] = gray     // B
            pixels[offset+1] = gray   // G
            pixels[offset+2] = gray   // R
            pixels[offset+3] = 255    // A
        }
        
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        
        guard let context = CGContext(data: &pixels,
                                      width: width,
                                      height: height,
                                      bitsPerComponent: 8,
                                      bytesPerRow: bytesPerRow,
                                      space: colorSpace,
                                      bitmapInfo: bitmapInfo.rawValue),
              let cgImage = context.makeImage() else {
            return nil
        }
        
        return UIImage(cgImage: cgImage)
    }
}
