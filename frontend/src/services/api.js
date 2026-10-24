import axios from 'axios';

// Relative base URL: the Vite dev server proxies /api to the Java backend (:8080)
export const API_BASE = '/api';

const API = axios.create({
  baseURL: API_BASE,
  headers: { 'Content-Type': 'application/json' },
});

/**
 * Run a one-shot batch VM placement simulation.
 * @param {Array} vms - array of VmRequest objects
 * @returns {Promise} SimulationResult
 */
export const runSimulation = (vms) => API.post('/simulation/run', vms).then((r) => r.data);

/**
 * Start a live (streamed) simulation.
 * @param {Array} vms - array of VmRequest objects
 * @param {{timeScale?: number, arrivalGapSeconds?: number}} options
 * @returns {Promise<string>} sessionId
 */
export const startLiveSimulation = (vms, options = {}) =>
  API.post('/simulation/sessions', { vms, ...options }).then((r) => r.data.sessionId);

/** Stop a running live simulation. */
export const stopLiveSimulation = (sessionId) =>
  API.post(`/simulation/sessions/${sessionId}/stop`).then((r) => r.data);

/** URL of a session's Server-Sent Events stream (for EventSource). */
export const sessionStreamUrl = (sessionId) => `${API_BASE}/simulation/sessions/${sessionId}/stream`;

/**
 * Ping the Java backend to verify it is reachable.
 */
export const pingBackend = () => API.get('/simulation/ping').then((r) => r.data);
