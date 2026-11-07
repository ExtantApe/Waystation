package com.extantape.waystation;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * The hook for other plugins: fired on the main thread whenever a player is checked against a gate,
 * both to see it and to walk through it. Cancel it to say no. It can only narrow access: a player
 * Multiverse has already refused never reaches this event.
 *
 * It fires about once a second per player per gate in their world, so keep listeners quick.
 */
public final class WaystationAccessEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String gateId, gateWorld, destination, worldGroup;
    private boolean cancelled;

    WaystationAccessEvent(Player player, String gateId, String gateWorld, String destination, String worldGroup) {
        super(player);
        this.gateId = gateId;
        this.gateWorld = gateWorld;
        this.destination = destination;
        this.worldGroup = worldGroup;
    }

    public String getGateId() { return gateId; }
    public String getGateWorld() { return gateWorld; }
    public String getDestination() { return destination; }
    public String getWorldGroup() { return worldGroup; }
    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { cancelled = cancel; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
