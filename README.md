# 🧠 Smart ML Scanner (Prompt Master)

> **Next-Gen Offline Document Intelligence for Android**  
> *High-precision OCR with structure preservation, powered by on-device AI.*

![Kotlin](https://img.shields.io/badge/Kotlin-1.9.20-blue.svg?logo=kotlin)
![Platform](https://img.shields.io/badge/Platform-Android%2010%2B-green.svg?logo=android)
![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)
![Status](https://img.shields.io/badge/Status-Active_Dev-orange.svg)

## 🚀 Overview

**Smart ML Scanner** is not just another OCR wrapper. It addresses the critical flaw of mobile OCR: **loss of structure**. While most scanners flatten text into a blob, this project reconstructs the original document layout (indentation, code blocks, paragraphs) using coordinate-based heuristics.

**Current Phase:** Moving from "Wrapper" to **"Hybrid Neural Pipeline"**.  
We are building a privacy-first, offline-only document analysis tool that combines Computer Vision (OpenCV) for precision, Neural Networks (PaddleOCR) for reading, and Small Language Models (SLMs) for understanding.

## ✨ Key Features

### ✅ Currently Implemented (v0.5)
- **Structure-Aware OCR:** Unique algorithm (`TextFormatPreserver`) that analyzes Bounding Box coordinates to reconstruct indentation levels (crucial for scanning code snippets or poetry).
- **Material 3 UI:** Modern, clean Jetpack Compose interface.
- **CameraX Integration:** Fast, reactive camera viewfinder with zoom and torch control.
- **Privacy First:** Zero data leaves the device. All processing is local.

### 🚧 In Development (The "Hybrid Pipeline")
- **Advanced Preprocessing (OpenCV):**
    - Document edge detection & auto-cropping.
    - Perspective warping (flattening angled photos).
    - Adaptive binarization (removing shadows/noise).
- **Neural Engine Switch:** Migrating from ML Kit to **PaddleOCR v4 (ONNX)** for superior table & layout recognition.
- **Semantic Understanding (RAG):**
    - Local Vector Database (ObjectBox) for semantic search ("Show me that receipt for coffee").
    - **SLM Post-processing:** Using Qwen-2.5-1.5B (via llama.cpp) to correct OCR typos and extract JSON data.

## 🛠 Tech Stack

*   **Core:** Kotlin, Coroutines, Flow
*   **UI:** Jetpack Compose (Material 3)
*   **Vision:** CameraX, ML Kit (legacy), OpenCV (incoming), ONNX Runtime (incoming)
*   **Architecture:** MVI / Clean Architecture
*   **DI:** Koin
*   **Concurrency:** Kotlin Coroutines & Channels

## 📐 Architecture Highlight: Text Format Preservation

Standard OCR returns a bag of words. We preserve meaning through geometry:

```
// Logic snippet: Preserving python/kotlin indentation from photos
val minX = sortedLines.minOf { it.boundingBox.left }
val indentLevel = ((lineX - minX) / CHAR_WIDTH_PX).coerceAtLeast(0)
val indent = "    ".repeat(indentLevel)
```

## 🗺️ Roadmap

| Phase | Goal | Status |
|-------|------|--------|
| **1** | **MVP:** CameraX + ML Kit + Formatting Logic | ✅ **Done** |
| **2** | **Vision:** OpenCV Preprocessing (Crop/Warp) | 🔄 **In Progress** |
| **3** | **Brain:** Replace ML Kit with PaddleOCR (ONNX) | 📅 Planned |
| **4** | **Intellect:** Add Local RAG (Vector DB + Search) | 📅 Planned |
| **5** | **Polish:** KMP support for iOS | 🔮 Future |

## 🤝 Contributing

We are exploring the limits of **Mobile Edge AI**. If you know:
*   Kotlin / C++ (JNI)
*   ONNX / TensorFlow Lite
*   OpenCV android sdk

...pull requests are welcome!

## 📄 License

Distributed under the MIT License. See `LICENSE` for more information.
