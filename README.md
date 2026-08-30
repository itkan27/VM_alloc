# Efficient VM Placement in Cloud Environments

> MLP-based classification (Spiky / Stable) + CloudSim Plus allocation + React dashboard

## Architecture

```
┌─────────────────────┐     HTTP POST /classify/batch     ┌──────────────────────┐
│   React Frontend    │ ────────────────────────────────► │  Python ML Service   │
│   (Vite :5173)      │ ◄────────────────────────────────  │  (FastAPI :8000)     │
└────────┬────────────┘        SPIKY | STABLE              └──────────────────────┘
         │                                                         ▲
         │ POST /api/simulation/run                                │
         ▼                                                         │
┌─────────────────────┐    calls ML service internally    ─────────┘
│  Java Backend       │
│  (Spring Boot :8080)│
│  + CloudSim Plus    │
└─────────────────────┘
```

## Project Structure

```
VM_alloc/
├── backend/                    ← Java Spring Boot + CloudSim Plus
│   ├── pom.xml
│   └── src/main/java/com/vmalloc/
│       ├── VmAllocApplication.java
│       ├── config/CorsConfig.java
│       ├── controller/VmController.java
│       ├── model/               (VmRequest, VmType, SimulationResult, …)
│       ├── service/
│       │   ├── VmClassifierClient.java   ← calls ML service
│       │   └── VmPlacementService.java   ← orchestration
│       └── simulation/
│           ├── CloudSimRunner.java         ← runs simulation
│           └── CustomVmAllocationPolicy.java  ← NO two spiky on same host
│
├── ml-service/                 ← Python FastAPI + ONNX
│   ├── main.py
│   ├── model_loader.py
│   ├── schemas.py
│   ├── requirements.txt
│   ├── README.md               ← ONNX training/export guide
│   └── models/                 ← put mlp_classifier.onnx here
│
└── frontend/                   ← React + Vite
    └── src/
        ├── App.jsx
        ├── index.css
        ├── services/api.js
        └── components/
            ├── VMSubmissionForm.jsx
            ├── PlacementTable.jsx
            ├── HostMetricsChart.jsx
            └── MetricsSummary.jsx
```

## Running All Three Services

### 1. ML Service (Python)
```bash
cd ml-service
python -m venv venv && venv\Scripts\activate
pip install -r requirements.txt
uvicorn main:app --reload --port 8000
```
> Without a model file the heuristic stub kicks in automatically.

### 2. Java Backend
```bash
cd backend
mvn spring-boot:run
```
> Runs on `:8080`. If the ML service is down, the Java fallback heuristic is used.

### 3. React Frontend
```bash
cd frontend
npm install
npm run dev
```
> Opens on `:5173`.

## Classification Logic

| Feature | Description |
|---|---|
| `min_cpu` | Minimum historical CPU usage % |
| `max_cpu` | Maximum historical CPU usage % |
| `avg_cpu` | Average historical CPU usage % |

After training, export your scikit-learn model to ONNX and place it at
`ml-service/models/mlp_classifier.onnx`. See `ml-service/README.md` for details.

## Allocation Policy

**Rule**: No two `SPIKY` VMs may share the same host.

- **SPIKY** → first-fit on a host that has zero spiky VMs currently allocated  
- **STABLE** → best-fit (densest available host) for maximum consolidation

Implemented in [`CustomVmAllocationPolicy.java`](backend/src/main/java/com/vmalloc/simulation/CustomVmAllocationPolicy.java).

## Configuration

| Property | File | Default |
|---|---|---|
| ML service URL | `application.properties` | `http://localhost:8000` |
| Host count | `application.properties` | `10` |
| Cores per host | `application.properties` | `8` |
| Host MIPS | `application.properties` | `3000` |
| Host RAM | `application.properties` | `32768 MB` |
