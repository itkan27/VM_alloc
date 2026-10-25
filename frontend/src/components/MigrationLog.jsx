const MAX_ROWS = 200;

/**
 * MigrationLog
 * Live migrations, newest first: when, which VM, from → to, and why.
 */
export default function MigrationLog({ migrations }) {
  if (!migrations || migrations.length === 0) {
    return (
      <div className="empty-state">
        <span className="icon">⇄</span>
        <span>No migrations yet. Spiky VMs move to the Performance Zone when they spike.</span>
      </div>
    );
  }

  const rows = migrations.slice(-MAX_ROWS).reverse();

  return (
    <div className="table-wrapper migration-log" id="migration-log">
      <table>
        <thead>
          <tr>
            <th>Time (s)</th>
            <th>VM</th>
            <th>From</th>
            <th>To</th>
            <th>Trigger</th>
            <th>Took</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((m) => (
            <tr key={`${m.vmId}-${m.requestedAt}`} className="row-new">
              <td className="mono">{m.requestedAt.toFixed(1)}</td>
              <td className="mono">{m.vmId}</td>
              <td>
                <span className={`badge badge-${m.fromZone === 'PERFORMANCE' ? 'perf' : 'eco'}`}>{m.fromHostName}</span>
              </td>
              <td>
                <span className={`badge badge-${m.toZone === 'PERFORMANCE' ? 'perf' : 'eco'}`}>{m.toHostName}</span>
              </td>
              <td title={m.detail} style={{ fontSize: '0.78rem' }}>
                {m.trigger === 'SPIKE' ? (
                  <span style={{ color: 'var(--accent-spiky)' }}>⚡ {m.detail}</span>
                ) : (
                  <span style={{ color: 'var(--accent-success)' }}>↓ spike ended</span>
                )}
              </td>
              <td className="mono" style={{ color: 'var(--text-secondary)' }}>
                {(m.completedAt - m.requestedAt).toFixed(1)}s
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {migrations.length > MAX_ROWS && (
        <p className="text-muted mt-4">Showing the latest {MAX_ROWS} of {migrations.length} migrations.</p>
      )}
    </div>
  );
}
