import axios from 'axios';

const API = axios.create({
  baseURL: 'http://localhost:8080/api',
  headers: { 'Content-Type': 'application/json' },
});

/**
 * Run a one-shot batch VM placement simulation.
 * @param {Array} vms - array of VmRequest objects
 * @returns {Promise} SimulationResult
 */
export const runSimulation = (vms) => API.post('/simulation/run', vms).then((r) => r.data);

/**
 * Ping the Java backend to verify it is reachable.
 */
export const pingBackend = () => API.get('/simulation/ping').then((r) => r.data);
