package com.vmalloc.service;

import com.vmalloc.model.ClassificationResult;
import com.vmalloc.model.VmRequest;
import com.vmalloc.model.VmType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * VmClassifierClient
 * ──────────────────
 * Calls the Python FastAPI ML service to classify a batch of VMs.
 *
 * Falls back to the heuristic stub (high CPU range → SPIKY) if the ML
 * service is unreachable, so the rest of the pipeline is never blocked.
 *
 * Configuration (application.properties):
 *   ml.service.url = http://localhost:8000
 */
@Service
public class VmClassifierClient {

    private static final Logger log = LoggerFactory.getLogger(VmClassifierClient.class);

    @Value("${ml.service.url:http://localhost:8000}")
    private String mlServiceUrl;

    private final RestTemplate restTemplate;

    public VmClassifierClient() {
        this.restTemplate = new RestTemplate();
    }

    /**
     * Classifies all VMs in the request list as a single batch call.
     *
     * @return map from vmId → ClassificationResult
     */
    public Map<String, ClassificationResult> classifyBatch(List<VmRequest> requests) {
        // Model part commented out for this phase
        /*
        try {
            return callMlService(requests);
        } catch (RestClientException e) {
            log.warn("ML service unreachable ({}). Using heuristic fallback.", e.getMessage());
            return fallbackClassify(requests);
        }
        */
        // log.info("Using heuristic fallback for classification (ML service disabled for this phase).");
        return fallbackClassify(requests);
    }

    // ── ML service call ──────────────────────────────────────────────────────

    private Map<String, ClassificationResult> callMlService(List<VmRequest> requests) {
        String url = mlServiceUrl + "/classify/batch";

        // Build request body matching the Python BatchClassifyRequest schema
        Map<String, Object> body = new HashMap<>();
        List<Map<String, Object>> vms = requests.stream()
                .map(r -> {
                    Map<String, Object> vm = new HashMap<>();
                    vm.put("vm_id", r.vmId());
                    vm.put("min_cpu", r.minCpu());
                    vm.put("max_cpu", r.maxCpu());
                    vm.put("avg_cpu", r.avgCpu());
                    return vm;
                })
                .toList();
        body.put("vms", vms);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.postForObject(url, body, Map.class);

        Map<String, ClassificationResult> results = new LinkedHashMap<>();
        if (response != null && response.containsKey("results")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("results");
            for (Map<String, Object> item : items) {
                String vmId = (String) item.get("vm_id");
                VmType type = VmType.valueOf((String) item.get("vm_type"));
                double confidence = ((Number) item.get("confidence")).doubleValue();
                results.put(vmId, new ClassificationResult(vmId, type, confidence));
            }
        }
        return results;
    }

    // ── Heuristic fallback ───────────────────────────────────────────────────

    /**
     * Same logic as the Python stub: high CPU range AND low avg → SPIKY.
     */
    private Map<String, ClassificationResult> fallbackClassify(List<VmRequest> requests) {
        Map<String, ClassificationResult> results = new LinkedHashMap<>();
        for (VmRequest r : requests) {
            double range = r.maxCpu() - r.minCpu();
            VmType type;
            double confidence;
            if (range > 40.0 && r.avgCpu() < r.maxCpu() * 0.6) {
                confidence = Math.min(0.5 + (range - 40.0) / 100.0, 0.95);
                type = VmType.SPIKY;
            } else {
                confidence = Math.min(0.5 + (40.0 - range) / 100.0, 0.95);
                type = VmType.STABLE;
            }
            results.put(r.vmId(), new ClassificationResult(r.vmId(), type, confidence));
        }
        return results;
    }
}
