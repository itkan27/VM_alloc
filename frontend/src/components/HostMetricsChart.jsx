import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ReferenceLine,
  ResponsiveContainer,
  Cell,
} from 'recharts';

const ZONE_COLORS = {
  PERFORMANCE: '#f97316', // orange
  ECO: '#22c55e',         // green
};

const ZONE_LABELS = { PERFORMANCE: 'Performance', ECO: 'Eco' };

const tooltipBox = {
  background: 'rgba(17,24,39,0.95)',
  border: '1px solid rgba(255,255,255,0.1)',
  borderRadius: 10,
  padding: '0.75rem 1rem',
  fontSize: '0.8rem',
  backdropFilter: 'blur(8px)',
};

const HostTooltip = ({ active, payload, label }) => {
  if (!active || !payload?.length) return null;
  const h = payload[0].payload.raw;
  return (
    <div style={tooltipBox}>
      <p style={{ fontWeight: 700, marginBottom: 6 }}>
        {label}{' '}
        <span style={{ color: ZONE_COLORS[h.zone] }}>· {ZONE_LABELS[h.zone]} Zone</span>
      </p>
      {!h.active ? (
        <p style={{ color: '#94a3b8' }}>No VMs — powered off</p>
      ) : (
        <>
          <p>CPU (avg): <strong>{(h.cpuUtilization * 100).toFixed(1)}%</strong></p>
          <p>
            Peak-safe demand: <strong>{Math.round(h.peakDemandMips)}</strong> / {Math.round(h.capacityMips)} MIPS
          </p>
          <p>VMs: <strong>{h.totalVms}</strong> ({h.spikyVms} spiky · {h.stableVms} stable)</p>
          {h.energyKWh != null && <p>Energy: <strong>{h.energyKWh.toFixed(3)} kWh</strong></p>}
        </>
      )}
    </div>
  );
};

const CountTooltip = ({ active, payload, label }) => {
  if (!active || !payload?.length) return null;
  return (
    <div style={tooltipBox}>
      <p style={{ fontWeight: 700, marginBottom: 6 }}>{label}</p>
      {payload.map((entry) => (
        <p key={entry.name} style={{ color: entry.color, marginBottom: 2 }}>
          {entry.name}: <strong>{entry.value}</strong>
        </p>
      ))}
    </div>
  );
};

function ZoneLegend() {
  return (
    <div style={{ display: 'flex', justifyContent: 'center', gap: '1rem', fontSize: 11, color: '#94a3b8', paddingTop: 4 }}>
      {Object.entries(ZONE_LABELS).map(([zone, label]) => (
        <span key={zone} style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
          <span style={{ width: 8, height: 8, borderRadius: '50%', background: ZONE_COLORS[zone] }} />
          {label} Zone
        </span>
      ))}
      <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
        <span style={{ width: 12, borderTop: '2px dashed rgba(248,113,113,0.6)' }} />
        90% threshold
      </span>
    </div>
  );
}

/**
 * HostMetricsChart
 * CPU utilisation per host (coloured by zone) and VM composition per host.
 * Accepts final HostMetric rows or live HostSnapshot rows (same field names).
 * In live mode animation is off: bars update several times a second, and each
 * update would restart the animation before it finishes.
 */
export default function HostMetricsChart({ hostMetrics, live = false }) {
  if (!hostMetrics || hostMetrics.length === 0) {
    return (
      <div className="empty-state">
        <span className="icon">📊</span>
        <span>Host metrics will appear after simulation.</span>
      </div>
    );
  }

  const data = hostMetrics.map((h) => ({
    name: h.hostName,
    'CPU Util %': parseFloat((h.cpuUtilization * 100).toFixed(1)),
    Spiky: h.spikyVms,
    Stable: h.stableVms,
    raw: h,
  }));

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}>
      {/* CPU Utilization */}
      <div>
        <p className="card-title">CPU Utilisation per Host</p>
        <ResponsiveContainer width="100%" height={220}>
          <BarChart data={data} barSize={22} margin={{ top: 5, right: 10, left: -10, bottom: 5 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.05)" />
            <XAxis dataKey="name" tick={{ fill: '#94a3b8', fontSize: 11 }} axisLine={false} tickLine={false} />
            <YAxis unit="%" domain={[0, 100]} tick={{ fill: '#94a3b8', fontSize: 11 }} axisLine={false} tickLine={false} />
            <Tooltip content={<HostTooltip />} cursor={{ fill: 'rgba(255,255,255,0.04)' }} />
            <ReferenceLine y={90} stroke="rgba(248,113,113,0.6)" strokeDasharray="4 4" />
            <Bar dataKey="CPU Util %" radius={[4, 4, 0, 0]} isAnimationActive={!live}>
              {data.map((entry) => (
                <Cell
                  key={entry.name}
                  fill={ZONE_COLORS[entry.raw.zone]}
                  fillOpacity={entry.raw.active ? 1 : 0.25}
                />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
        <ZoneLegend />
      </div>

      {/* VM composition per host */}
      <div>
        <p className="card-title">VM Composition per Host</p>
        <ResponsiveContainer width="100%" height={180}>
          <BarChart data={data} barSize={18} margin={{ top: 5, right: 10, left: -10, bottom: 5 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.05)" />
            <XAxis dataKey="name" tick={{ fill: '#94a3b8', fontSize: 11 }} axisLine={false} tickLine={false} />
            <YAxis allowDecimals={false} tick={{ fill: '#94a3b8', fontSize: 11 }} axisLine={false} tickLine={false} />
            <Tooltip content={<CountTooltip />} cursor={{ fill: 'rgba(255,255,255,0.04)' }} />
            <Legend
              wrapperStyle={{ fontSize: 11, color: '#94a3b8', paddingTop: 8 }}
              iconType="circle"
              iconSize={8}
            />
            <Bar dataKey="Spiky" stackId="a" fill="#f97316" radius={[0, 0, 0, 0]} isAnimationActive={!live} />
            <Bar dataKey="Stable" stackId="a" fill="#22d3ee" radius={[4, 4, 0, 0]} isAnimationActive={!live} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
