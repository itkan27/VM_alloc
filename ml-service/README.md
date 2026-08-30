# ML Service — VM Classifier

FastAPI service that exposes your trained ONNX MLP classifier.

## Quick Start

```bash
# 1. Create virtual environment
python -m venv venv
venv\Scripts\activate      # Windows
# source venv/bin/activate  # Linux/macOS

# 2. Install dependencies
pip install -r requirements.txt

# 3. (Optional) Place your trained ONNX model
#    After training on Colab/Kaggle, export and copy here:
mkdir models
# Copy mlp_classifier.onnx → models/mlp_classifier.onnx

# 4. Run the service
uvicorn main:app --reload --port 8000
```

The service runs at **http://localhost:8000**.
Interactive docs: **http://localhost:8000/docs**

---

## Training Your ONNX Model (Colab/Kaggle)

Train a scikit-learn or PyTorch MLP on features `[min_cpu, max_cpu, avg_cpu]`.

### scikit-learn → ONNX export

```python
from sklearn.neural_network import MLPClassifier
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType

# X: shape [n_samples, 3]  — [min_cpu, max_cpu, avg_cpu]
# y: 0 = STABLE, 1 = SPIKY
model = MLPClassifier(hidden_layer_sizes=(64, 32), max_iter=300)
model.fit(X_train, y_train)

initial_type = [('float_input', FloatTensorType([None, 3]))]
onnx_model = convert_sklearn(model, initial_types=initial_type)
with open("mlp_classifier.onnx", "wb") as f:
    f.write(onnx_model.SerializeToString())
```

**Required pip packages on Colab:** `skl2onnx onnx`

---

## ONNX Contract

| Property | Value |
|---|---|
| Input name | `float_input` |
| Input shape | `[batch, 3]`  → `[min_cpu, max_cpu, avg_cpu]` |
| Output[0]   | `label` — int64 `[batch]`, 0=STABLE 1=SPIKY |
| Output[1]   | `probabilities` — float32 `[batch, 2]`, [P(stable), P(spiky)] |

---

## Stub Behaviour (No Model)

If `models/mlp_classifier.onnx` is absent the service uses a heuristic:
- CPU range > 40% **and** avg < 60% of max → **SPIKY**
- Otherwise → **STABLE**

This lets the full pipeline run end-to-end while you train your model.
