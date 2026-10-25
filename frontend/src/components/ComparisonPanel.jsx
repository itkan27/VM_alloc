import { useMemo, useState } from 'react';
import { BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell } from 'recharts';
import { runComparison } from '../services/api';

const POLICIES = [
  { id: 'ZONED', label: 'Ours: Eco/Performance zones', color: '#f97316' },
  { id: 'ARA', label: 'Base paper: ARA', color: '#818cf8' },
  { id: 'FIRST_FIT', label: 'First Fit', color: '#22d3ee' },
  { id: 'BEST_FIT', label: 'Best Fit', color: '#4ade80' },
  { id: 'MIDSEM', label: 'Ours (midsem)', color: '#fbbf24' },
];
const COLOR = Object.fromEntries(POLICIES.map((p) => [p.id, p.color]));

/** Metrics compared, with the direction that counts as better */
const METRICS = [
  { key: 'hostsUsed', label: 'Hosts Used', better: 'lower', get: (s) => s.hostsUsed, digits: 0 },
  { key: 'avgCpuUtilization', label: 'Avg CPU Utilisation (%)', better: 'higher', get: (s) => s.avgCpuUtilization * 100, digits: 1 },
  { key: 'slaViolationRate', label: 'SLA Violation Rate (%)', better: 'lower', get: (s) => s.slaViolationRate, digits: 1 },
  { key: 'totalEnergyKWh', label: 'Energy Consumption (kWh)', better: 'lower', get: (s) => s.totalEnergyKWh, digits: 3 },
  { key: 'migrations', label: 'VM Migrations', better: 'lower', get: (s) => s.migrations, digits: 0 },
  { key: 'avgPlacementTimeMs', label: 'Avg Placement Time (ms)', better: 'lower', get: (s) => s.avgPlacementTimeMs, digits: 3 },
  { key: 'failedAllocations', label: 'Failed Allocations', better: 'lower', get: (s) => s.failedAllocations, digits: 0 },
  { key: 'overloadHostSeconds', label: 'Host Overload Time (s)', better: 'lower', get: (s) => s.overloadHostSeconds, digits: 0 },
];

/** % by which `ours` beats `base` (positive = better), or null when undefined */
function improvement(metric, ours, base) {
  if (base === 0) return ours === 0 ? 0 : null;
  return metric.better === 'lower' ? ((base - ours) / base) * 100 : ((ours - base) / base) * 100;
}

const DEFAULTS = {
  vmCount: 200,
  spikyPercent: 20,
  hostCount: 50,
  performanceCount: 5,
  seed: 42,
  minDurationSeconds: 300,
  maxDurationSeconds: 600,
};

/**
 * ComparisonPanel
 * Runs every placement policy on the same workload and compares them metric by metric:
 * a table with the best value per metric, % improvement of our policy over a chosen
 * baseline (the base paper's ARA by default), one bar chart per metric, the base
 * paper's published Table II for reference, and CSV export.
 */
export default function ComparisonPanel({ lastVms }) {
  const [form, setForm] = useState(DEFAULTS);
  const [source, setSource] = useState('generated'); // generated | batch
  const [selected, setSelected] = useState(POLICIES.map((p) => p.id));
  const [baseline, setBaseline] = useState('ARA');
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const runs = useMemo(() => result?.runs || [], [result]);
  const ours = runs.find((r) => r.policy === 'ZONED');
  const base = runs.find((r) => r.policy === baseline);

  async function handleRun(e) {
    e.preventDefault();
    setLoading(true);
    setError(null);
    try {
      const request = { policies: selected };
      if (source === 'batch' && lastVms?.length) {
        request.vms = lastVms;
        request.hostCount = form.hostCount;
        request.performanceCount = form.performanceCount;
      } else {
        Object.assign(request, form);
      }
      const data = await runComparison(request);
      setResult(data);
      if (!data.runs.some((r) => r.policy === baseline)) {
        setBaseline(data.runs.find((r) => r.policy !== 'ZONED')?.policy || data.runs[0]?.policy);
      }
    } catch (err) {
      setError(err?.response?.data?.message || 'Comparison failed. Is the Java backend running on :8080?');
    } finally {
      setLoading(false);
    }
  }

  function togglePolicy(id) {
    setSelected((prev) => (prev.includes(id) ? prev.filter((p) => p !== id) : [...prev, id]));
  }

  function exportCsv() {
    const header = ['Metric', 'Better', ...runs.map((r) => r.label)];
    if (ours && base && ours !== base) header.push(`Ours vs ${base.label} (%)`);
    const lines = [header];
    for (const m of METRICS) {
      const row = [m.label, m.better, ...runs.map((r) => m.get(r.summary).toFixed(m.digits === 0 ? 0 : 4))];
      if (ours && base && ours !== base) {
        const imp = improvement(m, m.get(ours.summary), m.get(base.summary));
        row.push(imp == null ? '' : imp.toFixed(2));
      }
      lines.push(row);
    }
    const sc = result.scenario;
    lines.push([]);
    lines.push(['Scenario', `${sc.vmCount} VMs (${sc.spikyVms} spiky, ${sc.stableVms} stable)`,
      `${sc.hostCount} hosts (${sc.performanceCount} performance)`,
      sc.generated ? `generated, seed ${sc.seed}` : 'batch from Simulation tab',
      `durations ${sc.minDurationSeconds}-${sc.maxDurationSeconds} s`]);
    lines.push([]);
    lines.push(['Base paper Table II', 'Avg CPU (%)', 'SLA violation (%)', 'Energy (kWh)', 'Migrations', 'Placement time (ms)']);
    for (const p of result.paperReference) {
      lines.push([p.algorithm, p.avgCpuUtilization, p.slaViolationRate, p.energyKWh, p.migrations, p.placementTimeMs]);
    }

    const csv = lines.map((row) => row.map((v) => `"${String(v).replace(/"/g, '""')}"`).join(',')).join('\n');
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = 'vm-placement-comparison.csv';
    a.click();
    URL.revokeObjectURL(url);
  }

  const best = useMemo(() => {
    const out = {};
    for (const m of METRICS) {
      const values = runs.map((r) => m.get(r.summary));
      out[m.key] = m.better === 'lower' ? Math.min(...values) : Math.max(...values);
    }
    return out;
  }, [runs]);

  const field = (key, label, props = {}) => (
    <div className="input-group" key={key}>
      <label className="input-label" htmlFor={`cmp-${key}`}>{label}</label>
      <input
        id={`cmp-${key}`}
        className="input-field"
        type="number"
        step="any"
        value={form[key]}
        onChange={(e) => setForm((f) => ({ ...f, [key]: Number(e.target.value) }))}
        required
        {...props}
      />
    </div>
  );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
      {/* ── Set-up ─────────────────────────────────────────── */}
      <form className="card" onSubmit={handleRun}>
        <div className="flex-between mb-4">
          <div>
            <h2 style={{ fontSize: '1rem', fontWeight: 700, color: 'var(--text-primary)' }}>
              Comparison with the Base Paper
            </h2>
            <p className="text-muted">
              Every policy runs on the same VMs, datacenter and simulated spikes. Defaults follow the
              paper&apos;s set-up (50 hosts, 200 VMs).
            </p>
          </div>
          <button type="submit" className="btn btn-primary" disabled={loading || selected.length === 0} id="btn-run-comparison">
            {loading ? <><span className="spinner" /> Comparing…</> : '▶ Run Comparison'}
          </button>
        </div>

        <div className="flex gap-3 mb-4 flex-center">
          <div className="mode-toggle" role="group" aria-label="Workload source">
            <button type="button" className={source === 'generated' ? 'active' : ''} onClick={() => setSource('generated')}>
              Generated workload
            </button>
            <button
              type="button"
              className={source === 'batch' ? 'active' : ''}
              onClick={() => setSource('batch')}
              disabled={!lastVms?.length}
              title={lastVms?.length ? '' : 'Run a simulation first'}
            >
              Last simulated batch{lastVms?.length ? ` (${lastVms.length})` : ''}
            </button>
          </div>
        </div>

        <div className="cmp-fields">
          {source === 'generated' && [
            field('vmCount', 'VMs', { min: 1, max: 2000 }),
            field('spikyPercent', 'Spiky %', { min: 0, max: 100 }),
            field('seed', 'Seed'),
            field('minDurationSeconds', 'Min duration (s)', { min: 10 }),
            field('maxDurationSeconds', 'Max duration (s)', { min: 10 }),
          ]}
          {field('hostCount', 'Hosts', { min: 1, max: 500 })}
          {field('performanceCount', 'Performance hosts', { min: 0, max: 500 })}
        </div>

        <div className="flex gap-3 mt-4" style={{ flexWrap: 'wrap' }}>
          {POLICIES.map((p) => (
            <label key={p.id} className="cmp-policy">
              <input type="checkbox" checked={selected.includes(p.id)} onChange={() => togglePolicy(p.id)} />
              <span className="cmp-swatch" style={{ background: p.color }} />
              {p.label}
            </label>
          ))}
        </div>
      </form>

      {error && (
        <div className="card" style={{ borderColor: 'var(--accent-danger)', color: 'var(--accent-danger)' }}>⚠ {error}</div>
      )}

      {result && (
        <>
          {/* ── Scenario + improvement badges ──────────────── */}
          <div className="card">
            <div className="card-title flex-between">
              <span>📈 Results</span>
              <span className="flex gap-3 flex-center">
                <label className="input-label" htmlFor="cmp-baseline">Compare ours against</label>
                <select
                  id="cmp-baseline"
                  className="input-field"
                  style={{ width: 'auto' }}
                  value={baseline}
                  onChange={(e) => setBaseline(e.target.value)}
                >
                  {runs.filter((r) => r.policy !== 'ZONED').map((r) => (
                    <option key={r.policy} value={r.policy}>{r.label}</option>
                  ))}
                </select>
                <button type="button" className="btn btn-ghost" onClick={exportCsv} id="btn-export-csv">⬇ Export CSV</button>
              </span>
            </div>
            <p className="text-muted mb-4">
              {result.scenario.vmCount} VMs ({result.scenario.spikyVms} spiky · {result.scenario.stableVms} stable) on{' '}
              {result.scenario.hostCount} hosts ({result.scenario.performanceCount} performance) ·{' '}
              {result.scenario.generated ? `generated workload, seed ${result.scenario.seed}` : 'batch from the Simulation tab'} ·
              durations {result.scenario.minDurationSeconds}–{result.scenario.maxDurationSeconds} s
            </p>

            {ours && base && ours !== base ? (
              <div className="cmp-badges">
                {METRICS.map((m) => {
                  const imp = improvement(m, m.get(ours.summary), m.get(base.summary));
                  const cls = imp == null || Math.abs(imp) < 0.05 ? 'neutral' : imp > 0 ? 'better' : 'worse';
                  return (
                    <div key={m.key} className={`cmp-badge cmp-badge-${cls}`}>
                      <span className="cmp-badge-value">
                        {imp == null ? 'n/a' : `${imp > 0 ? '+' : ''}${imp.toFixed(1)}%`}
                      </span>
                      <span className="cmp-badge-label">{m.label}</span>
                      <span className="cmp-badge-sub">
                        {imp == null ? `baseline is 0` : imp > 0 ? 'better' : imp < 0 ? 'worse' : 'same'} vs {base.label}
                      </span>
                    </div>
                  );
                })}
              </div>
            ) : (
              <p className="text-muted">Include our zoned policy and at least one other policy to see % improvement.</p>
            )}
          </div>

          {/* ── Metric table ───────────────────────────────── */}
          <div className="card">
            <div className="card-title">📋 Metrics by Policy <span className="text-muted">· best value highlighted</span></div>
            <div className="table-wrapper" id="comparison-table">
              <table>
                <thead>
                  <tr>
                    <th>Metric</th>
                    {runs.map((r) => (
                      <th key={r.policy} title={r.description}>
                        <span className="cmp-swatch" style={{ background: COLOR[r.policy] }} /> {r.label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {METRICS.map((m) => (
                    <tr key={m.key}>
                      <td>
                        {m.label} <span className="text-muted">({m.better} is better)</span>
                      </td>
                      {runs.map((r) => {
                        const v = m.get(r.summary);
                        return (
                          <td key={r.policy} className={`mono ${v === best[m.key] ? 'cmp-best' : ''}`}>
                            {v.toFixed(m.digits)}
                          </td>
                        );
                      })}
                    </tr>
                  ))}
                  <tr>
                    <td className="text-muted">Simulation wall time (ms)</td>
                    {runs.map((r) => (
                      <td key={r.policy} className="mono text-muted">{r.wallTimeMs}</td>
                    ))}
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          {/* ── One chart per metric ───────────────────────── */}
          <div className="cmp-charts">
            {METRICS.map((m) => (
              <div key={m.key} className="card">
                <p className="card-title">{m.label}</p>
                <ResponsiveContainer width="100%" height={170}>
                  <BarChart
                    data={runs.map((r) => ({ name: r.label, policy: r.policy, value: Number(m.get(r.summary).toFixed(m.digits === 0 ? 0 : 4)) }))}
                    margin={{ top: 5, right: 5, left: -15, bottom: 5 }}
                  >
                    <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.05)" />
                    <XAxis dataKey="policy" tick={{ fill: '#94a3b8', fontSize: 9 }} axisLine={false} tickLine={false} interval={0} />
                    <YAxis tick={{ fill: '#94a3b8', fontSize: 10 }} axisLine={false} tickLine={false} />
                    <Tooltip
                      cursor={{ fill: 'rgba(255,255,255,0.04)' }}
                      contentStyle={{ background: 'rgba(17,24,39,0.95)', border: '1px solid rgba(255,255,255,0.1)', borderRadius: 10, fontSize: 12 }}
                      labelFormatter={(_, payload) => payload?.[0]?.payload?.name}
                      formatter={(v) => [v, m.label]}
                    />
                    <Bar dataKey="value" radius={[4, 4, 0, 0]}>
                      {runs.map((r) => <Cell key={r.policy} fill={COLOR[r.policy]} />)}
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              </div>
            ))}
          </div>

          {/* ── Base paper's published numbers ─────────────── */}
          <div className="card">
            <div className="card-title">📄 Base Paper — Published Results (Table II)</div>
            <p className="text-muted mb-4">{result.paperNote}</p>
            <div className="table-wrapper" id="paper-reference">
              <table>
                <thead>
                  <tr>
                    <th>Algorithm</th>
                    <th>Avg CPU Utilisation (%)</th>
                    <th>SLA Violation Rate (%)</th>
                    <th>Energy (kWh)</th>
                    <th>VM Migrations</th>
                    <th>Placement Time (ms)</th>
                  </tr>
                </thead>
                <tbody>
                  {result.paperReference.map((p) => (
                    <tr key={p.algorithm}>
                      <td>{p.algorithm}</td>
                      <td className="mono">{p.avgCpuUtilization}</td>
                      <td className="mono">{p.slaViolationRate}</td>
                      <td className="mono">{p.energyKWh}</td>
                      <td className="mono">{p.migrations}</td>
                      <td className="mono">{p.placementTimeMs}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
