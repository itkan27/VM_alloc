# Future Work — Implementation Plan

> Efficient VM Placement in Cloud Environments (IT418 project, endsem phase)
>
> Built from: `README.md`, the full backend/frontend/ml-service code, the midsem
> report, and **running the current backend** against the report's test cases plus
> edge cases (results in §1.2). CloudSim Plus 8.0.0 APIs named below were checked
> against the actual jar.

## Status (updated)

| Phase | Status |
|---|---|
| 0 — Groundwork | ✅ Done. VM sizing fix, confirmed-only ledger booking/release, policy base class, all hosts in host order, measured host time in seconds |
| 1 — Performance & Eco zones | ✅ Done. Zoned policy with peak-safe admission and overflow rules, power models on all hosts, energy card, zone colours, Zone column |
| 2 — Real-time dashboard | ✅ Done. Live sessions on a background executor, SSE stream with replay (`Last-Event-ID`), Instant / Live modes, animated rows |
| 3 — Live placement & migration | ⏳ Next |
| 4 — Comparison with base paper | ⏳ Next |
| Testing & validation | Deferred to the end. The slow randomized suite is tagged `validation` and excluded from `mvn test` |

Notes from implementation:
* CloudSim truncates VM MIPS to whole numbers; a cloudlet sized from fractional MIPS never completes. VM MIPS per PE is now rounded down.
* Finished VMs are destroyed 1 s after their cloudlet ends (`setVmDestructionDelay`), so capacity and power are released on time.
* A stall guard terminates any run that passes `2 × last expected event + 60 s`. One random validation batch still runs far longer than expected; investigate during the testing phase.
* The plan's `/api/simulate/*` paths were implemented as `/api/simulation/sessions/*` (§2).

---

## 0. TL;DR

1. **Do a Phase 0 first.** Running the code shows a placement bug that would make
   the Phase 1 and Phase 4 numbers (hosts used, energy, utilisation) wrong. There is
   also no SLA/peak model and no tests yet. Fix these first (~2–3 days).
2. **Order: 0 → 1 → 4 (batch) → 2 → 3 → 4 (dynamic metrics).** The comparison with
   the base paper is the core endsem result and only needs Phases 0 and 1. Phases 2
   and 3 share one async "session engine", so build them back to back.
3. Six changes to the proposed plan are needed (§2). Most important:
   * a peak-aware admission rule in the Performance Zone (otherwise the 4th spiky VM
     fails with 3 perf hosts)
   * power models on **both** zones
   * dropping the `CloudletSchedulerSpaceShared` step
   * using a Round-Robin baseline **in addition to**, not instead of, the base
     paper's algorithm

---

## 1. Where the project stands

### 1.1 Features implemented today (README + code + report)

| Area | Implemented | Where |
|---|---|---|
| Batch API | `POST /api/simulation/run`, `GET /api/simulation/ping` | `controller/VmController.java` |
| Classification | Heuristic: `range > 40 && avg < 0.6·max` → SPIKY. The ML call is commented out | `service/VmClassifierClient.java:45-57` |
| ML service | FastAPI + ONNX. Falls back to a heuristic stub with no model. Not called by the backend right now | `ml-service/` |
| Datacenter | 10 identical hosts, 4 PE × 1000 MIPS, 32 GB, 10 Gbps | `application.properties`, `CloudSimRunner.buildHosts()` |
| VM sizing | Every VM created with **1 PE @ `req.mips`**. Real demand = `avgCpu% × mips × cores` is registered with the policy | `CloudSimRunner.java:97,116` |
| Policy | SPIKY: first-fit, at most 1 spiky VM per host. STABLE: best-fit (least remaining CPU). Own CPU/RAM/BW ledger | `CustomVmAllocationPolicy.java` |
| Metrics | Per-VM placement, per-host util (from avg demand), spiky/stable counts, hosts used, failures, avg util, "avg host time" | `CloudSimRunner.buildResult()` |
| UI | Batch form, 4 metric cards, CPU-util and composition bar charts, classification breakdown, placement table | `frontend/src/**` |
| Tests | **None** (no `src/test`, no frontend tests) | — |

### 1.2 Current results, reproduced

I ran the backend jar with the form defaults (stable = 15/30/25 %, spiky = 5/90/25 %,
1000 MIPS × 4 cores, 8 GB, 3600 s):

| Case | Hosts used | Failed | Avg util | Matches report? |
|---|---|---|---|---|
| TC1 – 4 stable | 1 | 0 | 100 % | ✅ |
| TC2 – 2 stable + 2 spiky | 2 (Host-0 = 1 spiky + 2 stable) | 0 | 50 % | ✅ |
| TC3 – 4 spiky | 4 | 0 | 25 % | ✅ |
| E1 – 8 stable, avg 10 %, **2 GB RAM** | **2** (expected 1) | 0 | 40 % | ❌ bug, see F1 |
| E3 – 1 spiky + 3 stable | 1 host at 100 % avg | 0 | 100 % | ⚠ spike overloads host, see F2 |
| E4 – 11 spiky | 10 | 1 | 25 % | ✅ expected |
| E5 – durations 600 s and 3600 s | 1 | 0 | 50 % | ⚠ "Avg Host Time" = 3600, see F3 |

### 1.3 Findings that change the plan

**F1 — The policy ledger and CloudSim disagree (ghost capacity).** Each VM asks
CloudSim for 1 PE at `req.mips` (1000). `VmSchedulerTimeShared` therefore fits only
**4 such VMs per host**, whatever their real demand. Our policy books the VM in
`defaultFindHostForVm()` *before* CloudSim confirms. When CloudSim rejects the VM
("failed due to lack of PEs"), the booking is never released, and the broker's retry
books it again. Log from E1:

```
-> Host-0 | CPU 2000/4000 MIPS (50.0%)
ERROR CustomVmAllocationPolicy Creation of Vm 4 (vm-004) on Host 0 failed due to lack of PEs.
...
-> Host-0 | CPU 4000/4000 MIPS (100.0%)   ← ledger says full; only 4 VMs (1600 MIPS) actually there
```

Consequences:
* Consolidation is silently capped at 4 VMs per host, so "hosts used" and energy are
  inflated.
* VMs with `mips` > host PE MIPS can't be placed. This matters as soon as Eco hosts
  use 1000-MIPS PEs and Perf hosts 5000.
* The ledger never releases capacity when VMs finish. That's harmless in batch mode
  but breaks Phase 3.

**F2 — Isolation covers only spiky vs spiky; there is no peak headroom.** In E3 the
spiky VM shares Host-0 with 3 stable VMs at 100 % average load. When it spikes to
90 %, demand is 3600 + 3000 = 6600 MIPS on a 4000-MIPS host (165 %). This is exactly
the problem the Performance Zone solves. It is also the SLA metric Phase 4 needs, and
we don't measure it yet.

**F3 — "Avg Host Time" isn't measured.** It is the *maximum requested duration*
(`CloudSimRunner.java:230`), and the UI shows it in **"ms"** although it's seconds
(the report screenshots show "3600.0ms"). Every policy gets the same value, so it is
useless for comparison. Replace it (§6.3).

**F4 — Host order is random.** `hostToVms` is a `HashMap`, so the chart shows
"Host-2, Host-1, Host-3, Host-0" (visible in the TC3 screenshot). Idle hosts are
omitted, so zones won't be visible either.

**F5 — Plan text vs code.**
* The base path is `/api/simulation`, not `/api/simulate`.
* The EventSource snippet uses a relative URL, but `api.js` calls
  `http://localhost:8080` directly and there is no Vite proxy.
* `sim.host.mips` means **MIPS per PE**.

**F6 — The report's future work says something different from the plan.** The report
says "migrate spiky VMs **at their time of spike** to a high-performance zone". The
plan's Phase 1 places them statically, and Phase 3 migrates *stable* VMs. Do both
(§5.3, rule R1).

---

## 2. Changes to the proposed plan

| Plan item | Change | Why |
|---|---|---|
| P1 "spiky only on perf hosts" + 1-spiky-per-host | Admit spiky VMs to perf hosts by **Σ peak demand ≤ capacity**. Make `maxSpikyPerHost` configurable (default unlimited). Add overflow rules | 3 perf hosts × 1 spiky = 3. TC3 (4 spiky) would go from 0 to 1 failure. A perf host has 20 000 MIPS, so 5 default spiky VMs fit even if all spike together |
| P1 power model on Eco only | Power models on **all** hosts (perf hosts get a higher max/idle wattage) | Leaving perf hosts out flatters our energy number, and a reviewer will catch it |
| P2 fire events in `defaultFindHostForVm()` | Fire events on **confirmed** allocation (override `allocateHostForVm(Vm, Host)`) and on `vm.addOnCreationFailureListener` | Otherwise rejected VMs show as placed (F1) and broker retries show as repeated events |
| P2 single `GET /api/simulate/stream` | `POST /api/simulation/sessions` → id, then `GET /api/simulation/sessions/{id}/stream` | EventSource can't send a request body (the VM list) |
| P3 step 1 `CloudletSchedulerSpaceShared` | **Drop it** | TimeShared *is* the time-sliced scheduler. With 1 cloudlet per VM the choice makes no difference |
| P3 `POST /api/simulate/add-vm` | `POST /api/simulation/sessions/{id}/vms` | Must target a running session |
| P3 rebalancer "move least-demanding STABLE VM to host with most headroom" | Keep it as rule R2. Add R1 spike promotion (the report's idea), R3 overflow repatriation and optional R4 consolidation. Pick targets by best-fit with hysteresis | Worst-fit target undoes consolidation. Without hysteresis/cooldown VMs ping-pong |
| P4 Round-Robin = "base paper" | RR, First-Fit, pure aggregation and pure segregation as **generic baselines**, plus `BasePaperVmAllocationPolicy` implementing the base paper's actual heuristic | The report title compares against the base paper. RR alone is a strawman |
| P4 "Avg Host Time" metric | Replace with measured makespan / VM slowdown, plus SLA metrics (SLATAH, PDM, SLAV, ESV) and placement overhead | F3. Also supports the report's "reduce overhead" objective |
| Report says WebSocket | SSE is fine | Server → client only. Live VM injection uses normal POSTs |

---

## 3. Phase 0 — Groundwork (do first, ~2–3 days)

### 3.1 Fix VM sizing (F1)
In `CloudSimRunner`, size the CloudSim VM to the **demand the policy budgets**, so
CloudSim never rejects a VM the policy accepted:

```java
double demand   = req.mips() * req.numCores() * (req.avgCpu() / 100.0);   // MIPS
int    vmPes    = (int) Math.max(1, Math.ceil(demand / minHostPeMips));    // fit any host's PE grid
double mipsPerPe = demand / vmPes;
VmSimple vm = new VmSimple(mipsPerPe, vmPes);
CloudletSimple cl = new CloudletSimple((long) (mipsPerPe * req.duration()), vmPes);  // runtime == duration
```

Rule: **CloudSim's reservation ≤ the policy's budget.** Average demand satisfies this,
because the policy budgets avg for stable VMs and peak (≥ avg) for spiky VMs. A side
benefit: CloudSim's own host utilisation then equals what we report, so CloudSim's
`PowerMeter` gives matching energy numbers.

### 3.2 Book the ledger only on success, and release it
* `defaultFindHostForVm()` only **chooses** a host (pure, no side effects).
* Override `allocateHostForVm(Vm vm, Host host)`: call `super`, and **book** the
  ledger only when `suitability.fully()` is true.
* Override `deallocateHostForVm(Vm)` to **release** it. This is needed for VMs that
  finish (live mode) and for migrations.

### 3.3 Refactor the policy into a base class plus strategies

```
simulation/
  ledger/HostLedger.java              ← CPU(avg), CPU(peak), RAM, BW, spiky count per host; book/release/fits
  model/VmProfile.java                ← vmId, type, avgDemand, peakDemand, ram, bw, duration
  model/Zone.java                     ← PERFORMANCE, ECO
  policy/TrackedAllocationPolicy.java ← extends VmAllocationPolicyAbstract; owns ledger, zones, listener;
                                         abstract Optional<Host> selectHost(VmProfile, List<Host> candidates)
  policy/ZonedVmAllocationPolicy.java ← Phase 1 (our policy)
  policy/MidsemVmAllocationPolicy.java← today's logic, kept as an ablation baseline
  policy/baseline/…                   ← Phase 4 baselines
  PlacementListener.java              ← interface; NOOP by default (no Spring dependency inside the policy)
```

Every policy in Phase 4 then shares the same capacity checks, VM sizing and metrics.
Only the host-selection rule differs, which is what makes the comparison fair.
`VmProfile` replaces the two `IdentityHashMap`s; keep identity semantics, keyed by
`Vm`.

### 3.4 Correct the metrics
* `hostMetrics` covers **all** hosts (with an `active` flag), sorted by id (F4).
* Host active time comes from the actual cloudlet start/finish times
  (`cloudlet.getStartTime()/getFinishTime()`), labelled in **seconds**.
* Add `peakDemandMips` per VM: `maxCpu% × mips × cores`.
* Lower per-VM logs to DEBUG. Phase 4 runs hundreds of simulations.

### 3.5 Tests (the safety net for every later phase)
Add `backend/src/test/java/...`:
* `CloudSimRunnerTest`: TC1–TC3 and E1–E5 as golden tests. E1 must give 1 host after
  the fix.
* `HostLedgerTest`: book, release and fits.
* An invariant test across random seeds: no host's Σ avg demand > capacity, and the
  ledger equals CloudSim's actual VM list per host.

**Done when:** the E1 test is green, ledger == CloudSim for 100 random batches, and
the "Avg Host Time" card shows seconds.

---

## 4. Phase 1 — Performance & Eco zones (~4–5 days)

### 4.1 Configuration (`application.properties`)
```properties
sim.host.count=10
sim.host.performance.count=3
sim.host.performance.pes=4
sim.host.performance.mips=5000            # per PE → 20 000 MIPS/host
sim.host.performance.ram=65536
sim.host.performance.powerModel.maxWatts=400
sim.host.performance.powerModel.idleWatts=150
sim.host.eco.pes=4
sim.host.eco.mips=1000                    # per PE → 4 000 MIPS/host
sim.host.eco.powerModel.maxWatts=250
sim.host.eco.powerModel.idleWatts=80
sim.zone.performance.strategy=FIRST_FIT   # FIRST_FIT (pack) | WORST_FIT (spread)
sim.zone.performance.maxSpikyPerHost=0    # 0 = only the peak rule applies
sim.zone.allowSpikyOverflowToEco=true
sim.zone.allowStableOverflowToPerf=true
sim.host.idlePolicy=POWER_OFF             # POWER_OFF | KEEP_IDLE (to show the green-computing gain)
```
Bind these with a `@ConfigurationProperties("sim")` record (`SimProperties`), not
dozens of `@Value` fields. Keep the old `sim.host.mips` and `sim.host.pes` as fallbacks.

### 4.2 Building the zoned datacenter (`CloudSimRunner`)
* `buildHosts()` creates `performance.count` perf hosts, then eco hosts. Register a
  `Map<Host, Zone>` (by identity) with the policy, so nothing depends on how CloudSim
  assigns host ids.
* `host.setPowerModel(new PowerModelHostSimple(maxW, idleW))` on every host. The
  class exists in 8.0.0; its constructor takes `(maxPower, staticPower)`.
* `POWER_OFF`: never-used hosts count as off (0 W). Optionally use
  `host.setIdleShutdownDeadline(…)` so hosts switch off after their VMs finish.

### 4.3 Placement rules (`ZonedVmAllocationPolicy.selectHost`)

| VM | Step 1 (preferred) | Step 2 (overflow) | Step 3 |
|---|---|---|---|
| SPIKY | Perf host where `Σ spikyPeak + Σ stableAvg + newPeak ≤ cap`, plus RAM/BW. First-fit (or worst-fit) | Eco host with **no spiky VM** where `Σ avg + newPeak ≤ cap`. Mark `overflow=true` (Phase 3 repatriates it) | Fail |
| STABLE | Eco host, best-fit on avg demand (today's rule) | Perf host only if it doesn't break the peak guarantee above | Fail |

Record a `placementReason` for each VM: `PERF_FIRST_FIT`, `ECO_BEST_FIT`,
`SPIKY_OVERFLOW_ECO` or `STABLE_OVERFLOW_PERF`. It goes into the table tooltip and
helps when explaining results in the report.

Expected results with the defaults:
* TC3 (4 spiky): all on Perf Host-0 (4 × 3600 = 14 400 ≤ 20 000), so 1 host instead
  of 4.
* TC2: spiky VMs on a perf host, stable VMs on an eco host, so no spike-induced
  overload.

Update the golden tests to match.

### 4.4 Energy
Write one `EnergyAccountant` and use it in batch mode now and in live mode later:
* Register `simulation.addOnClockTickListener` (the datacenter scheduling interval is
  1 s).
* On each tick, for each **active** host: `E += powerModel.getPower(u) × Δt`. `u`
  comes from our load model: constant avg in batch mode, the utilisation trace in
  Phase 3.
* Report kWh: `E_J / 3.6e6`.
* Cross-check in batch mode against CloudSim's `PowerMeter(sim, hosts)`. After the
  Phase 0 fix they should agree within ~1 %. Add this as a test.

### 4.5 Model / API changes
* `HostMetric` gets `zone`, `active`, `capacityMips`, `peakDemandMips`, `energyKWh`.
* `VmPlacementResult` gets `zone`, `placementReason`, `cpuDemandMips`,
  `peakDemandMips`.
* `SimulationSummary` gets `perfHostsUsed`, `ecoHostsUsed`, `spikyOverflowCount`,
  `totalEnergyKWh`. It also gets `simulationTimeSeconds` → `avgHostActiveSeconds`
  (rename and fix units).

### 4.6 Frontend
* `HostMetricsChart`: colour each bar by zone instead of by utilisation threshold:
  Perf `#f97316` (orange), Eco `#22c55e` (green). Add a zone legend, a dashed
  `ReferenceLine` at 90 %, and a "peak if all spike" ghost bar for perf hosts. Show
  all hosts, so idle eco hosts appear at 0 % with an "off" label.
* `PlacementTable`: add a **Zone** badge column and a reason tooltip. Use `vmId`
  instead of the array index as the row key (Phase 2 needs this).
* `MetricsSummary`: add an **Energy (kWh)** card and split Hosts Used into "P x / E y".
  Change the grid from `cols-4` to an auto-fit grid.
* The "Isolation check" text in `App.jsx` becomes "Spiky VMs run in the Performance
  Zone with peak headroom; n overflowed".

**Done when:** golden tests pass, energy matches PowerMeter, and zone colours, the
Zone column and the energy card appear in the UI.

---

## 5. Phase 2 — Real-time dashboard (~4 days)

### 5.1 Backend architecture
```
service/SimulationSessionRegistry   @Service — ConcurrentHashMap<String, SimulationSession>; @Scheduled TTL cleanup (10 min)
service/SimulationSession           state (CREATED/RUNNING/COMPLETED/FAILED/STOPPED), event buffer (replay),
                                    CopyOnWriteArrayList<SseEmitter>, ConcurrentLinkedQueue<VmRequest> injections (Phase 3), final result
service/SimulationEventPublisher    publish(sessionId, SimEvent) → buffer + send to every emitter; heartbeat comment every 15 s
config/AsyncConfig                  @EnableAsync + ThreadPoolTaskExecutor "simExecutor" (core 2, max 4, queue 10)
simulation/LiveSimulationEngine     runs one session on simExecutor (startSync loop, §6.1)
```

Endpoints (keep `POST /api/simulation/run` unchanged for batch mode and Phase 4):

| Method | Path | Purpose |
|---|---|---|
| POST | `/api/simulation/sessions` | Body: VM list (+ `timeScale`). Classifies, creates the session, starts it async, returns `{sessionId}` |
| GET | `/api/simulation/sessions/{id}/stream` | `text/event-stream`. **Replays buffered events first**, so nothing is lost before the client connects |
| GET | `/api/simulation/sessions/{id}` | Current snapshot (used after a reconnect) |
| POST | `/api/simulation/sessions/{id}/stop` | Stop |

SSE event types (named events, payload as JSON records):

| `event:` | Payload |
|---|---|
| `init` | Datacenter topology: hosts, zones, capacities |
| `placement` | `vmId, vmType, hostId, zone, cpuDemandMips, hostCpuUsedMips, hostCapacityMips, reason, simTime` |
| `placement_failed` | `vmId, reason, simTime` (send once, deduped across broker retries) |
| `host_update` | Per-host util snapshot, at most 2/s |
| `clock` | `simTime` |
| `migration` | Phase 3 |
| `completed` | Final `SimulationResult` |
| `error` | Message |

### 5.2 Key details
* **Pacing.** A batch places all VMs at t≈0 in under 100 ms, so without pacing the
  "live" view is instant. Use the startSync loop (§6.1), mapping sim seconds to wall
  time (`timeScale`, e.g. 1 sim-hour = 30 s). Stagger arrivals with
  `vm.setSubmissionDelay(i × arrivalGap)`. Add an optional `placementDelayMs` for t=0
  bursts.
* **Where events fire.** In `TrackedAllocationPolicy`'s confirmed-allocation path,
  through `PlacementListener`. The runner adapts it to the publisher, so the policy
  stays Spring-free and testable.
* **Concurrency.**
  * Remove `synchronized` from `CloudSimRunner.run()`. It has no shared mutable state,
    and each run creates its own `CloudSimPlus`.
  * Only the sim thread touches CloudSim objects.
  * `SseEmitter`: use a long timeout. Register `onCompletion`/`onTimeout`/`onError` to
    remove the emitter. Set `spring.mvc.async.request-timeout`.
* **CORS / URLs.** Add `server.proxy['/api'] → http://localhost:8080` in
  `vite.config.js` and make `api.js` use `baseURL: '/api'`. Then
  `new EventSource('/api/…')` works as in the plan snippet.

### 5.3 Frontend
* `hooks/useSimulationStream(sessionId)`:
  * Uses `EventSource` with `addEventListener` per event type.
  * Keeps `{hosts, placements, events, status, clock}` in a `useReducer`.
  * Batches updates with `requestAnimationFrame` when many events arrive.
  * Closes on `completed` or unmount.
* Mode toggle on the form: **Instant (batch)** / **Live (stream)**.
* Placement table: `@keyframes rowIn` (fade + slide, 400 ms) on new rows.
* Host chart: bars grow as VMs land. Recharts animates when the data changes, as long
  as the host list is fixed from the `init` event.
* Sim-clock indicator and connection status in the header (reuse `status-dot`).

**Done when:** `curl -N …/stream` shows paced events, the UI fills row by row, and a
page reload re-attaches through the replay buffer.

---

## 6. Phase 3 — Live VM placement & migration (~6–7 days)

### 6.1 Live engine (verified on CloudSim Plus 8.0.0)
I tested this: with `startSync()` + `terminateAt()` the simulation stays alive with no
work, a VM injected mid-run (t=50) was placed, and `requestVmMigration(vm, host)`
finished in ~1.6 s, firing `addOnMigrationFinishListener`. This works although
`isVmMigrationSupported()` returns false.

```java
simulation.terminateAt(maxSimSeconds);          // e.g. 4 h; also stops on user request
simulation.startSync();
while (simulation.isRunning() && !session.stopRequested()) {
    drainInjectionQueue();                      // VMs posted from REST → broker.submitVm/submitCloudlet
    simulation.runFor(tickSeconds);             // e.g. 1 s
    loadMonitor.sample(simulation.clock());     // actual demand per host from utilisation traces
    if (clock >= nextRebalance) { rebalancer.run(); nextRebalance += 60; }
    publisher.hostUpdate(...);  sleep(wallMsPerTick);
}
```
Also call `broker.setVmDestructionDelay(…)` so finished VMs free capacity. The ledger
releases it in `deallocateHostForVm`.

### 6.2 Load model (makes spikes exist in time)
Today every cloudlet uses `UtilizationModelDynamic(1.0)`, so nothing ever spikes.
Add a seeded, deterministic `UtilizationTrace` per VM:
* **STABLE:** avg ± small noise, clamped to [min, max].
* **SPIKY:** baseline near min, with spikes to max for `spikeLen` every `spikePeriod`.
  The spike fraction `f = (avg − min)/(max − min)` keeps the long-run mean equal to
  avg.
* Stretch goal: real traces via `UtilizationModelPlanetLab` (present in 8.0.0). Derive
  min/max/avg from the trace so classification and load come from the same data.

Design decision: the **ledger and `HostLoadMonitor` are the source of truth for load**,
as the policy already is today. CloudSim provides the clock, VM lifecycle, migration
timing and the power-model formula. Energy and SLA metrics integrate the actual trace
load per tick. This keeps CloudSim's reservation (avg) separate from the observed load
(trace), which can exceed it, and that excess is exactly what an SLA violation is.

### 6.3 Rebalancer (every 60 sim-seconds, in priority order)

| Rule | Trigger | Action |
|---|---|---|
| **R1 Spike promotion** (report's idea) | VM in ECO whose observed window shows PAR > 2 and range > 40 % (the classifier rule applied to live data) | Reclassify SPIKY → migrate to a perf host with peak headroom |
| **R2 Hot host** (plan) | Eco host > 90 % for k consecutive samples | Move the smallest STABLE VM that brings it ≤ 80 % to the best-fit eco host that stays ≤ 80 % |
| **R3 Overflow repatriation** | Perf capacity freed (VM finished) | Move `overflow=true` spiky VMs from eco to perf |
| **R4 Consolidation** (optional, green) | Eco host < 20 % | Evacuate it if all its VMs fit elsewhere, then switch it off |

Safeguards:
* Per-VM cooldown (300 s) and a cap on migrations per round.
* Hysteresis: trigger at 90 %, target 80 %.
* During a migration, book the target at request time and release the source in
  `addOnMigrationFinishListener`.

Each migration produces a log entry:
`MigrationEvent{vmId, fromHost, toHost, fromZone, toZone, trigger, startTime, endTime}`.
It is published as SSE `migration` and stored in the session result.

### 6.4 API

| Method | Path | Notes |
|---|---|---|
| POST | `/api/simulation/sessions/{id}/vms` | Body: `VmRequest`. Classify in the REST thread, enqueue, return **202**. Duplicate `vmId` or a session that isn't running → **409** |
| POST | `/api/simulation/sessions/{id}/speed` | Optional: changes `timeScale` |

### 6.5 Frontend
* **Submit VM Live** form, shown while the session is RUNNING. Extract a `VmRow`
  component from `VMSubmissionForm` and reuse it.
* **Datacenter view** (new `DatacenterView.jsx`): host cards grouped in a Perf row and
  an Eco row, each showing VM chips and a util bar. A bar chart isn't a good canvas for
  arrows.
* **Migration arrow:**
  * On a `migration` event, read the `getBoundingClientRect()` of the source and
    target cards (refs).
  * Draw an absolutely positioned SVG path, animated with `stroke-dashoffset` for
    ~1.5 s.
  * Move the chip with a FLIP animation. `framer-motion`'s `layoutId` does this almost
    for free; it's the one dependency worth adding.
* **Migration Events** panel: newest first, colour-coded by trigger, showing sim time.

**Done when:**
* A misclassified "stable" VM that starts spiking is promoted to Perf within one
  rebalance interval, with an arrow animation.
* Live-submitted VMs appear without a restart.
* There is no migration ping-pong in a 4 h simulated run.

---

## 7. Phase 4 — Comparison with the base paper (~4 days batch + 2 days dynamic)

### 7.1 Policies compared (all extend `TrackedAllocationPolicy`, run on the same hosts)

| Policy | Rule | Role |
|---|---|---|
| **Ours: Zoned (+ migration in live mode)** | §4.3 (+ §6.3) | Proposed |
| Midsem policy | Today's rule on a homogeneous DC | Ablation: what the zones add |
| Round-Robin | VM i → host i mod n, skipping hosts that don't fit | Naive (plan) |
| First-Fit (type-unaware) | First host that fits avg demand | Common default |
| Aggregation (Best-Fit, type-unaware) | Densest host that fits | Problem statement's "tight aggregation": best energy, worst SLA |
| Segregation | One VM per host | Problem statement's "strict segregation": best SLA, worst energy |
| **Base paper** | Adaptive resource-aware placement as described in the paper | **Required** to support "improvement over base paper" claims. Implement its algorithm section step by step |

The problem statement names segregation and aggregation as the two failure modes. Our
policy sitting between them (near-aggregation energy with near-segregation SLA) is the
headline chart.

### 7.2 Metrics (computed identically for every policy from the ledger/load timeline)

| Metric | Definition | Better |
|---|---|---|
| Hosts activated | Hosts that ran ≥ 1 VM at any time | Lower |
| Energy (kWh) | Σ P(u)·Δt over active hosts (§4.4) | Lower |
| Avg CPU util | Time-weighted, active hosts | Higher |
| Spiky-VM SLA violations | Spiky VMs whose host demand > capacity in ≥ 1 tick (batch mode: when Σ peaks > cap) | Lower |
| SLATAH | Mean over hosts of overloaded time / active time | Lower |
| PDM | Migration degradation: 10 % of the VM's CPU during migration, divided by demand | Lower |
| SLAV = SLATAH × PDM, ESV = Energy × SLAV | Combined metrics standard in energy-aware VMP papers (Beloglazov & Buyya) | Lower |
| Failed allocations | — | Lower |
| Migrations | Count (live mode) | Lower |
| Makespan / avg VM slowdown | Measured, replaces "Avg Host Time" (F3) | Lower |
| Placement overhead | ns per decision (`System.nanoTime` in `selectHost`), supporting the "reduce overhead" objective | Lower |

### 7.3 Workloads
4-VM test cases are too small to separate the policies. Add a seeded
`WorkloadGenerator` with these scenarios:
* stable-heavy (20 % spiky), mixed (50 %), spiky-heavy (80 %)
* scale sweep: 20 / 50 / 100 / 200 VMs, with hosts scaled to match

Run **5 seeds per scenario** and report mean ± std.

### 7.4 Backend
* `ComparisonService`:
  * Classify once.
  * For each policy (a `Supplier<TrackedAllocationPolicy>`), build a fresh CloudSim
    and run it.
  * Run policies in parallel with `CompletableFuture` on `simExecutor`. This is safe
    once `synchronized` is removed, because each run is independent.
* `ComparisonResult{scenario, seed, List<PolicyRun{policy, summary, hostMetrics}>, Map<String, Improvement>}`
* `Improvement`:
  * lower-is-better metrics: `(baseline − ours) / baseline`
  * higher-is-better metrics: `(ours − baseline) / baseline`
  * show "—" when baseline = 0
* `POST /api/comparison/run`. Body is `{vms}` **or** `{scenario, vmCount, spikyRatio, seeds}`, plus `policies[]`.

### 7.5 Frontend
* Tabs in `App.jsx`: **Dashboard | Live | Comparison**. Plain state is enough; no
  router needed.
* **Small multiples:** one mini bar chart per metric, one bar per policy. Don't use a
  single grouped chart for metrics with different units (kWh vs % vs count); it
  misleads.
* "% improvement" badges against a baseline picked from a dropdown, e.g. "75 % fewer
  hosts vs Round-Robin".
* Results table plus **Export CSV**, built client-side as a `Blob` from the JSON. No
  backend endpoint needed.

**Done when:** the comparison runs for all scenarios × 5 seeds in under ~10 s, the CSV
opens in Excel, and the charts and table are ready to paste into the report.

---

## 8. Schedule & split (2 members)

Agree the JSON contracts (§4.5, §5.1, §7.4) on day 1, so backend and frontend can work
in parallel.

| Week | Member A (backend/simulation) | Member B (frontend/API) |
|---|---|---|
| 1 | Phase 0 (sizing fix, ledger refactor, golden tests) → Phase 1 zones + energy | Vite proxy, zone colours, Zone column, energy card, all-hosts chart |
| 2 | Phase 4 batch: baselines, base-paper policy, workload generator, `ComparisonService` | Comparison tab, small multiples, badges, CSV |
| 3 | Phase 2: session registry, publisher, async engine, SSE | `useSimulationStream`, live mode, row animations |
| 4 | Phase 3: load traces, monitor, rebalancer R1–R3, migrations, inject endpoint | Datacenter view, migration arrows, live form, migration log |
| 5 | Phase 4 dynamic metrics (SLATAH/PDM/ESV, migrations), experiment runs | Polish, screenshots, report figures |

If time runs short, cut **R4 consolidation**, the **PlanetLab traces** and the **speed
control**. Don't cut Phase 0 or the base-paper policy.

## 9. Risks

| Risk | Mitigation |
|---|---|
| Ledger and CloudSim drift apart again | Invariant test (§3.5) runs on every build |
| Comparison looks rigged (zones get better hardware) | Run baselines on the **same** zoned hosts. Also report a homogeneous-DC run with the same total MIPS |
| SSE drops behind proxies | 15 s heartbeat comment, replay buffer, `Last-Event-ID` |
| Rebalancer oscillation | Hysteresis, cooldown, migration cap. Log migrations per hour as a metric |
| Base paper algorithm unclear or too complex | Implement its core placement heuristic, and state in the report what was simplified |
| Classifier still heuristic | Optional: re-enable `callMlService()` with the fallback (already coded) once the ONNX model exists. Phase 3 R1 compensates for misclassification either way |
