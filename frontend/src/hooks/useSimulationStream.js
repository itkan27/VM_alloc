import { useEffect, useReducer } from 'react';
import { sessionStreamUrl } from '../services/api';

const initialState = {
  status: 'idle', // idle | connecting | running | completed | stopped | failed
  hosts: [],
  placements: [],
  simTime: 0,
  result: null,
  error: null,
};

/** Insert or replace the placement row for a VM, keeping arrival order. */
function upsertPlacement(placements, row) {
  const i = placements.findIndex((p) => p.vmId === row.vmId);
  if (i === -1) return [...placements, row];
  const next = placements.slice();
  next[i] = row;
  return next;
}

function reducer(state, { type, data }) {
  switch (type) {
    case 'reset':
      return { ...initialState, status: 'connecting' };
    case 'init':
      return { ...state, status: 'running', hosts: data.hosts };
    case 'placement':
      return {
        ...state,
        placements: upsertPlacement(state.placements, data),
        hosts: state.hosts.map((h) => (h.hostId === data.host.hostId ? data.host : h)),
        simTime: data.simTime,
      };
    case 'placement_failed': {
      // The broker may still place the VM later; never overwrite a successful row
      const existing = state.placements.find((p) => p.vmId === data.vmId);
      if (existing?.allocationSuccess) return state;
      const row = { ...data, hostId: -1, hostName: 'Unallocated', zone: null, placementReason: null, allocationSuccess: false };
      return { ...state, placements: upsertPlacement(state.placements, row), simTime: data.simTime };
    }
    case 'host_update':
      return { ...state, hosts: data.hosts, simTime: data.simTime };
    case 'completed':
      return { ...state, status: data.status.toLowerCase(), result: data.result };
    case 'failed':
      return { ...state, status: 'failed', error: data?.error || 'Simulation failed' };
    default:
      return state;
  }
}

const EVENT_TYPES = ['init', 'placement', 'placement_failed', 'host_update', 'completed', 'failed'];

/**
 * Subscribes to a live simulation session over Server-Sent Events and keeps the
 * hosts, placements, clock and final result in sync with the backend.
 * Pass null to disconnect.
 */
export default function useSimulationStream(sessionId) {
  const [state, dispatch] = useReducer(reducer, initialState);

  useEffect(() => {
    if (!sessionId) return undefined;
    dispatch({ type: 'reset' });

    const source = new EventSource(sessionStreamUrl(sessionId));
    const listeners = EVENT_TYPES.map((type) => {
      const listener = (e) => {
        const data = JSON.parse(e.data);
        dispatch({ type, data });
        if (type === 'completed' || type === 'failed') source.close();
      };
      source.addEventListener(type, listener);
      return [type, listener];
    });

    // On transient errors EventSource reconnects by itself (sending Last-Event-ID);
    // only report a failure once the browser has given up.
    source.onerror = () => {
      if (source.readyState === EventSource.CLOSED) {
        dispatch({ type: 'failed', data: { error: 'Lost connection to the simulation stream.' } });
      }
    };

    return () => {
      listeners.forEach(([type, listener]) => source.removeEventListener(type, listener));
      source.close();
    };
  }, [sessionId]);

  return state;
}
