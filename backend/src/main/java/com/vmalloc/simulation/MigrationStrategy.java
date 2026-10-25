package com.vmalloc.simulation;

/**
 * Decides which VMs to live-migrate. Called by {@link SpikeMigrationController} every
 * clock tick after VM loads have been updated; requests migrations through
 * {@link SpikeMigrationController#migrate}.
 */
public interface MigrationStrategy {

    void onTick(double now, SpikeMigrationController controller);
}
