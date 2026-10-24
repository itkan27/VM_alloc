# Efficient VM Placement in Cloud Environments

> MLP-based classification (Spiky / Stable) + zoned CloudSim Plus allocation + live React dashboard

## Currently Implemented Features

| Feature | Description |
|---|---|
| Classification | Heuristic Spiky/Stable classifier (ML service call ready, currently disabled) |
| Performance & Eco Zones | High-MIPS Performance hosts for SPIKY VMs with peak headroom; Eco hosts densely packed with STABLE VMs |
| Energy model | `PowerModelHostSimple` on every host; energy integrated over simulated time, idle hosts powered off |
| Batch simulation | `POST /api/simulation/run` returns placements, per-host metrics and a summary |
| Live simulation | VMs arrive over time and placements stream to the dashboard over Server-Sent Events |
| Dashboard | Zone-coloured host charts, Zone column, energy card, Instant / Live modes with animated rows |
| Ablation policy | The midsem zone-unaware policy is kept (`sim.policy=MIDSEM`) for comparison |

The roadmap for the remaining phases is in [`docs/FUTURE_WORK_PLAN.md`](docs/FUTURE_WORK_PLAN.md).

## Architecture

```
┌─────────────────────┐     HTTP POST /classify/batch     ┌──────────────────────┐
│   React Frontend    │ ────────────────────────────────► │  Python ML Service   │
│   (Vite :5173)      │ ◄────────────────────────────────  │  (FastAPI :8000)     │
└────────┬────────────┘        SPIKY | STABLE              └──────────────────────┘
         │                                                         ▲
         │ POST /api/simulation/run      (batch)                   │
         │ POST /api/simulation/sessions + SSE stream (live)       │
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
│       ├── config/              (SimProperties ← sim.* keys, AsyncConfig, CorsConfig)
│       ├── controller/
│       │   ├── VmController.java              ← batch API
│       │   └── LiveSimulationController.java  ← live sessions + SSE stream
│       ├── live/                (session registry, event replay, LiveSimulationService)
│       ├── model/               (VmRequest, Zone, SimulationResult, live events, …)
│       ├── service/
│       │   ├── VmClassifierClient.java   ← calls ML service
│       │   └── VmPlacementService.java   ← batch orchestration
│       └── simulation/
│           ├── CloudSimRunner.java              ← builds zoned DC, runs batch / live simulations
│           ├── TrackedAllocationPolicy.java     ← shared ledger + zones; books only confirmed placements
│           ├── ZonedVmAllocationPolicy.java     ← Performance / Eco zone rules
│           ├── MidsemVmAllocationPolicy.java    ← midsem policy (ablation baseline)
│           ├── HostLedger.java                  ← per-host CPU / peak / RAM / BW bookkeeping
│           └── EnergyAccountant.java            ← E = Σ P(u)·Δt per host
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
        ├── hooks/useSimulationStream.js   ← EventSource subscription
        └── components/
            ├── VMSubmissionForm.jsx       ← Instant / Live mode
            ├── PlacementTable.jsx
            ├── HostMetricsChart.jsx
            ├── MetricsSummary.jsx
            └── LiveStatus.jsx
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
> Opens on `:5173`. The Vite dev server proxies `/api` (including the SSE stream) to `:8080`.

### Tests
```bash
cd backend
mvn test                                             # fast unit and scenario tests
mvn test -Dtest.excludedGroups= -Dgroups=validation  # slow randomized validation suite
```

## Classification Logic

| Feature | Description |
|---|---|
| `min_cpu` | Minimum historical CPU usage % |
| `max_cpu` | Maximum historical CPU usage % |
| `avg_cpu` | Average historical CPU usage % |

After training, export your scikit-learn model to ONNX and place it at
`ml-service/models/mlp_classifier.onnx`. See `ml-service/README.md` for details.

## Allocation Policy

Every VM has an **average demand** (`avgCpu% × mips × cores`) and a **peak demand**
(`maxCpu% × mips × cores`). The default `ZONED` policy keeps every host *peak-safe*:

```
Σ peak(spiky VMs) + Σ avg(stable VMs)  ≤  host capacity (pes × mips_per_pe)
```

so a host is never overloaded even if all of its spiky VMs spike at once.

| VM | Preferred | Overflow |
|---|---|---|
| **SPIKY** | Performance host with peak headroom (First-Fit packs, Worst-Fit spreads) | Eco host with no other spiky VM and peak headroom |
| **STABLE** | Eco host, Best-Fit (densest host that fits) | Performance host, only if peak headroom survives |

Each placement records the rule that chose it (`PERF_FIRST_FIT`, `ECO_BEST_FIT`,
`SPIKY_OVERFLOW_ECO`, `STABLE_OVERFLOW_PERF`), shown as a tooltip on the Zone badge.

Implemented in [`ZonedVmAllocationPolicy.java`](backend/src/main/java/com/vmalloc/simulation/ZonedVmAllocationPolicy.java).
The midsem rule (no two spiky VMs on one host, average demand only) remains available as `sim.policy=MIDSEM`.

## Live Simulation API

| Method | Path | Description |
|---|---|---|
| POST | `/api/simulation/sessions` | Body `{ "vms": [...], "timeScale": 120, "arrivalGapSeconds": 30 }` → `{ "sessionId" }` |
| GET | `/api/simulation/sessions/{id}/stream` | `text/event-stream`: `init`, `placement`, `placement_failed`, `host_update`, then `completed` or `failed` |
| GET | `/api/simulation/sessions/{id}` | Status, plus the final result once finished |
| POST | `/api/simulation/sessions/{id}/stop` | Stop a running session |

`timeScale` is simulated seconds per real second (120 plays a one-hour run in 30 s).
Events carry ids, so a reconnecting client (`Last-Event-ID`) only receives what it missed.

## Configuration

All keys live in `backend/src/main/resources/application.properties`.

| Property | Default | Meaning |
|---|---|---|
| `ml.service.url` | `http://localhost:8000` | ML service URL |
| `sim.policy` | `ZONED` | `ZONED` or `MIDSEM` |
| `sim.host.count` | `10` | Total hosts (both zones) |
| `sim.host.performance.count` | `3` | Performance Zone hosts (`0` = homogeneous Eco DC) |
| `sim.host.performance.pes` / `.mips` / `.ram` | `4` / `5000` / `65536` | Per-host PEs, MIPS per PE, RAM (MB) |
| `sim.host.performance.powerModel.maxWatts` / `.idleWatts` | `400` / `150` | Power model |
| `sim.host.eco.pes` / `.mips` / `.ram` | `4` / `1000` / `32768` | Per-host PEs, MIPS per PE, RAM (MB) |
| `sim.host.eco.powerModel.maxWatts` / `.idleWatts` | `250` / `80` | Power model |
| `sim.host.idlePolicy` | `POWER_OFF` | `POWER_OFF` (idle hosts draw 0 W) or `KEEP_IDLE` |
| `sim.zone.performance.strategy` | `FIRST_FIT` | `FIRST_FIT` or `WORST_FIT` for spiky VMs |
| `sim.zone.performance.maxSpikyPerHost` | `0` | Cap on spiky VMs per Performance host (`0` = peak headroom only) |
| `sim.zone.allowSpikyOverflowToEco` | `true` | Overflow rule for spiky VMs |
| `sim.zone.allowStableOverflowToPerf` | `true` | Overflow rule for stable VMs |
