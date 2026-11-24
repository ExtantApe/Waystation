package com.extantape.waycave;

/**
 * Where someone arrives in the cave (D194, the owner's answer: "End of their last trail"): one step in front of the gate
 * they last walked into, looking into the cave. When that spot is gone or isn't safe, they arrive in the start chamber.
 * Nobody ever arrives at a gate they can't use.
 */
final class CaveArrival {
    private CaveArrival() {}

    /** Where to put them. */
    enum Spot { LAST_TRAIL, START }

    /**
     * @param remembered they have a last gate on record
     * @param gateStillThere that gate still exists, in the cave, and leads to a world on this server
     * @param canUse they can still use it (access, hooks and the gate being open)
     * @param frontSafe the spot in front of it is solid ground with room and nothing harmful, inside the cave
     */
    static Spot choose(boolean remembered, boolean gateStillThere, boolean canUse, boolean frontSafe) {
        return remembered && gateStillThere && canUse && frontSafe ? Spot.LAST_TRAIL : Spot.START;
    }
}
