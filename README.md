# Efficient VM Placement in Cloud Environments

> MLP-based classification (Spiky / Stable) + CloudSim Plus Eco/Performance zones with spike-driven live migration + live React dashboard

## Currently Implemented Features

| Feature | Description |
|---|---|
| Classification | Heuristic Spiky/Stable classifier (ML service call ready, currently disabled) |
| Performance & Eco Zones | Decoupled zones: every VM starts in the Eco Zone (one spiky VM per host, stable VMs best-fit); the Performance Zone starts empty |
| Spike simulation | Odd-numbered spiky VMs spike 15 s after start for 5 s, even-numbered after 20 s for 5 s (repeating); CPU → maxCpu, RAM usage → 90% |
| Live migration | A spiky VM crossing a CPU / RAM / MIPS threshold is live-migrated to the Performance Zone and back to the Eco Zone once all thresholds drop |
| Energy model | `PowerModelHostSimple` on every host; energy integrated over simulated time from the live load, idle hosts powered off |
| Batch simulation | `POST /api/simulation/run` returns placements, per-host metrics and a summary |
| Live simulation | VMs arrive over time; placements, spikes and migrations stream to the dashboard over Server-Sent Events |
| Dashboard | Live datacenter view with migration arrows, migration log, zone-coloured host charts, energy and migration cards, Instant / Live modes |
| Comparison with base paper | Our zoned policy vs the base paper's ARA (Kumar et al., IMED 2026), First Fit, Best Fit and our midsem policy on the same workload; Comparison tab with % improvement, charts, the paper's Table II and CSV export |
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
│       ├── comparison/          (ComparisonService, WorkloadGenerator, base paper Table II)
│       ├── controller/
│       │   ├── VmController.java              ← batch API
│       │   ├── LiveSimulationController.java  ← live sessions + SSE stream
│       │   └── ComparisonController.java      ← policy comparison
│       ├── live/                (session registry, event replay, LiveSimulationService)
│       ├── model/               (VmRequest, Zone, SimulationResult, live events, …)
│       ├── service/
│       │   ├── VmClassifierClient.java   ← calls ML service
│       │   └── VmPlacementService.java   ← batch orchestration
│       └── simulation/
│           ├── CloudSimRunner.java              ← builds zoned DC, runs batch / live simulations
│           ├── TrackedAllocationPolicy.java     ← shared ledger + zones; books only confirmed placements
│           ├── ZonedVmAllocationPolicy.java     ← Eco-only initial placement + migration targets
│           ├── MidsemVmAllocationPolicy.java    ← midsem policy (ablation baseline)
│           ├── AraVmAllocationPolicy.java       ← base paper ARA scoring placement
│           ├── AraConsolidation.java            ← base paper ARA consolidation + EWMA prediction
│           ├── FirstFit/BestFitVmAllocationPolicy.java ← base paper baselines
│           ├── SpikeSchedule.java               ← simulated VM load (odd / even spike windows)
│           ├── SpikeMigrationController.java    ← spike simulation + executes live migrations
│           ├── ZoneSpikeMigration.java          ← our thresholds → Eco ⇄ Performance migrations
│           ├── HostLedger.java                  ← per-host CPU (avg + current) / RAM / BW bookkeeping
│           └── HostMonitor.java                 ← energy, utilisation, overload time per host
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
            ├── LiveStatus.jsx
            ├── DatacenterView.jsx         ← hosts per zone, VM chips, migration arrows
            ├── MigrationLog.jsx
            └── ComparisonPanel.jsx        ← Comparison tab
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
mvn test -Dtest.excludedGroups= -Dgroups=validation  # validation suite (deferred; excluded from the default build)
```

## Classification Logic

| Feature | Description |
|---|---|
| `min_cpu` | Minimum historical CPU usage % |
| `max_cpu` | Maximum historical CPU usage % |
| `avg_cpu` | Average historical CPU usage % |

After training, export your scikit-learn model to ONNX and place it at
`ml-service/models/mlp_classifier.onnx`. See `ml-service/README.md` for details.

## Allocation Policy & Live Migration

Every VM has an **average demand** (`avgCpu% × mips × cores`) and a **peak demand**
(`maxCpu% × mips × cores`).

**1. Initial placement — Eco Zone only** (`ZonedVmAllocationPolicy`)

| VM | Rule |
|---|---|
| **SPIKY** | First-Fit on an Eco host with no other spiky VM (at most one per host) |
| **STABLE** | Best-Fit on an Eco host (densest host that still fits) |

On every host `Σ avg demand ≤ capacity`. The Performance Zone starts empty.

**2. Spike simulation** (`SpikeSchedule`) — spiky VMs are numbered 1, 2, 3 … in submission order:

| Spiky VM | Spike window (from VM start) | During the spike |
|---|---|---|
| odd (1, 3, 5 …) | after 15 s, for 5 s | CPU = maxCpu, RAM usage = 90% |
| even (2, 4, 6 …) | after 20 s, for 5 s | CPU = maxCpu, RAM usage = 90% |

With `sim.spike.repeat=true` the window recurs for as long as the VM runs.

**3. Live migration** (`SpikeMigrationController`) — checked every simulated second:

* **ANY** threshold crossed (CPU > 70%, RAM > 85%, demand > 3000 MIPS) while on an Eco host
  → live-migrate to the first Performance host with room for the VM's current demand.
* **ALL** thresholds back below while on a Performance host
  → live-migrate back to the VM's original Eco host, or another Eco host with no spiky VM.

Migrations use CloudSim's live migration: memory is copied at the target host's bandwidth
(8 GB onto a 40 Gbps Performance host ≈ 1.6 s). Until a migration lands, the spike loads
the Eco host, which is reported as overload time.

The midsem rule (no zones, no migration) remains available as `sim.policy=MIDSEM`.

## Live Simulation API

| Method | Path | Description |
|---|---|---|
| POST | `/api/simulation/sessions` | Body `{ "vms": [...], "timeScale": 5, "arrivalGapSeconds": 5 }` → `{ "sessionId" }` |
| GET | `/api/simulation/sessions/{id}/stream` | `text/event-stream`: `init`, `placement`, `placement_failed`, `spike`, `migration`, `host_update`, then `completed` or `failed` |
| GET | `/api/simulation/sessions/{id}` | Status, plus the final result once finished |
| POST | `/api/simulation/sessions/{id}/stop` | Stop a running session |

`timeScale` is simulated seconds per real second (5 makes each 5 s spike visible for about a second).
Events carry ids, so a reconnecting client (`Last-Event-ID`) only receives what it missed.

## Comparison with the Base Paper

Base paper: *An Adaptive Resource-Aware Virtual Machine Placement for Energy-Efficient Cloud
Computing Environments* (Kumar, Jain & Pareek, IMED 2026). Its ARA algorithm (Algorithm 1) is
implemented as `sim.policy=ARA`:

* **Filter** hosts whose current free CPU, RAM, storage and bandwidth cover the VM's predicted demand.
* **Score** `α·Available_CPU + β·Available_RAM + γ·Energy_Efficiency − δ·Migration_Cost`
  (each term 0–1; energy efficiency = MIPS per watt after placement, migration cost = memory copy
  time) and place on the highest score.
* **Predict** each VM's demand with an exponentially weighted moving average of its observed load.
* **Consolidate** every 30 s: VMs on hosts below 20% CPU are migrated onto busier active hosts and
  the emptied hosts power down.

`POST /api/comparison/run` runs the selected policies (default: all five) on one workload, each
in its own simulation with identical VMs, datacenter and simulated spikes. With no `vms` in the
body a seeded workload is generated; defaults follow the paper's scale (200 VMs, 50 hosts).

| Metric | Definition |
|---|---|
| Hosts Used | Hosts that ran at least one VM |
| Avg CPU Utilisation | Time-averaged CPU utilisation of active hosts |
| SLA Violation Rate | % of placed VMs that ran on an overloaded host (demand > capacity) at some point |
| Energy | Σ P(u)·Δt over hosts, idle hosts powered off |
| VM Migrations | Completed live migrations |
| Placement Time | Mean wall-clock time per host-selection decision |
| Failed Allocations | VMs no host could take |
| Host Overload Time | Σ seconds hosts spent overloaded |

The **Comparison** tab shows these side by side, the % improvement of our zoned policy over a
chosen baseline (ARA by default), one chart per metric, the paper's published Table II for
reference (different set-up: PlanetLab traces, 24 h), and a CSV export. PSO from the paper is not
implemented.

## Configuration

All keys live in `backend/src/main/resources/application.properties`.

| Property | Default | Meaning |
|---|---|---|
| `ml.service.url` | `http://localhost:8000` | ML service URL |
| `sim.policy` | `ZONED` | `ZONED`, `MIDSEM`, `ARA`, `FIRST_FIT` or `BEST_FIT` |
| `sim.host.count` | `10` | Total hosts (both zones) |
| `sim.host.performance.count` | `3` | Performance Zone hosts |
| `sim.host.performance.pes` / `.mips` / `.ram` / `.bandwidth` | `4` / `5000` / `65536` / `40000` | Per-host PEs, MIPS per PE, RAM (MB), bandwidth (Mbps) |
| `sim.host.performance.powerModel.maxWatts` / `.idleWatts` | `400` / `150` | Power model |
| `sim.host.eco.pes` / `.mips` / `.ram` / `.bandwidth` | `4` / `1000` / `32768` / `10000` | Per-host PEs, MIPS per PE, RAM (MB), bandwidth (Mbps) |
| `sim.host.eco.powerModel.maxWatts` / `.idleWatts` | `250` / `80` | Power model |
| `sim.host.idlePolicy` | `POWER_OFF` | `POWER_OFF` (idle hosts draw 0 W) or `KEEP_IDLE` |
| `sim.spike.repeat` | `true` | Repeat each VM's spike window while it runs |
| `sim.spike.odd.afterSeconds` / `.durationSeconds` | `15` / `5` | Spike window of odd-numbered spiky VMs |
| `sim.spike.even.afterSeconds` / `.durationSeconds` | `20` / `5` | Spike window of even-numbered spiky VMs |
| `sim.spike.ramBaselinePercent` / `ramSpikePercent` | `50` / `90` | VM RAM usage outside / during a spike |
| `sim.migration.enabled` | `true` | Live migration on/off (spikes are simulated either way) |
| `sim.migration.cpuThresholdPercent` | `70` | VM CPU % that triggers migration (0 = off) |
| `sim.migration.ramThresholdPercent` | `85` | VM RAM usage % that triggers migration (0 = off) |
| `sim.migration.mipsThreshold` | `3000` | VM demand in MIPS that triggers migration (0 = off) |
| `sim.migration.bandwidthPercent` | `1.0` | Share of target host bandwidth used to copy VM memory |
| `sim.ara.alpha` / `beta` / `gamma` / `delta` | `0.4` / `0.3` / `0.3` / `0.2` | ARA score weights |
| `sim.ara.underutilizedThresholdPercent` | `20` | ARA consolidates hosts below this CPU % |
| `sim.ara.consolidationIntervalSeconds` | `30` | How often ARA consolidates |
| `sim.ara.predictionSmoothing` | `0.5` | EWMA weight of the newest load sample |
| `sim.ara.migrationCostNormSeconds` | `10` | Migration time that counts as maximum cost |
