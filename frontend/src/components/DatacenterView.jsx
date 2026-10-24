import { useEffect, useLayoutEffect, useRef, useState } from 'react';

const ARROW_MS = 1800;

/**
 * DatacenterView
 * Live picture of the datacenter: Performance and Eco hosts with the VMs running on
 * them right now. Spiking VMs pulse, and every completed migration draws an arrow
 * from the old host to the new one.
 */
export default function DatacenterView({ hosts, vmTypes, spiking, migrations }) {
  const containerRef = useRef(null);
  const hostRefs = useRef(new Map());
  const animatedCount = useRef(0);
  const [arrows, setArrows] = useState([]);

  // Draw an arrow for each migration that arrived since the last render
  useLayoutEffect(() => {
    const container = containerRef.current;
    if (!container || migrations.length <= animatedCount.current) return;

    const box = container.getBoundingClientRect();
    const centre = (hostId) => {
      const el = hostRefs.current.get(hostId);
      if (!el) return null;
      const r = el.getBoundingClientRect();
      return { x: r.left - box.left + r.width / 2, y: r.top - box.top + r.height / 2 };
    };

    const fresh = migrations.slice(animatedCount.current).flatMap((m, i) => {
      const from = centre(m.fromHostId);
      const to = centre(m.toHostId);
      if (!from || !to) return [];
      return [{ id: `${animatedCount.current + i}-${m.vmId}`, from, to, vmId: m.vmId, toPerf: m.toZone === 'PERFORMANCE' }];
    });
    animatedCount.current = migrations.length;
    if (fresh.length === 0) return;

    setArrows((prev) => [...prev, ...fresh]);
    const ids = new Set(fresh.map((a) => a.id));
    setTimeout(() => setArrows((prev) => prev.filter((a) => !ids.has(a.id))), ARROW_MS);
  }, [migrations]);

  // Forget arrows from a previous session
  useEffect(() => {
    if (migrations.length === 0) {
      animatedCount.current = 0;
      setArrows([]);
    }
  }, [migrations.length]);

  const zones = [
    { zone: 'PERFORMANCE', title: 'Performance Zone', hint: 'empty until a spiky VM crosses a threshold' },
    { zone: 'ECO', title: 'Eco Zone', hint: 'every VM starts here · one spiky VM per host' },
  ];

  return (
    <div className="dc-view" ref={containerRef}>
      {zones.map(({ zone, title, hint }) => (
        <div key={zone} className={`dc-zone dc-zone-${zone === 'PERFORMANCE' ? 'perf' : 'eco'}`}>
          <div className="dc-zone-title">
            {title} <span className="text-muted">· {hint}</span>
          </div>
          <div className="dc-hosts">
            {hosts.filter((h) => h.zone === zone).map((h) => {
              const load = h.cpuUtilization;
              return (
                <div
                  key={h.hostId}
                  ref={(el) => (el ? hostRefs.current.set(h.hostId, el) : hostRefs.current.delete(h.hostId))}
                  className={`dc-host ${h.active ? '' : 'dc-host-off'} ${load > 1 ? 'dc-host-overload' : ''}`}
                >
                  <div className="flex-between">
                    <span className="dc-host-name">{h.hostName}</span>
                    <span className="dc-host-load">{h.active ? `${(load * 100).toFixed(0)}%` : 'off'}</span>
                  </div>
                  <div className="dc-load-bar">
                    <div style={{ width: `${Math.min(load, 1) * 100}%` }} />
                  </div>
                  <div className="dc-chips">
                    {(h.vmIds || []).map((id) => (
                      <span
                        key={id}
                        className={`dc-chip ${vmTypes[id] === 'SPIKY' ? 'dc-chip-spiky' : 'dc-chip-stable'} ${spiking[id] ? 'dc-chip-spiking' : ''}`}
                        title={spiking[id] ? `${id} is spiking` : id}
                      >
                        {spiking[id] ? '⚡' : ''}{id}
                      </span>
                    ))}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      ))}

      <svg className="dc-arrows" aria-hidden="true">
        <defs>
          <marker id="dc-arrowhead-perf" markerWidth="8" markerHeight="8" refX="6" refY="4" orient="auto">
            <path d="M0,0 L8,4 L0,8 z" fill="#f97316" />
          </marker>
          <marker id="dc-arrowhead-eco" markerWidth="8" markerHeight="8" refX="6" refY="4" orient="auto">
            <path d="M0,0 L8,4 L0,8 z" fill="#22c55e" />
          </marker>
        </defs>
        {arrows.map((a) => {
          const midX = (a.from.x + a.to.x) / 2;
          const midY = Math.min(a.from.y, a.to.y) - 30;
          return (
            <g key={a.id} className="dc-arrow">
              <title>{a.vmId}</title>
              <path
                d={`M${a.from.x},${a.from.y} Q${midX},${midY} ${a.to.x},${a.to.y}`}
                stroke={a.toPerf ? '#f97316' : '#22c55e'}
                markerEnd={`url(#dc-arrowhead-${a.toPerf ? 'perf' : 'eco'})`}
              />
            </g>
          );
        })}
      </svg>
    </div>
  );
}
