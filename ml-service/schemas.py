from pydantic import BaseModel, Field


class ClassifyRequest(BaseModel):
    """
    Input features for MLP-based VM classification.
    vm_id  : unique identifier (passed through, not used as a model feature)
    min_cpu: minimum CPU usage percentage observed (0–100)
    max_cpu: maximum CPU usage percentage observed (0–100)
    avg_cpu: average CPU usage percentage observed (0–100)
    """
    vm_id: str = Field(..., description="Unique VM identifier")
    min_cpu: float = Field(..., ge=0.0, le=100.0, description="Min CPU %")
    max_cpu: float = Field(..., ge=0.0, le=100.0, description="Max CPU %")
    avg_cpu: float = Field(..., ge=0.0, le=100.0, description="Avg CPU %")


class ClassifyResponse(BaseModel):
    """
    Classification result returned to the Java backend.
    vm_type   : 'SPIKY' or 'STABLE'
    confidence: model confidence score (0.0–1.0)
    """
    vm_id: str
    vm_type: str          # "SPIKY" | "STABLE"
    confidence: float


class BatchClassifyRequest(BaseModel):
    """List of VMs to classify in one request."""
    vms: list[ClassifyRequest]


class BatchClassifyResponse(BaseModel):
    """Classification results for all submitted VMs."""
    results: list[ClassifyResponse]


class HealthResponse(BaseModel):
    status: str
    model_loaded: bool
    model_path: str | None
