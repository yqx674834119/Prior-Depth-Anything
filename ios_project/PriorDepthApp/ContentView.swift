import SwiftUI

struct ContentView: View {
    @State private var rgbImage: UIImage? = nil
    @State private var priorImage: UIImage? = nil
    @State private var showingImagePickerRGB = false
    @State private var showingImagePickerPrior = false
    
    @StateObject private var modelRunner = ModelRunner()
    
    var body: some View {
        VStack(spacing: 20) {
            Text("Prior Depth Anything (iOS)")
                .font(.title)
                .bold()
            
            HStack(spacing: 20) {
                VStack {
                    if let img = rgbImage {
                        Image(uiImage: img)
                            .resizable()
                            .scaledToFit()
                            .frame(height: 120)
                    } else {
                        Rectangle()
                            .fill(Color.gray.opacity(0.2))
                            .frame(height: 120)
                            .overlay(Text("RGB"))
                    }
                    Button("Select RGB") { showingImagePickerRGB = true }
                }
                
                VStack {
                    if let img = priorImage {
                        Image(uiImage: img)
                            .resizable()
                            .scaledToFit()
                            .frame(height: 120)
                    } else {
                        Rectangle()
                            .fill(Color.gray.opacity(0.2))
                            .frame(height: 120)
                            .overlay(Text("Prior"))
                    }
                    Button("Select Prior") { showingImagePickerPrior = true }
                }
            }
            .padding()
            
            Button(action: {
                if let rgb = rgbImage, let prior = priorImage {
                    modelRunner.runInference(rgb: rgb, prior: prior)
                }
            }) {
                Text("Run Inference")
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color.blue)
                    .foregroundColor(.white)
                    .cornerRadius(10)
            }
            .disabled(rgbImage == nil || priorImage == nil)
            .padding(.horizontal)
            
            Text(modelRunner.status)
                .font(.caption)
            
            if let result = modelRunner.outputImage {
                VStack {
                    Text("Result")
                    Image(uiImage: result)
                        .resizable()
                        .scaledToFit()
                        .frame(height: 250)
                }
            }
            
            Spacer()
        }
        .sheet(isPresented: $showingImagePickerRGB) {
            ImagePicker(image: $rgbImage)
        }
        .sheet(isPresented: $showingImagePickerPrior) {
            ImagePicker(image: $priorImage)
        }
    }
}

struct ImagePicker: UIViewControllerRepresentable {
    @Binding var image: UIImage?

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    class Coordinator: NSObject, UINavigationControllerDelegate, UIImagePickerControllerDelegate {
        let parent: ImagePicker

        init(_ parent: ImagePicker) {
            self.parent = parent
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey : Any]) {
            if let uiImage = info[.originalImage] as? UIImage {
                parent.image = uiImage
            }
            picker.dismiss(animated: true)
        }
    }
}
