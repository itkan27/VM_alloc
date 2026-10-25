package com.vmalloc.simulation;

import com.vmalloc.model.VmType;
import com.vmalloc.model.Zone;
import org.cloudsimplus.allocationpolicies.VmAllocationPolicyAbstract;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSuitability;
import org.cloudsimplus.vms.Vm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Stream;

/**
 * Base class for every allocation policy in the project.
 *
 * It owns the {@link HostLedger}, the host → zone map and the per-VM profiles, so all
 * policies share identical capacity accounting and only differ in
 * {@link #selectHost(VmProfile, Vm)}.
 *
 * Booking lifecycle:
 *   1. defaultFindHostForVm() only CHOOSES a host (no side effects).
 *   2. allocateHostForVm(vm, host) books the ledger once CloudSim confirms the VM was created.
 *   3. deallocateHostForVm(vm) releases it when the VM finishes or leaves the host.
 * A live migration is a deallocate on the source followed by an allocate on the
 * target, so the ledger follows migrating VMs automatically.
 *
 * Candidate hosts must also pass CloudSim's own suitability check, so CloudSim never
 * rejects a host the policy picked.
 */
public abstract class TrackedAllocationPolicy extends VmAllocationPolicyAbstract {

    private static final Logger log = LoggerFactory.getLogger(TrackedAllocationPolicy.class);

    /** Host chosen by a policy together with the rule that chose it */
    protected record Selection(Host host, String reason) {}

    /** A VM's initial placement, kept after the VM moves or finishes for result reporting */
    public record Placement(Host host, Zone zone, String reason, double placedAt) {}

    protected final HostLedger ledger = new HostLedger();

    private final Map<Vm, VmProfile> profiles = new IdentityHashMap<>();
    private final Map<Host, Zone> zones = new IdentityHashMap<>();
    private final Map<Vm, String> pendingReasons = new IdentityHashMap<>();
    private final Map<Vm, Host> bookedHosts = new IdentityHashMap<>();
    private final Map<Vm, Placement> placements = new IdentityHashMap<>();
    private PlacementListener placementListener = PlacementListener.NOOP;
    private long placementNanos;
    private int placementDecisions;

    // ─── Registration API (called by CloudSimRunner before simulation.start()) ──

    public void register(Vm vm, VmProfile profile) {
        profiles.put(vm, profile);
    }

    public void setZone(Host host, Zone zone) {
        zones.put(host, zone);
    }

    public void setPlacementListener(PlacementListener listener) {
        this.placementListener = listener;
    }

    // ─── Policy-specific host selection ──────────────────────────────────────

    protected abstract Optional<Selection> selectHost(VmProfile vm, Vm cloudSimVm);

    // ─── CloudSim hooks ──────────────────────────────────────────────────────

    @Override
    public Optional<Host> defaultFindHostForVm(final Vm vm) {
        VmProfile profile = profileOf(vm);
        long start = System.nanoTime();
        Optional<Selection> selection = selectHost(profile, vm);
        placementNanos += System.nanoTime() - start;
        placementDecisions++;

        selection.ifPresentOrElse(
                s -> pendingReasons.put(vm, s.reason()),
                () -> log.debug("No host found for {} ({}, avg={} MIPS)",
                        profile.vmId(), profile.type(),
                        String.format("%.0f", profile.avgDemandMips())));

        return selection.map(Selection::host);
    }

    @Override
    public HostSuitability allocateHostForVm(final Vm vm, final Host host) {
        HostSuitability suitability = super.allocateHostForVm(vm, host);
        if (!suitability.fully()) return suitability;

        VmProfile profile = profileOf(vm);
        ledger.book(host, profile);
        bookedHosts.put(vm, host);

        // A VM that already has a placement is arriving through a live migration
        if (placements.containsKey(vm)) return suitability;

        String reason = Objects.requireNonNullElse(pendingReasons.remove(vm), "DIRECT");
        Placement placement = new Placement(host, zoneOf(host), reason, vm.getSimulation().clock());
        placements.put(vm, placement);

        log.debug("{} ({}) -> Host-{} [{}] via {} | avg {}/{} MIPS",
                profile.vmId(), profile.type(), host.getId(), zoneOf(host), reason,
                String.format("%.0f", ledger.avgMips(host)), (long) host.getTotalMipsCapacity());
        placementListener.onPlaced(vm, profile, placement);
        return suitability;
    }

    @Override
    public void deallocateHostForVm(final Vm vm) {
        super.deallocateHostForVm(vm);
        Host host = bookedHosts.remove(vm);
        if (host != null) ledger.release(host, profileOf(vm));
    }

    // ─── Accessors ───────────────────────────────────────────────────────────

    public HostLedger getLedger() {
        return ledger;
    }

    public Zone zoneOf(Host host) {
        return zones.getOrDefault(host, Zone.ECO);
    }

    /** Wall-clock time spent choosing hosts for VMs (ms), including broker retries */
    public double totalPlacementTimeMs() {
        return placementNanos / 1e6;
    }

    /** Mean wall-clock time per host-selection decision (ms) */
    public double avgPlacementTimeMs() {
        return placementDecisions == 0 ? 0.0 : totalPlacementTimeMs() / placementDecisions;
    }

    public Optional<Placement> placementOf(Vm vm) {
        return Optional.ofNullable(placements.get(vm));
    }

    public VmProfile profileOf(Vm vm) {
        VmProfile profile = profiles.get(vm);
        if (profile != null) return profile;
        return new VmProfile(vm.getDescription(), VmType.STABLE, vm.getTotalMipsCapacity(), 100, 100,
                vm.getRam().getCapacity(), vm.getBw().getCapacity(), 0);
    }

    // ─── Helpers for subclasses ──────────────────────────────────────────────

    /** Hosts (in host-list order) with room for the VM's RAM/BW that CloudSim would accept */
    protected Stream<Host> candidates(VmProfile profile, Vm vm) {
        return getHostList().stream()
                .filter(h -> ledger.fitsRamAndBw(h, profile))
                .filter(h -> h.isSuitableForVm(vm));
    }

    protected Stream<Host> candidates(VmProfile profile, Vm vm, Zone zone) {
        return candidates(profile, vm).filter(h -> zoneOf(h) == zone);
    }
}
