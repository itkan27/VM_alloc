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
 *
 * Candidate hosts must also pass CloudSim's own suitability check, so CloudSim never
 * rejects a host the policy picked.
 */
public abstract class TrackedAllocationPolicy extends VmAllocationPolicyAbstract {

    private static final Logger log = LoggerFactory.getLogger(TrackedAllocationPolicy.class);

    /** Host chosen by a policy together with the rule that chose it */
    protected record Selection(Host host, String reason) {}

    /** Where a VM was booked, kept after the VM finishes for result reporting */
    public record Placement(Host host, Zone zone, String reason) {}

    protected final HostLedger ledger = new HostLedger();

    private final Map<Vm, VmProfile> profiles = new IdentityHashMap<>();
    private final Map<Host, Zone> zones = new IdentityHashMap<>();
    private final Map<Vm, String> pendingReasons = new IdentityHashMap<>();
    private final Map<Vm, Host> bookedHosts = new IdentityHashMap<>();
    private final Map<Vm, Placement> placements = new IdentityHashMap<>();
    private PlacementListener placementListener = PlacementListener.NOOP;

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
        Optional<Selection> selection = selectHost(profile, vm);

        selection.ifPresentOrElse(
                s -> pendingReasons.put(vm, s.reason()),
                () -> log.debug("No host found for {} ({}, avg={} MIPS, peak={} MIPS)",
                        profile.vmId(), profile.type(),
                        String.format("%.0f", profile.avgDemandMips()),
                        String.format("%.0f", profile.peakDemandMips())));

        return selection.map(Selection::host);
    }

    @Override
    public HostSuitability allocateHostForVm(final Vm vm, final Host host) {
        HostSuitability suitability = super.allocateHostForVm(vm, host);
        if (!suitability.fully()) return suitability;

        VmProfile profile = profileOf(vm);
        ledger.book(host, profile);
        bookedHosts.put(vm, host);
        String reason = Objects.requireNonNullElse(pendingReasons.remove(vm), "DIRECT");
        Placement placement = new Placement(host, zoneOf(host), reason);
        placements.put(vm, placement);

        double cap = host.getTotalMipsCapacity();
        log.debug("{} ({}) -> Host-{} [{}] via {} | avg {}/{} MIPS | peak-safe {}/{} MIPS",
                profile.vmId(), profile.type(), host.getId(), zoneOf(host), reason,
                String.format("%.0f", ledger.avgMips(host)), (long) cap,
                String.format("%.0f", ledger.guaranteedMips(host)), (long) cap);
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

    public Optional<Placement> placementOf(Vm vm) {
        return Optional.ofNullable(placements.get(vm));
    }

    public VmProfile profileOf(Vm vm) {
        VmProfile profile = profiles.get(vm);
        if (profile != null) return profile;
        double mips = vm.getTotalMipsCapacity();
        return new VmProfile(vm.getDescription(), VmType.STABLE, mips, mips,
                vm.getRam().getCapacity(), vm.getBw().getCapacity());
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
