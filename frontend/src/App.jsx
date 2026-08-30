import { useState, useEffect } from 'react';
import './index.css';
import VMSubmissionForm from './components/VMSubmissionForm';
import PlacementTable from './components/PlacementTable';
import HostMetricsChart from './components/HostMetricsChart';
import MetricsSummary from './components/MetricsSummary';
import { runSimulation, pingBackend } from './services/api';

export default function App() {
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [backendOnline, setBackendOnline] = useState(null); // null = unknown

  // Probe backend on mount
  useEffect(() => {
    pingBackend()
      .then(() => setBackendOnline(true))
      .catch(() => setBackendOnline(false));
  }, []);

  async function handleSubmit(vms) {
    setLoading(true);
    setError(null);
    try {
      const data = await runSimulation(vms);
      setResult(data);
    } catch (err) {
      setError(
        err?.response?.data?.message ||
          'Backend unreachable. Make sure the Java service is running on :8080.'
      );
    } finally {
      setLoading(false);
    }
  }

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
          <VMSubmissionForm onSubmit={handleSubmit} loading={loading} />
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
        {result && (
          <>
            {/* Summary cards */}
            <div className="mb-6">
              <MetricsSummary summary={result.summary} />
            </div>

            {/* Charts + table */}
            <div className="section-grid cols-2 mb-6">
              {/* Host metrics chart */}
              <div className="card">
                <div className="card-title">📊 Host Metrics</div>
                <HostMetricsChart hostMetrics={result.hostMetrics} />
              </div>

              {/* Classification breakdown */}
              <div className="card">
                <div className="card-title">🔍 Classification Breakdown</div>
                <ClassificationBreakdown placements={result.placements} />
              </div>
            </div>

            {/* Placements table */}
            <div className="card">
              <div className="card-title flex-between">
                <span>📋 VM Placement Results</span>
                <span className="text-muted">
                  {result.placements.length} VMs
                </span>
              </div>
              <PlacementTable placements={result.placements} />
            </div>
          </>
        )}

        {/* ── Empty hero ────────────────────────────────────── */}
        {!result && !loading && (
          <div className="card" style={{ padding: '4rem 2rem' }}>
            <div className="empty-state">
              <span className="icon" style={{ fontSize: '4rem', opacity: 0.3 }}>☁</span>
              <span style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-secondary)' }}>
                Ready to simulate
              </span>
              <span>
                Add VMs above and click <strong>▶ Run Simulation</strong> to see placement results.
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
            each on a separate host
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
            best-fit packed together
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
        Spiky VMs are placed on dedicated hosts with no other spiky VM co-located.
      </div>
    </div>
  );
}
