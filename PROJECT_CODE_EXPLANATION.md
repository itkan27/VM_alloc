# VM Allocation Project: Complete Code and System-Flow Explanation

This document explains the implemented frontend and Java backend, how a VM request
travels through the system, how placement and migration decisions are made, and
how simulation results are returned to the dashboard.

The ML service implementation is intentionally outside the scope of this document.
The backend classification boundary is described only where it is required to
understand the rest of the application.

## 1. Project Purpose

The project simulates VM placement in a CloudSim Plus datacenter with two zones:

- **Eco Zone**: standard, lower-capacity hosts used for initial VM placement.
- **Performance Zone**: higher-capacity hosts kept available for spiky VM workloads.

VMs are classified as either:

- **SPIKY**: their historical CPU range suggests that their demand can rise sharply.
- **STABLE**: their demand is expected to remain comparatively steady.

The default zoned policy follows this lifecycle:

1. Every VM initially enters the Eco Zone.
2. Spiky VMs are isolated during initial placement: at most one spiky VM per Eco
   host.
3. Stable VMs are densely packed into Eco hosts using best-fit placement.
4. A spiky VM is simulated at its average load until its configured spike window.
5. If a migration threshold is crossed, it is moved to the Performance Zone.
6. The migration target prefers an unused Performance host, preserving isolation.
7. If all suitable Performance hosts are occupied, the first suitable host is reused.
8. When the spike ends and all migration thresholds are below their limits, the VM
   returns to its original Eco host when possible.
9. Host utilization, overload duration, active time, migration counts, and energy
   are collected for the final result.

The application supports both:

- **Instant/batch simulations**, which return one complete response.
- **Live simulations**, which run asynchronously and stream events using SSE.

## 2. Repository Structure

```text
VM_proj/
├── README.md
├── PROJECT_CODE_EXPLANATION.md
├── backend/
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/com/vmalloc/
│       │   └── resources/application.properties
│       └── test/
├── frontend/
│   ├── package.json
│   ├── vite.config.js
│   └── src/
└── ml-service/
```

The main application code is divided into:

| Area | Responsibility |
|---|---|
| `frontend/src` | React dashboard, request form, REST calls, SSE stream handling, charts, and tables |
| `backend/src/main/java/com/vmalloc/controller` | HTTP endpoints |
| `backend/src/main/java/com/vmalloc/service` | Request orchestration and classification boundary |
| `backend/src/main/java/com/vmalloc/live` | Asynchronous live-session lifecycle and SSE event replay |
| `backend/src/main/java/com/vmalloc/model` | Request, result, event, and enum records |
| `backend/src/main/java/com/vmalloc/simulation` | CloudSim construction, allocation policy, spike simulation, migration, accounting, and monitoring |
| `backend/src/main/java/com/vmalloc/config` | Spring, CORS, executor, and simulation configuration |

## 3. High-Level Architecture

```text
Browser
  │
  │ React UI
  │
  ├── POST /api/simulation/run
  │       │
  │       ▼
  │   VmController
  │       │
  │       ▼
  │   VmPlacementService
  │       │
  │       ├── classify VM requests
  │       └── CloudSimRunner.run(...)
  │
  └── POST /api/simulation/sessions
          │
          ▼
      LiveSimulationController
          │
          ▼
      LiveSimulationService
          │
          ├── background TaskExecutor
          └── SimulationSession
                  │
                  └── SSE: init, placement, spike, migration,
                           host_update, completed, failed

CloudSimRunner
  ├── creates CloudSim Plus simulation
  ├── builds Performance and Eco hosts
  ├── creates CloudSim VMs and cloudlets
  ├── installs allocation policy
  ├── runs SpikeMigrationController on clock ticks
  └── returns SimulationResult
```

The browser never performs placement decisions. It sends VM input and renders
the backend's decisions and metrics.

## 4. Backend Startup and Configuration

### 4.1 Application entry point

`VmAllocApplication` is the Spring Boot entry point. It:

- Starts the embedded Spring application.
- Enables component scanning through `@SpringBootApplication`.
- Enables `@ConfigurationPropertiesScan`, which binds `sim.*` properties into
  `SimProperties`.

### 4.2 HTTP and scheduling configuration

`CorsConfig` allows browser clients running on the common Vite/React development
origins:

- `http://localhost:5173`
- `http://localhost:3000`

It permits the HTTP methods and headers required by REST calls and SSE setup.

`AsyncConfig` provides two related facilities:

- Spring scheduling for live-session heartbeats and cleanup.
- A `ThreadPoolTaskExecutor` named `simExecutor`.

The simulation executor has:

- Core pool size: 2.
- Maximum pool size: 4.
- Queue capacity: 10.
- Thread prefix: `sim-`.

Each live simulation occupies an executor thread while it is paced against wall
clock time.

### 4.3 `SimProperties`

`SimProperties` is bound to the `sim` configuration prefix. It groups settings
into:

- `Hosts`
- `Spike`
- `Migration`

The important defaults in `application.properties` are:

```text
sim.policy=ZONED
sim.host.count=10
sim.host.performance.count=3
sim.host.performance.pes=4
sim.host.performance.mips=5000
sim.host.eco.pes=4
sim.host.eco.mips=1000
sim.host.idlePolicy=POWER_OFF
sim.migration.enabled=true
sim.migration.cpuThresholdPercent=70
sim.migration.ramThresholdPercent=85
sim.migration.mipsThreshold=3000
```

Host capacity is calculated as:

```text
host capacity in MIPS = number of PEs × MIPS per PE
```

With the defaults:

- Each Performance host has `4 × 5000 = 20,000 MIPS`.
- Each Eco host has `4 × 1000 = 4,000 MIPS`.
- The first three hosts are Performance hosts.
- The remaining seven hosts are Eco hosts.

The host list order is important because first-fit selection uses that order.

## 5. Backend Domain Models

The records in `com.vmalloc.model` are intentionally simple transport and
simulation data objects.

### 5.1 VM input models

`VmRequest` is the validated request for one VM:

| Field | Meaning |
|---|---|
| `vmId` | User-supplied identifier |
| `minCpu` | Minimum historical CPU percentage |
| `maxCpu` | Maximum historical CPU percentage |
| `avgCpu` | Average historical CPU percentage |
| `mips` | MIPS value per core used to calculate VM capacity |
| `numCores` | Number of requested VM cores |
| `ram` | RAM in MB |
| `bandwidth` | Bandwidth in Mbps |
| `duration` | Simulated lifetime in seconds |

Validation prevents blank IDs, non-positive resources, invalid CPU percentages,
and invalid core counts.

`LiveSimulationRequest` wraps a non-empty VM list and optional live controls:

- `timeScale`: simulated seconds per real second.
- `arrivalGapSeconds`: simulated seconds between VM arrivals.

Defaults are 5 for both values when omitted.

### 5.2 Classification and placement models

`VmType` has two values: `SPIKY` and `STABLE`.

`ClassificationResult` contains:

- VM ID.
- Assigned type.
- Confidence score.

`Zone` has two values:

- `ECO`
- `PERFORMANCE`

`VmPlacementResult` is the final per-VM result. It includes:

- Initial host and zone.
- Initial placement reason.
- VM classification and confidence.
- Average and peak demand in MIPS.
- Completed migration count.
- Allocation success.

The host and zone in this object describe **initial placement**, not necessarily
the VM's final host after migrations.

### 5.3 Final result models

`SimulationResult` contains:

```text
placements
hostMetrics
migrations
summary
```

`HostMetric` reports whole-run statistics for one host:

- Host identity and zone.
- Whether it was active.
- Capacity in MIPS.
- Time-averaged CPU utilization.
- Maximum observed load.
- Distinct total, spiky, and stable VMs seen.
- Active seconds.
- Overload seconds.
- Incoming and outgoing migration counts.
- Energy consumption in kWh.

`SimulationSummary` aggregates:

- Total, spiky, and stable VM counts.
- Hosts used by zone.
- Failed allocations.
- Migration and spike counts.
- Total overloaded host-seconds.
- Mean utilization across active hosts.
- Mean active host time.
- Total energy.

## 6. HTTP API

### 6.1 Batch health check

```http
GET /api/simulation/ping
```

Response:

```json
{
  "status": "ok",
  "service": "vm-placement-backend"
}
```

The frontend uses this when it mounts to show whether the backend is reachable.

### 6.2 Batch simulation

```http
POST /api/simulation/run
Content-Type: application/json
```

Body:

```json
[
  {
    "vmId": "vm-001",
    "minCpu": 5,
    "maxCpu": 90,
    "avgCpu": 25,
    "mips": 1000,
    "numCores": 4,
    "ram": 8192,
    "bandwidth": 1000,
    "duration": 3600
  }
]
```

`VmController.runSimulation`:

1. Validates the request and every `VmRequest`.
2. Rejects a null or empty list with HTTP 400.
3. Calls `VmPlacementService.runBatch`.
4. Returns the resulting `SimulationResult`.

### 6.3 Starting a live simulation

```http
POST /api/simulation/sessions
Content-Type: application/json
```

Body:

```json
{
  "vms": [
    {
      "vmId": "vm-001",
      "minCpu": 5,
      "maxCpu": 90,
      "avgCpu": 25,
      "mips": 1000,
      "numCores": 4,
      "ram": 8192,
      "bandwidth": 1000,
      "duration": 3600
    }
  ],
  "timeScale": 5,
  "arrivalGapSeconds": 5
}
```

`LiveSimulationController.start` returns HTTP 201:

```json
{
  "sessionId": "..."
}
```

The actual simulation runs asynchronously.

### 6.4 Live SSE stream

```http
GET /api/simulation/sessions/{id}/stream
Accept: text/event-stream
```

The stream contains named events:

| Event | Meaning |
|---|---|
| `init` | Initial topology before VM arrivals |
| `placement` | A VM was successfully booked on a host |
| `placement_failed` | A placement attempt failed |
| `spike` | A spiky VM entered or left a simulated spike |
| `migration` | A live migration completed |
| `host_update` | Periodic current state for all hosts |
| `completed` | Simulation ended normally or was stopped |
| `failed` | Simulation failed |

Each event has an increasing SSE event ID. The browser can reconnect with
`Last-Event-ID`, and the session replays events after that ID.

### 6.5 Session status and stop

```http
GET  /api/simulation/sessions/{id}
POST /api/simulation/sessions/{id}/stop
```

The status endpoint returns `SessionInfo`, containing:

- Session ID.
- `RUNNING`, `COMPLETED`, `STOPPED`, or `FAILED`.
- Final result when available.
- Error text when failed.

The stop endpoint sets a cooperative stop flag. The simulation checks this flag
between live simulation steps and terminates cleanly.

## 7. Batch Backend Flow

The complete batch path is:

```text
React form
  → api.runSimulation
  → POST /api/simulation/run
  → VmController
  → VmPlacementService.runBatch
  → classification map
  → CloudSimRunner.run
  → CloudSimRunner.prepare
  → CloudSim simulation.start()
  → buildResult
  → SimulationResult JSON
  → React result state
```

### 7.1 `VmPlacementService`

`VmPlacementService` is the batch orchestration service. It:

1. Logs the request size.
2. Obtains one classification result per VM.
3. Logs the number of spiky and stable VMs.
4. Passes requests and classifications to `CloudSimRunner`.
5. Logs the final host and allocation summary.
6. Returns the final result.

### 7.2 Simulation preparation

`CloudSimRunner.prepare` constructs the complete simulation before it starts.

#### Step A: Create CloudSim

A `CloudSimPlus` instance is created.

#### Step B: Create the policy

The `sim.policy` setting chooses:

- `ZONED` → `ZonedVmAllocationPolicy`.
- `MIDSEM` → `MidsemVmAllocationPolicy`.

The default is `ZONED`.

#### Step C: Build hosts

`buildHosts` creates `HostSimple` objects:

1. Host IDs are assigned by CloudSim in insertion order.
2. The first `performanceCount()` hosts use the Performance specification.
3. The remaining hosts use the Eco specification.
4. Each host receives:
   - RAM.
   - Bandwidth.
   - Storage.
   - `PeSimple` processing elements.
   - `VmSchedulerTimeShared`.
   - `PowerModelHostSimple`.
5. The policy records the host's zone.

#### Step D: Configure the datacenter

The datacenter:

- Uses the host list and selected policy.
- Schedules work every simulated second.
- Receives the migration bandwidth percentage.

#### Step E: Create the broker

`DatacenterBrokerSimple` submits VMs and cloudlets. A VM is destroyed one second
after its cloudlet completes, so resources are released near the VM's configured
duration.

#### Step F: Build CloudSim VMs

For each request:

1. The classification is read.
2. Spiky VMs receive a 1-based spike number.
3. A `VmProfile` is created.
4. VM demand is calculated from average CPU demand:

   ```text
   capacityMips = mips × numCores
   avgDemandMips = capacityMips × avgCpu / 100
   peakDemandMips = capacityMips × maxCpu / 100
   ```

5. CloudSim VM PE count and MIPS per PE are derived from the smallest host PE
   grid, so the VM is representable on every host type.
6. RAM, bandwidth, storage, and a time-shared scheduler are assigned.
7. The request ID is stored as the CloudSim VM description.
8. The VM and profile are registered with the policy.
9. Submission delay is set for live arrivals.
10. A cloudlet is created with a length that makes its runtime approximately equal
    to the requested duration.

The actual spike demand is not changed in the CloudSim VM's fixed capacity.
Instead, the project models dynamic CPU/RAM demand through `SpikeSchedule` and
the `HostLedger`, which allows migration decisions and monitoring to use the
simulated load.

#### Step G: Install monitoring and migration

The runner creates:

- `SpikeMigrationController`.
- `HostMonitor`.

On every CloudSim clock tick:

1. The monitor integrates the previous interval.
2. The migration controller updates current VM demands and may request migrations.
3. The monitor samples the new state.
4. A guard timeout prevents a stalled simulation from running forever.

#### Step H: Submit workload

The broker receives the VM list and cloudlet list. The prepared simulation is
returned to the caller.

### 7.3 Running and building the result

For batch mode, `CloudSimRunner.run` starts the prepared simulation synchronously.
After it finishes, `buildResult` creates:

- One final placement row for each requested VM.
- One host metric row for every host, including unused hosts.
- The list of completed migration events.
- Aggregate summary statistics.

## 8. Allocation Policies

### 8.1 Shared `TrackedAllocationPolicy`

`TrackedAllocationPolicy` extends CloudSim Plus's abstract allocation policy and
centralizes state shared by both policies.

It owns:

- `HostLedger`.
- VM → `VmProfile`.
- Host → `Zone`.
- VM → pending selection reason.
- VM → currently booked host.
- VM → initial placement.
- Placement listener.

#### Selection lifecycle

The policy deliberately separates selection from booking:

1. `defaultFindHostForVm` calls the policy-specific `selectHost`.
2. Selection only returns a host; it does not update accounting.
3. CloudSim evaluates and accepts the host.
4. `allocateHostForVm` calls the parent implementation.
5. Only after a fully suitable allocation does the ledger book the VM.
6. A first successful booking creates the permanent initial placement record.
7. A migration booking updates the ledger but does not replace the initial
   placement record.
8. `deallocateHostForVm` releases the VM from the ledger.

This prevents the policy from counting hosts that CloudSim rejected and ensures
that migration accounting follows the VM.

#### Candidate filtering

The shared `candidates` helper only returns hosts that:

- Have enough ledger-tracked RAM and bandwidth.
- Pass CloudSim's own `isSuitableForVm` check.
- Optionally belong to the requested zone.

CPU capacity is then checked by the specific policy rule.

### 8.2 `ZonedVmAllocationPolicy`: initial placement

#### Spiky VMs

For a spiky VM, the policy:

1. Considers Eco hosts only.
2. Rejects any host already containing a spiky VM.
3. Requires average demand to fit:

   ```text
   current average host MIPS + VM average demand
       ≤ host total MIPS capacity
   ```

4. Chooses the first remaining candidate in host-list order.
5. Records reason `ECO_SPIKY_FIRST_FIT`.

This gives the initial isolation guarantee:

```text
at most one spiky VM per Eco host
```

Stable VMs may already be present on the same host because the isolation rule is
specifically about the number of spiky VMs.

#### Stable VMs

For a stable VM, the policy:

1. Considers Eco hosts only.
2. Requires average demand to fit.
3. Chooses the candidate with the least remaining CPU headroom.
4. Records reason `ECO_BEST_FIT`.

This consolidates stable workloads instead of spreading them unnecessarily.
Stable VMs are not limited to one VM per host; they are packed until CPU, RAM,
bandwidth, or CloudSim suitability prevents another placement.

### 8.3 Performance migration target

`findPerformanceHost` is used only for a spiky VM moving from Eco to Performance.

The method:

1. Filters to Performance hosts.
2. Requires RAM/BW and CloudSim suitability.
3. Requires current host demand plus reserved migration demand to fit the
   requested spike demand.
4. Collects suitable hosts in host-list order.
5. First chooses a host with:
   - No booked VMs.
   - No reserved in-flight migration demand.
6. If no such host exists, chooses the first suitable host.

This implements:

```text
empty-host-first first-fit
```

It intentionally does **not** use best-fit. Concurrent spiky VMs are therefore
spread across empty Performance hosts before any suitable host is reused.

Reservations matter because a migration has a period between request and
completion. Without reservations, two migrations in the same simulation tick
could both select the same apparently empty host.

### 8.4 Eco migration target

`findEcoHost` is used after a spike ends. It:

1. Considers Eco hosts only.
2. Rejects hosts already containing a spiky VM.
3. Checks average-demand CPU room plus reserved migration demand.
4. Gives the original home host priority.
5. Falls back to the first qualifying Eco host.

The original host is stored in the initial `Placement` record and passed into
the migration controller as the preferred home.

### 8.5 `MidsemVmAllocationPolicy`

This is an ablation baseline:

- It does not distinguish zones.
- It does not migrate VMs.
- It applies one-spiky-per-host isolation globally.
- Stable VMs use best-fit.
- Spiky VMs use first-fit.

It is selected with `sim.policy=MIDSEM` and is useful for comparison against the
zoned policy.

## 9. Host Ledger and Resource Accounting

`HostLedger` is the policy's internal resource ledger. It tracks:

- VM identity on each host.
- Sum of average MIPS.
- RAM used.
- Bandwidth used.
- Spiky VM count.
- Current dynamic demand for each VM.

### 9.1 Booking

When CloudSim confirms a VM:

```text
avgMips += vm.avgDemandMips()
ram += vm.ram()
bw += vm.bw()
spikyVms += 1 when the VM is spiky
currentDemand = average demand initially
```

The ledger uses identity-based VM/profile collections so distinct objects are
not accidentally merged by value equality.

### 9.2 Current demand

`setCurrentDemand` updates a profile's current MIPS. During a spike, this becomes
peak demand. The host's current demand is the sum of current demands for all
booked profiles.

```text
currentLoad = currentMips / hostCapacityMips
```

Loads above `1.0` are retained for overload reporting even though displayed
utilization is capped at 100%.

### 9.3 Capacity checks

`fitsCpu` permits a small floating-point epsilon:

```text
currentMips + extraMips ≤ capacity + epsilon
```

RAM and bandwidth are checked independently against host capacity.

## 10. Spike Simulation

`SpikeSchedule` calculates one VM's load at a simulated time.

Stable behavior:

```text
CPU = avgCpu
RAM = ramBaselinePercent
```

Spiky behavior inside the active window:

```text
CPU = maxCpu
RAM = ramSpikePercent
```

Demand is always calculated as:

```text
demandMips = capacityMips × currentCpuPercent / 100
```

Spiky VMs are numbered in submission order among spiky VMs:

- VM 1, 3, 5, ... use the odd spike window.
- VM 2, 4, 6, ... use the even spike window.

With the default configuration:

- Odd spiky VMs start after 15 seconds and spike for 5 seconds.
- Even spiky VMs start after 20 seconds and spike for 5 seconds.
- `repeat=true` repeats the windows for the VM lifetime.

The elapsed time is measured from the VM's initial placement time, which is
important for live simulations where VMs arrive at different times.

## 11. Migration Controller

`SpikeMigrationController` runs on the simulation thread once per clock tick.

For every running VM with a recorded placement:

1. It calculates the current `VmLoad`.
2. It updates the ledger's current demand.
3. It detects transitions into and out of spike windows.
4. It emits a `SpikeEvent` for a transition.
5. For spiky VMs, it checks whether a migration is needed.

### 11.1 Threshold evaluation

The configured thresholds are:

- CPU percentage.
- RAM percentage.
- MIPS demand.

Thresholds with value `0` are disabled. A migration trigger is produced when
**any** enabled threshold is crossed using a strict greater-than comparison:

```text
cpuPercent > cpuThreshold
ramPercent > ramThreshold
demandMips > mipsThreshold
```

The event detail contains all thresholds crossed at that instant.

### 11.2 Eco to Performance

When a spiky VM is on an Eco host and any threshold is crossed:

1. `findPerformanceHost` searches for a target.
2. If one exists, the controller requests a live migration.
3. The target demand is reserved while the migration is in flight.
4. If no target exists, the VM stays on Eco and a warning is logged once for
   that condition.

### 11.3 Performance to Eco

When a spiky VM is on a Performance host and no threshold is crossed:

1. `findEcoHost` searches for a host with average-demand room.
2. The original host is preferred.
3. A migration back to Eco is requested if a target exists.

### 11.4 In-flight migration handling

The controller stores an `InFlight` record for each migrating VM. It contains:

- Source host.
- Target host.
- Trigger and detail.
- Load at request time.
- Request time.
- Reserved MIPS.

The same VM is not evaluated for another migration while its current migration
is in flight.

CloudSim's migration-finished callback removes the in-flight record, increments
the migration count, creates a `MigrationEvent`, and notifies the listener.

## 12. Monitoring, Energy, and Overload Metrics

`HostMonitor` samples each host from the ledger and integrates the interval
between samples.

For every host it tracks:

- Joules.
- Active seconds.
- Utilization-seconds.
- Overload seconds.
- Maximum load.
- Distinct VM profiles observed.

At each tick, the runner calls:

```text
monitor.integrate(now)
controller.onTick(now)
monitor.sample()
```

This ordering charges the elapsed interval using the previous state, then
captures the new state for the next interval.

### 12.1 Power

For a host with load `u`, the CloudSim power model calculates watts. Under
`POWER_OFF`, a host with no VMs draws zero watts. Under `KEEP_IDLE`, an empty
host draws idle power.

Energy conversion is:

```text
kWh = joules / 3,600,000
```

### 12.2 Utilization

Utilization is capped at 1.0 for utilization and power calculations, but the
uncapped load is kept for overload detection and maximum-load reporting.

```text
displayed/utilization value = min(load, 1.0)
overload occurs when load > 1.0
```

## 13. Live Session Flow

### 13.1 Starting

`LiveSimulationService.start`:

1. Reads the VM list.
2. Gets classifications.
3. Builds live options with defaults.
4. Creates a `SimulationSession`.
5. Submits a background task to `simExecutor`.
6. Returns the session immediately.

If the executor rejects the task, the session is removed and HTTP 503 is
returned.

### 13.2 Running

The background task calls `CloudSimRunner.runLive`. It passes:

- VM requests.
- Classification map.
- Live options.
- A `SimulationObserver` that publishes to the session.
- A stop predicate backed by the session's atomic stop flag.

The live runner:

1. Prepares the same CloudSim model as batch mode.
2. Installs a placement listener.
3. Publishes initial host snapshots.
4. Starts the simulation synchronously.
5. Advances one simulated second at a time.
6. Sleeps according to `timeScale`.
7. Publishes host updates at a throttled wall-clock interval.
8. Stops when the simulation completes or the user requests stop.

### 13.3 Event publishing

`LiveSimulationService.publisherFor` maps simulation callbacks to named session
events:

| Simulation callback | SSE event |
|---|---|
| `onInit` | `init` |
| `onPlacement` | `placement` |
| `onPlacementFailed` | `placement_failed` |
| `onHostUpdate` | `host_update` |
| `onSpike` | `spike` |
| `onMigration` | `migration` |

When the run ends, the service publishes either `completed` or `failed`.

### 13.4 `SimulationSession`

A session stores:

- Session ID.
- Creation and completion timestamps.
- Current status.
- Final result or error.
- All published events.
- Currently attached SSE emitters.
- Atomic stop request flag.

All event publication and attachment operations are synchronized. This preserves
event ordering and makes replay consistent.

If a browser connects late, all events after its supplied `Last-Event-ID` are
replayed before it begins receiving new events.

### 13.5 Session registry

`SimulationSessionRegistry` stores sessions in memory:

- `create` adds a UUID-keyed session.
- `get` looks up a session.
- `remove` deletes a session.
- A scheduled heartbeat keeps active SSE connections alive.
- A scheduled cleanup removes finished sessions older than ten minutes.

Because this state is in memory, sessions are local to one backend process and
are not durable across process restarts.

## 14. Frontend Architecture

The frontend is a React 18 application built with Vite.

### 14.1 Entry points

`main.jsx`:

1. Imports global CSS.
2. Gets the `root` DOM element.
3. Renders `<App />` inside `StrictMode`.

`vite.config.js`:

- Enables the React plugin.
- Proxies `/api` to `http://localhost:8080`.
- This proxy covers both REST calls and the SSE stream.

### 14.2 `App.jsx`

`App` is the page-level coordinator. Its state includes:

- `result`: final batch or completed-live result.
- `loading`: request startup state.
- `error`: visible error text.
- `backendOnline`: ping result.
- `sessionId`: active live-session ID.
- `stopping`: stop-request state.

On mount, it calls `pingBackend`.

When the form is submitted:

- Batch mode calls `runSimulation`.
- Live mode calls `startLiveSimulation` and stores the returned session ID.

The app uses `useSimulationStream(sessionId)` for live state. Once the stream
provides a final result, `App` switches to the same result-oriented view used by
batch mode.

The displayed `view` is selected as follows:

1. Final result, if present.
2. Current live stream state while a live session is running.
3. No result before a simulation starts.

The app derives a VM ID → VM type map for the live datacenter visualization.

### 14.3 `VMSubmissionForm`

The form lets a user:

- Add and remove VM rows.
- Edit VM ID.
- Edit CPU history values.
- Edit MIPS, cores, RAM, bandwidth, and duration.
- Select Instant or Live mode.
- Set live speed and arrival gap.
- Submit the workload.

The component maintains the list locally. Numeric inputs are converted to
numbers before being passed upward.

Default row values are:

```text
minCpu: 15
maxCpu: 30
avgCpu: 25
mips: 1000
numCores: 4
ram: 8192
bandwidth: 1000
duration: 3600
```

VM IDs are generated as `vm-001`, `vm-002`, and so on for newly added rows.

### 14.4 Frontend API module

`services/api.js` creates an Axios client with:

```text
baseURL = /api
Content-Type = application/json
```

It exposes:

- `runSimulation(vms)`.
- `startLiveSimulation(vms, options)`.
- `stopLiveSimulation(sessionId)`.
- `sessionStreamUrl(sessionId)`.
- `pingBackend()`.

Keeping the base URL relative lets Vite proxy requests in development and
allows a production deployment to serve the frontend and API under one origin.

### 14.5 `useSimulationStream`

This hook owns the browser-side live-session state:

```text
status
hosts
placements
migrations
spiking
location
simTime
result
error
```

When a session ID appears:

1. The reducer resets to `connecting`.
2. An `EventSource` is created.
3. Listeners are attached for every supported event.
4. JSON event payloads are dispatched into the reducer.
5. The stream closes after `completed` or `failed`.

The browser's EventSource automatically reconnects after transient failures.
The backend's event IDs and replay behavior make reconnects safe.

Reducer behavior:

- `init`: sets the topology and changes status to running.
- `placement`: inserts or updates a VM row, updates its host, and updates the
  corresponding host snapshot.
- `placement_failed`: adds a failed row unless a successful row already exists.
- `spike`: updates the VM's current spiking flag.
- `migration`: appends the migration, updates the current VM location, and
  increments the row's migration count.
- `host_update`: replaces the full host snapshot list.
- `completed`: stores final status and result.
- `failed`: stores the error.

### 14.6 Dashboard components

#### `LiveStatus`

Shows live counters:

- Simulation clock.
- VMs placed.
- Powered-on hosts.
- Performance host count.
- Current spikes.
- Current Performance-zone locations.
- Live migration count.

It also provides the Stop button.

#### `MetricsSummary`

Shows final batch/completed-run cards:

- Total VMs and type split.
- Hosts used by zone.
- Average CPU utilization.
- Migration and overload counts.
- Energy consumed.
- Average active host time.

#### `PlacementTable`

Displays one row per VM:

- VM ID.
- CloudSim ID.
- Type.
- Classification confidence.
- Initial zone.
- Initial host.
- Current location in live mode.
- Migration count.
- Allocation status.

The initial placement reason is available as a tooltip. Known reasons include:

- `ECO_SPIKY_FIRST_FIT`.
- `ECO_BEST_FIT`.
- `SPIKY_FIRST_FIT`.
- `STABLE_BEST_FIT`.

#### `DatacenterView`

Displays live host state grouped into:

- Performance Zone.
- Eco Zone.

Each host shows:

- Host name.
- Current load.
- Load bar.
- Current VM chips.

VM chips use type colors and pulse while the VM is spiking. Completed
migrations create temporary SVG arrows between host cards:

- Orange arrow: movement to Performance.
- Green arrow: movement back to Eco.

#### `HostMetricsChart`

Uses Recharts for:

1. CPU utilization per host.
2. Spiky/stable VM composition per host.

Hosts are colored by zone. It displays:

- Current or time-averaged utilization.
- Peak load.
- Overload seconds.
- VM counts.
- Migration counts.
- Energy.

The CPU chart includes a 90% visual reference line.

#### `MigrationLog`

Lists migrations newest first, including:

- Requested time.
- VM ID.
- Source host.
- Target host.
- Trigger and detail.
- Migration duration.

The view caps rendering at the latest 200 migrations.

### 14.7 Frontend styling

`index.css` defines the dashboard design system:

- Dark glassmorphism cards.
- Indigo primary actions.
- Orange spiky indicators.
- Cyan stable indicators.
- Green success states.
- Red failures and overload states.
- Responsive grid breakpoints.
- Host load bars, badges, tables, forms, migration animations, and datacenter
  layout.

`App.css` contains the initial Vite styles and is not the primary dashboard
layout stylesheet; `App.jsx` imports `index.css` for the active design system.

## 15. End-to-End Batch Example

Assume a request contains:

```text
vm-001: avgCpu=25%, maxCpu=90%, mips=1000, cores=4
```

The processing is:

1. `capacityMips = 1000 × 4 = 4000`.
2. `avgDemandMips = 4000 × 25% = 1000`.
3. `peakDemandMips = 4000 × 90% = 3600`.
4. The classifier marks it `SPIKY`.
5. `ZonedVmAllocationPolicy` searches Eco hosts.
6. It chooses the first Eco host with:
   - No existing spiky VM.
   - At least 1000 average MIPS available.
   - RAM, bandwidth, and CloudSim suitability.
7. The ledger books 1000 average MIPS.
8. At the odd spike time, the schedule changes current demand to 3600 MIPS.
9. CPU, RAM, or MIPS thresholds may be crossed.
10. The controller searches Performance hosts:
    - An empty suitable host is preferred.
    - Otherwise the first suitable occupied host is selected.
11. CloudSim migrates the VM.
12. When the spike ends, demand returns to 1000 MIPS.
13. The controller chooses the original Eco host if it still qualifies.
14. The final placement row still reports the original Eco placement, while the
    migration list records the round trip.

## 16. End-to-End Live Example

For live mode:

1. The browser posts the VM list and live options.
2. The backend returns a session ID immediately.
3. The browser opens the SSE URL.
4. The backend emits `init` with all hosts off/empty.
5. CloudSim advances according to the configured arrival gap.
6. Each successful placement emits `placement`.
7. Each failed attempt emits `placement_failed`.
8. Each host-update interval emits `host_update`.
9. Spike transitions emit `spike`.
10. Completed migrations emit `migration`.
11. The dashboard reducer updates the datacenter, charts, locations, and logs.
12. The user may request stop.
13. The simulation terminates cooperatively and emits `completed` with
    `STOPPED` status.

## 17. Failure and Edge-Case Behavior

### Invalid input

Spring validation rejects invalid request bodies. The frontend displays the
backend error message when available.

### No suitable host

If no host satisfies resource and suitability checks, CloudSim can report a
placement failure. In live mode, a failure event may be followed by a later
successful placement if the broker retries after resources become available.

### No Performance target

If a spiky VM crosses a threshold but no Performance host has enough current
capacity, it remains on its current Eco host and a warning is logged once.

### Migration failure or completion

Migration counts are incremented only when CloudSim reports completion. A
requested migration that does not complete is not reported as completed.

### Empty hosts

Under `POWER_OFF`, unused hosts remain inactive and consume no modeled power.
They are still included in host snapshots and final metrics.

### Live stream reconnect

The browser EventSource reconnects automatically. The backend replays events
after `Last-Event-ID`, preventing missed placement or migration updates.

### Session cleanup

Completed sessions remain queryable for ten minutes, after which the in-memory
registry removes them.

## 18. Tests and Validation Areas

The backend contains:

- `HostLedgerTest`: current-demand, release, and RAM/bandwidth accounting.
- `CloudSimRunnerTest`: zoned placement, spiky isolation, migration timing,
  repeated spikes, disabled migration, energy, and host reporting.
- `SimPropertiesBindingTest`: configuration binding.

The CloudSim scenario tests are tagged as `validation` and excluded from the
default Maven test run by configuration. They can be run with:

```bash
cd backend
mvn test -Dtest.excludedGroups= -Dgroups=validation
```

Frontend package scripts include:

```bash
npm run dev
npm run build
npm run lint
npm run preview
```

## 19. Running the Application

### Backend

```bash
cd backend
mvn spring-boot:run
```

The backend listens on port 8080.

### Frontend

```bash
cd frontend
npm install
npm run dev
```

The Vite development server listens on port 5173 and proxies `/api` to the
backend.

## 20. Important Design Invariants

The following invariants explain the intended behavior of the implemented
system:

1. **Initial placement is Eco-only under the zoned policy.**
2. **Initial spiky placement is isolated per Eco host.**
3. **Stable VMs may share Eco hosts until resource limits are reached.**
4. **Stable placement is best-fit; spiky placement is first-fit.**
5. **Performance migration is empty-host-first first-fit, not best-fit.**
6. **In-flight Performance reservations prevent concurrent over-admission.**
7. **The initial placement record is not overwritten by migrations.**
8. **The ledger is booked only after CloudSim confirms allocation.**
9. **Current demand can exceed average demand during simulated spikes.**
10. **Overload is measurable even when displayed utilization is capped at 100%.**
11. **Batch and live modes use the same CloudSim and allocation logic.**
12. **The frontend renders backend state; it does not duplicate placement policy.**

## 21. Source Reference Map

### Backend

- Application startup: `backend/src/main/java/com/vmalloc/VmAllocApplication.java`
- Batch endpoint: `backend/src/main/java/com/vmalloc/controller/VmController.java`
- Live endpoints: `backend/src/main/java/com/vmalloc/controller/LiveSimulationController.java`
- Batch orchestration: `backend/src/main/java/com/vmalloc/service/VmPlacementService.java`
- Live orchestration: `backend/src/main/java/com/vmalloc/live/LiveSimulationService.java`
- Session state and replay: `backend/src/main/java/com/vmalloc/live/SimulationSession.java`
- Session registry: `backend/src/main/java/com/vmalloc/live/SimulationSessionRegistry.java`
- Simulation construction: `backend/src/main/java/com/vmalloc/simulation/CloudSimRunner.java`
- Shared policy state: `backend/src/main/java/com/vmalloc/simulation/TrackedAllocationPolicy.java`
- Zoned allocation: `backend/src/main/java/com/vmalloc/simulation/ZonedVmAllocationPolicy.java`
- Baseline allocation: `backend/src/main/java/com/vmalloc/simulation/MidsemVmAllocationPolicy.java`
- Resource ledger: `backend/src/main/java/com/vmalloc/simulation/HostLedger.java`
- Spike schedule: `backend/src/main/java/com/vmalloc/simulation/SpikeSchedule.java`
- Migration controller: `backend/src/main/java/com/vmalloc/simulation/SpikeMigrationController.java`
- Monitoring and energy: `backend/src/main/java/com/vmalloc/simulation/HostMonitor.java`
- Configuration: `backend/src/main/java/com/vmalloc/config/SimProperties.java`

### Frontend

- Page coordinator: `frontend/src/App.jsx`
- Form: `frontend/src/components/VMSubmissionForm.jsx`
- REST API: `frontend/src/services/api.js`
- SSE state hook: `frontend/src/hooks/useSimulationStream.js`
- Live datacenter: `frontend/src/components/DatacenterView.jsx`
- Live status: `frontend/src/components/LiveStatus.jsx`
- Final summary: `frontend/src/components/MetricsSummary.jsx`
- Placement results: `frontend/src/components/PlacementTable.jsx`
- Host charts: `frontend/src/components/HostMetricsChart.jsx`
- Migration log: `frontend/src/components/MigrationLog.jsx`
- Global styling: `frontend/src/index.css`
- Vite proxy: `frontend/vite.config.js`

