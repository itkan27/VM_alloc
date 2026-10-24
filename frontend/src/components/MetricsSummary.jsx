/**
 * MetricsSummary
 * Headline cards showing totals from the simulation summary.
 */
export default function MetricsSummary({ summary }) {
  if (!summary) return null;

  const {
    totalVms,
    spikyVms,
    stableVms,
    hostsUsed,
    performanceHostsUsed,
    ecoHostsUsed,
    failedAllocations,
    migrations,
    spikes,
    overloadHostSeconds,
    avgCpuUtilization,
    avgHostActiveSeconds,
    totalEnergyKWh,
  } = summary;

  const cards = [
    {
      id: 'card-total-vms',
      label: 'Total VMs',
      value: totalVms,
      sub: `${spikyVms} spiky · ${stableVms} stable`,
      color: 'var(--accent-primary)',
    },
    {
      id: 'card-hosts-used',
      label: 'Hosts Used',
      value: hostsUsed,
      sub: `${performanceHostsUsed} perf · ${ecoHostsUsed} eco · ${failedAllocations} failure${failedAllocations !== 1 ? 's' : ''}`,
      color: failedAllocations > 0 ? 'var(--accent-danger)' : 'var(--accent-success)',
    },
    {
      id: 'card-avg-util',
      label: 'Avg CPU Util',
      value: `${(avgCpuUtilization * 100).toFixed(1)}%`,
      sub: 'time-averaged, active hosts',
      color: avgCpuUtilization > 0.8 ? 'var(--accent-spiky)' : 'var(--accent-stable)',
    },
    {
      id: 'card-migrations',
      label: 'Live Migrations',
      value: migrations,
      sub: `${spikes} spike${spikes !== 1 ? 's' : ''} · ${overloadHostSeconds.toFixed(0)} s host overload`,
      color: 'var(--accent-spiky)',
    },
    {
      id: 'card-energy',
      label: 'Energy Consumed',
      value: `${totalEnergyKWh.toFixed(4)} kWh`,
      sub: 'idle hosts powered off',
      color: 'var(--accent-success)',
    },
    {
      id: 'card-host-time',
      label: 'Avg Host Time',
      value: `${avgHostActiveSeconds.toFixed(1)} s`,
      sub: 'measured active time per host',
      color: 'var(--text-secondary)',
    },
  ];

  return (
    <div className="section-grid cols-auto">
      {cards.map((c) => (
        <div className="card metric-card" key={c.id} id={c.id}>
          <span className="metric-label">{c.label}</span>
          <span className="metric-value" style={{ color: c.color }}>
            {c.value}
          </span>
          <span className="metric-sublabel">{c.sub}</span>
        </div>
      ))}
    </div>
  );
}
