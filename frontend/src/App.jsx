import { useState, useEffect } from 'react';
import './index.css';
import VMSubmissionForm from './components/VMSubmissionForm';
import PlacementTable from './components/PlacementTable';
import HostMetricsChart from './components/HostMetricsChart';
import MetricsSummary from './components/MetricsSummary';
import LiveStatus from './components/LiveStatus';
import DatacenterView from './components/DatacenterView';
import MigrationLog from './components/MigrationLog';
import useSimulationStream from './hooks/useSimulationStream';
import { runSimulation, startLiveSimulation, stopLiveSimulation, pingBackend } from './services/api';

export default function App() {
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [backendOnline, setBackendOnline] = useState(null); // null = unknown
  const [sessionId, setSessionId] = useState(null);
  const [stopping, setStopping] = useState(false);

  const stream = useSimulationStream(sessionId);
  const liveRunning = sessionId && (stream.status === 'connecting' || stream.status === 'running');

  // Probe backend on mount
  useEffect(() => {
    pingBackend()
      .then(() => setBackendOnline(true))
      .catch(() => setBackendOnline(false));
  }, []);

  // When a live session ends, show its final result like a batch run
  useEffect(() => {
    if (stream.result) {
      setResult(stream.result);
      setStopping(false);
    }
    if (stream.status === 'failed') {
      setError(stream.error);
      setStopping(false);
    }
  }, [stream.result, stream.status, stream.error]);

  async function handleSubmit(vms, { mode, timeScale, arrivalGapSeconds }) {
    setLoading(true);
    setError(null);
    setResult(null);
    setSessionId(null);
    try {
      if (mode === 'live') {
        setSessionId(await startLiveSimulation(vms, { timeScale, arrivalGapSeconds }));
      } else {
        setResult(await runSimulation(vms));
      }
    } catch (err) {
      setError(
        err?.response?.data?.message ||
          'Backend unreachable. Make sure the Java service is running on :8080.'
      );
    } finally {
      setLoading(false);
    }
  }

  async function handleStop() {
    setStopping(true);
    try {
      await stopLiveSimulation(sessionId);
    } catch {
      setStopping(false);
    }
  }

  // Final result (batch or finished live run) takes precedence over live state
  const view = result
    ? { placements: result.placements, hosts: result.hostMetrics, migrations: result.migrations }
    : liveRunning
    ? { placements: stream.placements, hosts: stream.hosts, migrations: stream.migrations }
    : null;
  const vmTypes = Object.fromEntries((view?.placements || []).map((p) => [p.vmId, p.vmType]));

  return (
    <div className="app-shell">
      {/* ── Header ─────────────────────────────────────────────── */}
      <header className="app-header">
        <div className="header-brand">
          <div className="header-logo">☁</div>
          <span className="header-title">VM Placement Dashboard</span>
        </div>

        <div className="header-status">
          <span
            className={`status-dot ${backendOnline === false ? 'offline' : ''}`}
          />
          {backendOnline === null
            ? 'Connecting…'
            : backendOnline
            ? 'Backend online'
            : 'Backend offline'}
        </div>
      </header>

      {/* ── Main ───────────────────────────────────────────────── */}
      <main className="app-main">

        {/* ── Submission form ─────────────────────────────── */}
        <div className="card mb-6">
          <VMSubmissionForm onSubmit={handleSubmit} loading={loading || liveRunning} />
        </div>

        {/* ── Error banner ─────────────────────────────────── */}
        {error && (
          <div
            className="card mb-6"
            style={{ borderColor: 'var(--accent-danger)', color: 'var(--accent-danger)' }}
            id="error-banner"
          >
            ⚠ {error}
          </div>
        )}

        {/* ── Results section ──────────────────────────────── */}
        {view && (
          <>
            {/* Summary cards */}
            <div className="mb-6">
              {result
                ? <MetricsSummary summary={result.summary} />
                : <LiveStatus stream={stream} onStop={handleStop} stopping={stopping} />}
            </div>

            {/* Live datacenter: VMs on hosts right now, migration arrows */}
            {!result && (
              <div className="card mb-6">
                <div className="card-title">🖥 Datacenter (live)</div>
                <DatacenterView
                  hosts={stream.hosts}
                  vmTypes={vmTypes}
                  spiking={stream.spiking}
                  migrations={stream.migrations}
                />
              </div>
            )}

            {/* Charts + table */}
            <div className="section-grid cols-2 mb-6">
              {/* Host metrics chart */}
              <div className="card">
                <div className="card-title">📊 Host Metrics</div>
                <HostMetricsChart hostMetrics={view.hosts} live={!result} />
              </div>

              {/* Classification breakdown */}
              <div className="card">
                <div className="card-title">🔍 Classification Breakdown</div>
                <ClassificationBreakdown placements={view.placements} />
              </div>
            </div>

            {/* Placements table */}
            <div className="card">
              <div className="card-title flex-between">
                <span>📋 VM Placement Results</span>
                <span className="text-muted">
                  {view.placements.length} VMs
                </span>
              </div>
              <PlacementTable placements={view.placements} live={result ? null : stream} />
            </div>

            {/* Migration log */}
            <div className="card mt-6">
              <div className="card-title flex-between">
                <span>⇄ Live Migrations</span>
                <span className="text-muted">{view.migrations.length} migrations</span>
              </div>
              <MigrationLog migrations={view.migrations} />
            </div>
          </>
        )}

        {/* ── Empty hero ────────────────────────────────────── */}
        {!view && !loading && (
          <div className="card" style={{ padding: '4rem 2rem' }}>
            <div className="empty-state">
              <span className="icon" style={{ fontSize: '4rem', opacity: 0.3 }}>☁</span>
              <span style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-secondary)' }}>
                Ready to simulate
              </span>
              <span>
                Add VMs above and click <strong>▶ Run Simulation</strong> to see placement results,
                or switch to <strong>Live</strong> to watch them being placed.
              </span>
            </div>
          </div>
        )}
      </main>
    </div>
  );
}

/* ── Inline helper component ───────────────────────────────── */
function ClassificationBreakdown({ placements }) {
  const spiky = placements.filter((p) => p.vmType === 'SPIKY');
  const stable = placements.filter((p) => p.vmType === 'STABLE');
  const migrated = placements.filter((p) => p.migrationCount > 0).length;

  const spikyPct = placements.length ? (spiky.length / placements.length) * 100 : 0;
  const stablePct = 100 - spikyPct;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
      {/* Donut-style breakdown */}
      <div className="section-grid cols-2" style={{ gap: '1rem', marginTop: '0.5rem' }}>
        <div
          style={{
            background: 'rgba(249,115,22,0.08)',
            border: '1px solid rgba(249,115,22,0.25)',
            borderRadius: 12,
            padding: '1.25rem',
            textAlign: 'center',
          }}
        >
          <div style={{ fontSize: '2.5rem', fontWeight: 800, color: 'var(--accent-spiky)' }}>
            {spiky.length}
          </div>
          <div style={{ fontSize: '0.75rem', color: 'var(--accent-spiky)', fontWeight: 600 }}>
            ⚡ SPIKY VMs
          </div>
          <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginTop: 4 }}>
            one per Eco host, migrate on spikes
          </div>
        </div>
        <div
          style={{
            background: 'rgba(34,211,238,0.06)',
            border: '1px solid rgba(34,211,238,0.2)',
            borderRadius: 12,
            padding: '1.25rem',
            textAlign: 'center',
          }}
        >
          <div style={{ fontSize: '2.5rem', fontWeight: 800, color: 'var(--accent-stable)' }}>
            {stable.length}
          </div>
          <div style={{ fontSize: '0.75rem', color: 'var(--accent-stable)', fontWeight: 600 }}>
            ● STABLE VMs
          </div>
          <div style={{ fontSize: '0.7rem', color: 'var(--text-muted)', marginTop: 4 }}>
            Eco Zone, best-fit packed
          </div>
        </div>
      </div>

      {/* Mix bar */}
      <div>
        <div className="flex-between mb-4" style={{ marginBottom: '0.5rem' }}>
          <span style={{ fontSize: '0.75rem', color: 'var(--accent-spiky)', fontWeight: 600 }}>
            Spiky {spikyPct.toFixed(1)}%
          </span>
          <span style={{ fontSize: '0.75rem', color: 'var(--accent-stable)', fontWeight: 600 }}>
            Stable {stablePct.toFixed(1)}%
          </span>
        </div>
        <div style={{ height: 12, borderRadius: 999, background: 'rgba(255,255,255,0.06)', overflow: 'hidden' }}>
          <div style={{ display: 'flex', height: '100%' }}>
            <div
              style={{
                width: `${spikyPct}%`,
                background: 'linear-gradient(90deg,#f97316,#fb923c)',
                borderRadius: '999px 0 0 999px',
                transition: 'width 0.6s ease',
              }}
            />
            <div
              style={{
                flex: 1,
                background: 'linear-gradient(90deg,#818cf8,#22d3ee)',
                borderRadius: '0 999px 999px 0',
                transition: 'width 0.6s ease',
              }}
            />
          </div>
        </div>
      </div>

      {/* Per-host spiky isolation confirmation */}
      <div style={{ fontSize: '0.8rem', color: 'var(--text-secondary)' }}>
        <strong style={{ color: 'var(--text-primary)' }}>Isolation check: </strong>
        Every VM starts in the Eco Zone with at most one spiky VM per host. Spiky VMs
        move to the Performance Zone while they spike
        {migrated > 0 ? ` (${migrated} VM${migrated !== 1 ? 's' : ''} migrated so far).` : '.'}
      </div>
    </div>
  );
}
