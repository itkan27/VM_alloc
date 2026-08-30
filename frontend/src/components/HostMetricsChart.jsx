import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
  Cell,
} from 'recharts';

const CustomTooltip = ({ active, payload, label }) => {
  if (!active || !payload?.length) return null;
  return (
    <div
      style={{
        background: 'rgba(17,24,39,0.95)',
        border: '1px solid rgba(255,255,255,0.1)',
        borderRadius: 10,
        padding: '0.75rem 1rem',
        fontSize: '0.8rem',
        backdropFilter: 'blur(8px)',
      }}
    >
      <p style={{ fontWeight: 700, marginBottom: 6 }}>{label}</p>
      {payload.map((entry) => (
        <p key={entry.name} style={{ color: entry.color, marginBottom: 2 }}>
          {entry.name}: <strong>{typeof entry.value === 'number' && entry.value < 2 ? `${(entry.value * 100).toFixed(1)}%` : entry.value}</strong>
        </p>
      ))}
    </div>
  );
};

/**
 * HostMetricsChart
 * Grouped bar chart: CPU utilisation, spiky VM count, stable VM count per host.
 */
export default function HostMetricsChart({ hostMetrics }) {
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
        <ResponsiveContainer width="100%" height={200}>
          <BarChart data={data} barSize={22} margin={{ top: 5, right: 10, left: -10, bottom: 5 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.05)" />
            <XAxis dataKey="name" tick={{ fill: '#94a3b8', fontSize: 11 }} axisLine={false} tickLine={false} />
            <YAxis unit="%" domain={[0, 100]} tick={{ fill: '#94a3b8', fontSize: 11 }} axisLine={false} tickLine={false} />
            <Tooltip content={<CustomTooltip />} cursor={{ fill: 'rgba(255,255,255,0.04)' }} />
            <Bar dataKey="CPU Util %" radius={[4, 4, 0, 0]}>
              {data.map((entry, i) => (
                <Cell
                  key={i}
                  fill={
                    entry['CPU Util %'] > 80
                      ? '#f97316'
                      : entry['CPU Util %'] > 50
                      ? '#818cf8'
                      : '#22d3ee'
                  }
                />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>

      {/* VM composition per host */}
      <div>
        <p className="card-title">VM Composition per Host</p>
        <ResponsiveContainer width="100%" height={180}>
          <BarChart data={data} barSize={18} margin={{ top: 5, right: 10, left: -10, bottom: 5 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="rgba(255,255,255,0.05)" />
            <XAxis dataKey="name" tick={{ fill: '#94a3b8', fontSize: 11 }} axisLine={false} tickLine={false} />
            <YAxis allowDecimals={false} tick={{ fill: '#94a3b8', fontSize: 11 }} axisLine={false} tickLine={false} />
            <Tooltip content={<CustomTooltip />} cursor={{ fill: 'rgba(255,255,255,0.04)' }} />
            <Legend
              wrapperStyle={{ fontSize: 11, color: '#94a3b8', paddingTop: 8 }}
              iconType="circle"
              iconSize={8}
            />
            <Bar dataKey="Spiky" stackId="a" fill="#f97316" radius={[0, 0, 0, 0]} />
            <Bar dataKey="Stable" stackId="a" fill="#22d3ee" radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}
