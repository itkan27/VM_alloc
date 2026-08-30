"""
main.py  —  FastAPI application entry point for the VM classifier service.

Endpoints:
  POST /classify        → classify a single VM
  POST /classify/batch  → classify a batch of VMs
  GET  /health          → service + model health
"""

from contextlib import asynccontextmanager
import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from schemas import (
    ClassifyRequest, ClassifyResponse,
    BatchClassifyRequest, BatchClassifyResponse,
    HealthResponse,
)
import model_loader

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Load ONNX model on startup."""
    logger.info("Loading ONNX model…")
    model_loader.load_model()
    yield
    logger.info("Shutting down ML service.")


app = FastAPI(
    title="VM Classifier Service",
    description="MLP-based ONNX classifier — labels VMs as SPIKY or STABLE.",
    version="1.0.0",
    lifespan=lifespan,
)

# Allow requests from the Java backend and the React dev server
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:8080", "http://localhost:5173", "*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.post("/classify", response_model=ClassifyResponse)
def classify_vm(request: ClassifyRequest) -> ClassifyResponse:
    """Classify a single VM as SPIKY or STABLE."""
    vm_type, confidence = model_loader.predict(
        request.min_cpu, request.max_cpu, request.avg_cpu
    )
    return ClassifyResponse(
        vm_id=request.vm_id,
        vm_type=vm_type,
        confidence=confidence,
    )


@app.post("/classify/batch", response_model=BatchClassifyResponse)
def classify_batch(request: BatchClassifyRequest) -> BatchClassifyResponse:
    """Classify a batch of VMs in a single call."""
    results = []
    for vm in request.vms:
        vm_type, confidence = model_loader.predict(
            vm.min_cpu, vm.max_cpu, vm.avg_cpu
        )
        results.append(
            ClassifyResponse(vm_id=vm.vm_id, vm_type=vm_type, confidence=confidence)
        )
    return BatchClassifyResponse(results=results)


@app.get("/health", response_model=HealthResponse)
def health_check() -> HealthResponse:
    return HealthResponse(
        status="ok",
        model_loaded=model_loader.is_model_loaded(),
        model_path=model_loader.get_model_path(),
    )
