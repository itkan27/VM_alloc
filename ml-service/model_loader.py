"""
model_loader.py
Loads the ONNX MLP classifier at startup.

Expected ONNX model contract
─────────────────────────────
  Input  : float32 tensor of shape [batch, 3]
           columns → [min_cpu, max_cpu, avg_cpu]  (values 0–100)
  Output : int64   label   tensor [batch]  — 0 = STABLE, 1 = SPIKY
           float32 prob    tensor [batch, 2] — [P(stable), P(spiky)]

If the model file is absent, a heuristic stub is used so that the
backend works immediately while you train your model on Colab/Kaggle.
"""

import os
import logging
import numpy as np

logger = logging.getLogger(__name__)

MODEL_PATH = os.getenv("ONNX_MODEL_PATH", "models/mlp_classifier.onnx")

# Will be set to the InferenceSession after load
_session = None
_model_loaded = False


def load_model() -> None:
    """Called once at application startup."""
    global _session, _model_loaded

    if not os.path.exists(MODEL_PATH):
        logger.warning(
            "ONNX model not found at '%s'. Using heuristic stub. "
            "Train your model and place the .onnx file at that path, "
            "then restart the service.",
            MODEL_PATH,
        )
        _model_loaded = False
        return

    try:
        import onnxruntime as ort  # imported lazily so the service starts even without ort installed
        _session = ort.InferenceSession(MODEL_PATH, providers=["CPUExecutionProvider"])
        _model_loaded = True
        logger.info("ONNX model loaded successfully from '%s'.", MODEL_PATH)
    except Exception as exc:
        logger.error("Failed to load ONNX model: %s. Falling back to stub.", exc)
        _model_loaded = False


def predict(min_cpu: float, max_cpu: float, avg_cpu: float) -> tuple[str, float]:
    """
    Returns (vm_type, confidence).
    vm_type   : 'SPIKY' or 'STABLE'
    confidence: probability score in [0, 1]
    """
    if _model_loaded and _session is not None:
        return _predict_onnx(min_cpu, max_cpu, avg_cpu)
    return _predict_heuristic(min_cpu, max_cpu, avg_cpu)


def _predict_onnx(min_cpu: float, max_cpu: float, avg_cpu: float) -> tuple[str, float]:
    """Run inference using the loaded ONNX session."""
    features = np.array([[min_cpu, max_cpu, avg_cpu]], dtype=np.float32)
    input_name = _session.get_inputs()[0].name

    outputs = _session.run(None, {input_name: features})
    # outputs[0] = label tensor [batch], outputs[1] = prob tensor [batch, 2]
    label = int(outputs[0][0])
    probs = outputs[1][0]  # [P(stable), P(spiky)]

    if label == 1:
        return "SPIKY", float(probs[1])
    return "STABLE", float(probs[0])


def _predict_heuristic(min_cpu: float, max_cpu: float, avg_cpu: float) -> tuple[str, float]:
    """
    Stub heuristic used when no ONNX model is available.
    Logic: high CPU range (max-min > 40%) AND avg skewed low → spiky.
    """
    cpu_range = max_cpu - min_cpu
    if cpu_range > 40.0 and avg_cpu < (max_cpu * 0.6):
        confidence = min(0.5 + (cpu_range - 40.0) / 100.0, 0.95)
        return "SPIKY", round(confidence, 4)
    confidence = min(0.5 + (40.0 - cpu_range) / 100.0, 0.95)
    return "STABLE", round(confidence, 4)


def is_model_loaded() -> bool:
    return _model_loaded


def get_model_path() -> str | None:
    return MODEL_PATH if _model_loaded else None
