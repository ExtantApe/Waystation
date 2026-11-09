package com.extantape.waystation;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * The hand-over, for a world whose own plugin takes players in (`worlds.<name>.hand-over: true`). Fired on the main
 * thread when a player walks through that world's gate, after every access check has passed and with their inventory
 * screen closed. Waystation moves nobody: the listener moves the player and calls {@code setHandled(true)}. If nobody
 * does, the player stays where they are and is told the world isn't open.
 */
public final class WaystationHandOverEvent extends PlayerEvent {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String gateId, destination;
    private boolean handled;

    WaystationHandOverEvent(Player player, String gateId, String destination) {
        super(player);
        this.gateId = gateId;
        this.destination = destination;
    }

    public String getGateId() { return gateId; }
    /** The world the gate leads to. */
    public String getDestination() { return destination; }
    public boolean isHandled() { return handled; }
    public void setHandled(boolean handled) { this.handled = handled; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
