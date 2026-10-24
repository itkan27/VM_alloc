/**
 * LiveStatus
 * Headline cards for a running live simulation: clock, placements so far,
 * hosts currently powered on, and a Stop button.
 */
export default function LiveStatus({ stream, onStop, stopping }) {
  const { status, simTime, placements, hosts } = stream;

  const placed = placements.filter((p) => p.allocationSuccess).length;
  const failed = placements.length - placed;
  const activeHosts = hosts.filter((h) => h.active);
  const perf = activeHosts.filter((h) => h.zone === 'PERFORMANCE').length;
  const running = status === 'running' || status === 'connecting';

  const cards = [
    {
      id: 'live-clock',
      label: 'Simulation Clock',
      value: formatClock(simTime),
      sub: running ? 'streaming live…' : status,
      color: 'var(--accent-primary)',
    },
    {
      id: 'live-placed',
      label: 'VMs Placed',
      value: placed,
      sub: `${failed} waiting / failed`,
      color: failed > 0 ? 'var(--accent-danger)' : 'var(--accent-success)',
    },
    {
      id: 'live-hosts',
      label: 'Hosts Powered On',
      value: activeHosts.length,
      sub: `${perf} perf · ${activeHosts.length - perf} eco`,
      color: 'var(--accent-stable)',
    },
  ];

  return (
    <div className="section-grid cols-auto">
      {cards.map((c) => (
        <div className="card metric-card" key={c.id} id={c.id}>
          <span className="metric-label">{c.label}</span>
          <span className="metric-value" style={{ color: c.color }}>{c.value}</span>
          <span className="metric-sublabel">{c.sub}</span>
        </div>
      ))}
      <div className="card metric-card" style={{ justifyContent: 'center' }}>
        <span className="metric-label">
          <span className={`status-dot ${running ? '' : 'offline'}`} /> Live session
        </span>
        <button
          type="button"
          className="btn btn-danger"
          onClick={onStop}
          disabled={!running || stopping}
          id="btn-stop-live"
        >
          {stopping ? 'Stopping…' : '■ Stop'}
        </button>
      </div>
    </div>
  );
}

function formatClock(seconds) {
  const s = Math.floor(seconds);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return `${h}:${String(m).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;
}
