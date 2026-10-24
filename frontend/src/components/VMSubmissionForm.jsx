import { useState } from 'react';

const DEFAULT_VM = {
  vmId: '',
  minCpu: 15,
  maxCpu: 30,
  avgCpu: 25,
  mips: 1000,
  numCores: 4,
  ram: 8192,
  bandwidth: 1000,
  duration: 3600,
};

let vmCounter = 1;

function generateId() {
  return `vm-${String(vmCounter++).padStart(3, '0')}`;
}

/**
 * VMSubmissionForm
 * Lets the user build a list of VMs and submit them for simulation, either as an
 * instant batch or as a live run streamed to the dashboard.
 */
export default function VMSubmissionForm({ onSubmit, loading }) {
  const [vms, setVms] = useState([{ ...DEFAULT_VM, vmId: generateId() }]);
  const [mode, setMode] = useState('batch'); // batch | live
  const [timeScale, setTimeScale] = useState(120);
  const [arrivalGapSeconds, setArrivalGapSeconds] = useState(30);

  function addVm() {
    setVms((prev) => [...prev, { ...DEFAULT_VM, vmId: generateId() }]);
  }

  function removeVm(idx) {
    setVms((prev) => prev.filter((_, i) => i !== idx));
  }

  function updateVm(idx, field, value) {
    setVms((prev) =>
      prev.map((vm, i) =>
        i === idx ? { ...vm, [field]: field === 'vmId' ? value : Number(value) } : vm
      )
    );
  }

  function handleSubmit(e) {
    e.preventDefault();
    onSubmit(vms, { mode, timeScale, arrivalGapSeconds });
  }

  const fields = [
    { key: 'minCpu',    label: 'Min CPU %',    min: 0,  max: 100, step: 1  },
    { key: 'maxCpu',    label: 'Max CPU %',    min: 0,  max: 100, step: 1  },
    { key: 'avgCpu',    label: 'Avg CPU %',    min: 0,  max: 100, step: 1  },
    { key: 'mips',      label: 'MIPS',         min: 100,max: 50000, step: 100 },
    { key: 'numCores',  label: 'Cores',        min: 1,  max: 64,  step: 1  },
    { key: 'ram',       label: 'RAM (MB)',      min: 128,max: 131072, step: 128 },
    { key: 'bandwidth', label: 'BW (Mbps)',     min: 10, max: 100000, step: 10 },
    { key: 'duration',  label: 'Duration (s)', min: 60, max: 86400, step: 60 },
  ];

  return (
    <form onSubmit={handleSubmit}>
      <div className="flex-between mb-4">
        <div>
          <h2 style={{ fontSize: '1rem', fontWeight: 700, color: 'var(--text-primary)' }}>
            Virtual Machine Batch
          </h2>
          <p className="text-muted">Configure VMs to place. Min/Max/Avg CPU drives classification.</p>
        </div>
        <div className="flex gap-3 flex-center">
          <div className="mode-toggle" role="group" aria-label="Simulation mode">
            <button
              type="button"
              className={mode === 'batch' ? 'active' : ''}
              onClick={() => setMode('batch')}
              id="btn-mode-batch"
            >
              Instant
            </button>
            <button
              type="button"
              className={mode === 'live' ? 'active' : ''}
              onClick={() => setMode('live')}
              id="btn-mode-live"
            >
              Live
            </button>
          </div>
          <button type="button" className="btn btn-ghost" onClick={addVm} id="btn-add-vm">
            + Add VM
          </button>
          <button
            type="submit"
            className="btn btn-primary"
            disabled={loading || vms.length === 0}
            id="btn-run-simulation"
          >
            {loading
              ? <><span className="spinner" /> Running…</>
              : mode === 'live' ? '▶ Start Live Simulation' : '▶ Run Simulation'}
          </button>
        </div>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
        {/* Header row */}
        <div className="vm-row" style={{ borderColor: 'transparent', paddingBottom: '0.25rem', opacity: 0.6 }}>
          <span className="input-label">VM ID</span>
          {fields.map((f) => <span key={f.key} className="input-label">{f.label}</span>)}
          <span />
        </div>

        {vms.map((vm, idx) => (
          <div className="vm-row" key={idx}>
            {/* VM ID */}
            <div className="input-group">
              <input
                id={`vm-id-${idx}`}
                className="input-field"
                type="text"
                value={vm.vmId}
                onChange={(e) => updateVm(idx, 'vmId', e.target.value)}
                placeholder="vm-001"
                required
              />
            </div>

            {/* Numeric fields */}
            {fields.map((f) => (
              <div className="input-group" key={f.key}>
                <input
                  id={`vm-${idx}-${f.key}`}
                  className="input-field"
                  type="number"
                  min={f.min}
                  max={f.max}
                  step={f.step}
                  value={vm[f.key]}
                  onChange={(e) => updateVm(idx, f.key, e.target.value)}
                  required
                />
              </div>
            ))}

            {/* Remove */}
            <button
              type="button"
              className="btn btn-danger"
              id={`btn-remove-vm-${idx}`}
              onClick={() => removeVm(idx)}
              disabled={vms.length === 1}
              style={{ padding: '0.55rem 0.85rem' }}
              title="Remove VM"
            >
              ✕
            </button>
          </div>
        ))}
      </div>

      {mode === 'live' && (
        <div className="flex gap-4 mt-4" id="live-options">
          <div className="input-group" style={{ maxWidth: 220 }}>
            <label className="input-label" htmlFor="live-time-scale">Speed (sim s / real s)</label>
            <input
              id="live-time-scale"
              className="input-field"
              type="number"
              min={1}
              max={100000}
              step="any"
              value={timeScale}
              onChange={(e) => setTimeScale(Number(e.target.value))}
              required
            />
          </div>
          <div className="input-group" style={{ maxWidth: 220 }}>
            <label className="input-label" htmlFor="live-arrival-gap">VM arrival gap (sim s)</label>
            <input
              id="live-arrival-gap"
              className="input-field"
              type="number"
              min={0}
              max={86400}
              step="any"
              value={arrivalGapSeconds}
              onChange={(e) => setArrivalGapSeconds(Number(e.target.value))}
              required
            />
          </div>
        </div>
      )}

      <p className="text-muted mt-4">
        {vms.length} VM{vms.length !== 1 ? 's' : ''} queued for simulation
        {mode === 'live' && ' · placements stream to the dashboard as they happen'}
      </p>
    </form>
  );
}
