const REASON_LABELS = {
  PERF_FIRST_FIT: 'Performance Zone, first host with peak headroom',
  PERF_WORST_FIT: 'Performance Zone, host with the most headroom',
  SPIKY_OVERFLOW_ECO: 'Performance Zone full: isolated on an Eco host with peak headroom',
  ECO_BEST_FIT: 'Eco Zone, best-fit (densest host that fits)',
  STABLE_OVERFLOW_PERF: 'Eco Zone full: placed on a Performance host without breaking peak headroom',
  SPIKY_FIRST_FIT: 'First host with capacity and no other spiky VM',
  STABLE_BEST_FIT: 'Best-fit (densest host that fits)',
};

/**
 * PlacementTable
 * Shows every VM placement result: ID, type badge, confidence, zone, host, status.
 * New rows fade in, so live placements appear one by one.
 */
export default function PlacementTable({ placements }) {
  if (!placements || placements.length === 0) {
    return (
      <div className="empty-state">
        <span className="icon">📋</span>
        <span>No placement results yet. Run a simulation above.</span>
      </div>
    );
  }

  return (
    <div className="table-wrapper" id="placement-table">
      <table>
        <thead>
          <tr>
            <th>VM ID</th>
            <th>CloudSim ID</th>
            <th>Type</th>
            <th>Confidence</th>
            <th>Zone</th>
            <th>Host</th>
            <th>Status</th>
          </tr>
        </thead>
        <tbody>
          {placements.map((p) => (
            <tr key={p.vmId} className="row-new">
              <td>
                <span className="mono">{p.vmId}</span>
              </td>
              <td>
                <span className="mono" style={{ color: 'var(--text-secondary)' }}>
                  #{p.cloudSimVmId}
                </span>
              </td>
              <td>
                <span className={`badge badge-${p.vmType === 'SPIKY' ? 'spiky' : 'stable'}`}>
                  {p.vmType === 'SPIKY' ? '⚡' : '●'} {p.vmType}
                </span>
              </td>
              <td>
                <div className="flex flex-center gap-2">
                  <div className="progress-bar-wrap" style={{ width: 70 }}>
                    <div
                      className="progress-bar-fill"
                      style={{
                        width: `${(p.confidence * 100).toFixed(0)}%`,
                        background: p.vmType === 'SPIKY'
                          ? 'linear-gradient(90deg,var(--accent-spiky),#fb923c)'
                          : 'linear-gradient(90deg,var(--accent-primary),var(--accent-stable))',
                      }}
                    />
                  </div>
                  <span style={{ fontSize: '0.78rem', color: 'var(--text-secondary)', minWidth: 38 }}>
                    {(p.confidence * 100).toFixed(1)}%
                  </span>
                </div>
              </td>
              <td>
                {p.zone ? (
                  <span
                    className={`badge badge-${p.zone === 'PERFORMANCE' ? 'perf' : 'eco'}`}
                    title={REASON_LABELS[p.placementReason] || p.placementReason}
                  >
                    {p.zone === 'PERFORMANCE' ? 'Performance' : 'Eco'}
                    {p.placementReason?.includes('OVERFLOW') ? ' ↪' : ''}
                  </span>
                ) : (
                  <span style={{ color: 'var(--text-muted)' }}>—</span>
                )}
              </td>
              <td>
                {p.allocationSuccess ? (
                  <span style={{ color: 'var(--accent-stable)', fontWeight: 600 }}>
                    {p.hostName}
                  </span>
                ) : (
                  <span style={{ color: 'var(--text-muted)' }}>—</span>
                )}
              </td>
              <td>
                <span className={`badge badge-${p.allocationSuccess ? 'success' : 'danger'}`}>
                  {p.allocationSuccess ? '✓ Placed' : '✗ Failed'}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
