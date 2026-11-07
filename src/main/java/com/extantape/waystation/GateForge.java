package com.extantape.waystation;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Designs gates from parts, from a seed, so the same seed always gives the same gate. It only does
 * geometry: a gate is a short list of boxes, each one display entity, each with a role that the
 * renderer turns into a block. Coordinates: "a" along the gate's plane from its centre, "v" up from
 * the floor, "d" across it.
 *
 * A station gate is shape x thickness x base x crown x ornament x opening x glass colour. Gates on a
 * platform share a shape, thickness, base and colour, and differ in crown, ornament and opening. Nothing
 * here looks like a nether or End portal: no solid purple or black opening, no black frame.
 */
final class GateForge {
    enum Role { FRAME, ACCENT, OPENING, GLOW }
    record Part(float a0, float a1, float v0, float v1, float d0, float d1, Role role, boolean spin) {}
    record Blueprint(List<Part> parts, String recipe, int glass, float top) {}

    static final String[] SHAPES = {"doorway", "arch", "ring", "obelisk", "temple", "gothic", "lozenge", "towers", "monolith"};
    static final String[] WORLD_SHAPES = {"doorway", "arch", "gothic", "lozenge"};
    static final String[] CROWNS = {"none", "spikes", "orb", "horns", "steps", "antennae", "halo"};
    static final String[] BASES = {"none", "plinth", "stairs", "feet"};
    static final String[] ORNAMENTS = {"none", "shards", "lanterns", "buttress"};
    static final String[] OPENINGS = {"pane", "bars", "louvres", "lattice", "inset", "shards"};
    static final float[] THICKNESS = {0.2f, 0.3f, 0.45f};
    /** Stained glass colours, deliberately without purple, magenta or black. */
    static final String[] GLASS = {"light_blue", "cyan", "lime", "green", "yellow", "orange", "red", "pink", "white", "light_gray", "blue", "brown"};
    private static final int MOST_PARTS = 26;

    /** A generator for this seed. Mixed first, since Java's Random gives close seeds close results. */
    static Random random(long seed) {
        long z = seed + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return new Random(z ^ (z >>> 31));
    }

    private final List<Part> parts = new ArrayList<>();
    private final float w, h;

    private GateForge(float w, float h) {
        this.w = w;
        this.h = h;
    }

    /** A gate for the station. A forced shape, like "toy" for the kids, overrides the seed's. */
    static Blueprint station(long platformSeed, long gateSeed, float w, float h, String forcedShape) {
        Random family = random(platformSeed), own = random(gateSeed);
        String shape = forcedShape != null ? forcedShape : pick(family, SHAPES);
        float f = THICKNESS[family.nextInt(THICKNESS.length)];
        String base = pick(family, BASES);
        int glass = family.nextInt(GLASS.length);
        String crown = pick(own, CROWNS), ornament = pick(own, ORNAMENTS), opening = pick(own, OPENINGS);
        GateForge g = new GateForge(w, h);
        g.opening(opening, own);
        g.base(base);
        g.shape(shape, f);
        g.crown(crown, g.frameTop(), own);
        if (g.parts.size() < MOST_PARTS - 6) g.ornament(ornament, own);
        return g.done(String.join(" ", shape, "t" + f, base, crown, ornament, opening, GLASS[glass]), glass);
    }

    /**
     * A gate out in a world. Always a slim frame, a lattice and a floating lantern diamond, so anyone
     * can recognise one at a glance. Only the frame's outline varies, unless standard is asked for.
     */
    static Blueprint world(long seed, float w, float h, boolean standard) {
        Random r = random(seed);
        String shape = standard ? "doorway" : pick(r, WORLD_SHAPES);
        GateForge g = new GateForge(w, h);
        g.opening("lattice", r);
        g.shape(shape, 0.2f);
        float top = g.frameTop();
        g.add(-0.25f, 0.25f, top + 0.45f, top + 0.95f, -0.25f, 0.25f, Role.GLOW, true);
        return g.done(shape, 2);
    }

    /** A fargate: one fixed design, in hazard stripes, so it's never mistaken for a waygate. */
    static Blueprint fargate(float w, float h) {
        GateForge g = new GateForge(w, h);
        g.opening("bars", new Random(0));
        float band = 0.6f;
        int i = 0;
        for (float v = 0; v < h + 0.6f; v += band) {
            Role stripe = i++ % 2 == 0 ? Role.FRAME : Role.ACCENT;
            g.add(-w - 0.45f, -w, v, v + band, -0.3f, 0.3f, stripe, false);
            g.add(w, w + 0.45f, v, v + band, -0.3f, 0.3f, stripe, false);
        }
        g.add(-w, w, h, h + 0.5f, -0.3f, 0.3f, Role.ACCENT, false);
        g.add(-0.3f, 0.3f, h + 0.75f, h + 1.35f, -0.3f, 0.3f, Role.GLOW, true);
        return g.done("fargate", 6);
    }

    /** The station's centrepiece: a tapering stack with a ring of shards. Its height comes from the seed. */
    static Blueprint monument(long seed) {
        Random r = random(seed);
        GateForge g = new GateForge(1, 1);
        int tiers = 3 + r.nextInt(3);
        float size = 1.2f + r.nextFloat() * 0.6f, v = 0;
        g.add(-size - 0.5f, size + 0.5f, 0, 0.3f, -size - 0.5f, size + 0.5f, Role.ACCENT, false);
        v = 0.3f;
        for (int i = 0; i < tiers; i++) {
            float tall = 0.8f + r.nextFloat() * 0.9f;
            g.add(-size, size, v, v + tall, -size, size, i % 2 == 0 ? Role.FRAME : Role.ACCENT, false);
            v += tall;
            size *= 0.72f;
        }
        g.add(-0.4f, 0.4f, v + 0.5f, v + 1.3f, -0.4f, 0.4f, Role.GLOW, true);
        int shards = 3 + r.nextInt(3);
        for (int i = 0; i < shards; i++) {
            double angle = i * 2 * Math.PI / shards;
            float x = (float) Math.cos(angle) * 2.8f, z = (float) Math.sin(angle) * 2.8f, up = v * 0.5f + r.nextFloat();
            g.add(x - 0.2f, x + 0.2f, up, up + 0.4f, z - 0.2f, z + 0.2f, Role.GLOW, true);
        }
        return g.done("monument", 0);
    }

    private Blueprint done(String recipe, int glass) {
        float top = 0;
        for (Part p : parts) top = Math.max(top, p.v1());
        return new Blueprint(List.copyOf(parts), recipe, glass, top);
    }

    private void add(float a0, float a1, float v0, float v1, float d0, float d1, Role role, boolean spin) {
        parts.add(new Part(Math.min(a0, a1), Math.max(a0, a1), v0, v1, d0, d1, role, spin));
    }

    private void frame(float a0, float a1, float v0, float v1, float depth) {
        add(a0, a1, v0, v1, -depth, depth, Role.FRAME, false);
    }

    private float frameTop() {
        float top = h;
        for (Part p : parts) if (p.role() != Role.OPENING) top = Math.max(top, p.v1());
        return top;
    }

    private void shape(String shape, float f) {
        float t = Math.max(f / 2, 0.1f);
        switch (shape) {
            case "arch" -> {
                frame(-w - f, -w, 0, h, t);
                frame(w, w + f, 0, h, t);
                frame(-w - f, -w + 0.5f, h, h + f, t);
                frame(w - 0.5f, w + f, h, h + f, t);
                frame(-w + 0.25f, w - 0.25f, h + f, h + 2 * f, t);
                add(-0.3f, 0.3f, h + 2 * f, h + 3 * f, -t - 0.1f, t + 0.1f, Role.ACCENT, false);
            }
            case "ring" -> {
                float c = 0.75f;
                frame(-w - f, -w, c, h - c, t);
                frame(w, w + f, c, h - c, t);
                frame(-w + c, w - c, h, h + f, t);
                frame(-w + c, w - c, 0, f, t);
                for (float s : new float[] {-1, 1}) {
                    float o0 = s < 0 ? -w - f : w - c, o1 = s < 0 ? -w + c : w + f;
                    frame(o0, o1, h - c, h - c + f, t);
                    frame(o0, o1, c - f, c, t);
                }
            }
            case "obelisk" -> {
                for (float s : new float[] {-1, 1}) {
                    frame(s * w, s * (w + 0.6f), 0, h - 0.5f, 0.3f);
                    frame(s * w, s * (w + 0.45f), h - 0.5f, h + 1f, 0.2f);
                    add(s * w, s * (w + 0.3f), h + 1f, h + 1.8f, -0.1f, 0.1f, Role.ACCENT, false);
                }
                add(-w, w, h + 0.1f, h + 0.3f, -t, t, Role.ACCENT, false);
            }
            case "temple" -> {
                add(-w - 0.6f, w + 0.6f, 0, 0.2f, -0.9f, 0.9f, Role.ACCENT, false);
                for (float s : new float[] {-1, 1}) {
                    float a0 = s < 0 ? -w - 0.4f : w, a1 = a0 + 0.4f;
                    add(a0, a1, 0.2f, h, -0.8f, -0.4f, Role.FRAME, false);
                    add(a0, a1, 0.2f, h, 0.4f, 0.8f, Role.FRAME, false);
                }
                add(-w - 0.6f, w + 0.6f, h, h + 0.25f, -0.9f, 0.9f, Role.ACCENT, false);
                frame(-w - 0.2f, w + 0.2f, h + 0.25f, h + 0.5f, 0.6f);
                frame(-w + 0.4f, w - 0.4f, h + 0.5f, h + 0.75f, 0.3f);
            }
            case "gothic" -> {
                frame(-w - f, -w, 0, h - 0.6f, t);
                frame(w, w + f, 0, h - 0.6f, t);
                float inset = 0.45f, v = h - 0.6f;
                for (int i = 0; i < 3 && w - i * inset > 0.1f; i++) {
                    float x = w - i * inset;
                    frame(-x - f, -x + 0.05f, v, v + 0.5f, t);
                    frame(x - 0.05f, x + f, v, v + 0.5f, t);
                    v += 0.5f;
                }
                add(-f, f, v, v + 0.7f, -t, t, Role.ACCENT, false);
            }
            case "lozenge" -> {
                int steps = 4;
                float rise = h / (2f * steps), v = 0;
                for (int i = 0; i < steps * 2; i++) {
                    float reach = i < steps ? (i + 1) * (w + 0.3f) / steps : (2 * steps - i) * (w + 0.3f) / steps;
                    frame(-reach - f, -reach, v, v + rise, t);
                    frame(reach, reach + f, v, v + rise, t);
                    v += rise;
                }
            }
            case "towers" -> {
                for (float s : new float[] {-1, 1}) {
                    frame(s * w, s * (w + 0.8f), 0, 1.2f, 0.5f);
                    frame(s * (w + 0.1f), s * (w + 0.7f), 1.2f, h, 0.35f);
                    add(s * w, s * (w + 0.8f), h, h + 0.3f, -0.5f, 0.5f, Role.ACCENT, false);
                    frame(s * (w + 0.2f), s * (w + 0.6f), h + 0.3f, h + 1.3f, 0.2f);
                }
                frame(-w, w, h - 0.2f, h + f - 0.2f, t);
            }
            case "monolith" -> {
                frame(-w - 1f, -w, 0, h + 1f, 0.4f);
                frame(w, w + 1f, 0, h + 1f, 0.4f);
                frame(-w, w, h, h + 1f, 0.4f);
                add(-w - 1f, w + 1f, h + 1f, h + 1.15f, -0.5f, 0.5f, Role.ACCENT, false);
            }
            case "toy" -> {
                float cube = 0.7f;
                int i = 0;
                for (float up = 0; up + cube <= h + 0.01f; up += cube) {
                    float wobble = i++ % 2 == 0 ? 0 : 0.1f;
                    frame(-w - cube + wobble, -w + wobble, up, up + cube, cube / 2);
                    frame(w - wobble, w + cube - wobble, up, up + cube, cube / 2);
                }
                for (float a = -w - cube; a < w + cube - 0.01f; a += cube) {
                    frame(a, Math.min(a + cube, w + cube), h, h + cube, cube / 2);
                }
            }
            default -> {
                frame(-w - f, -w, 0, h, t);
                frame(w, w + f, 0, h, t);
                frame(-w - f, w + f, h, h + f, t);
            }
        }
    }

    private void base(String base) {
        switch (base) {
            case "plinth" -> add(-w - 0.7f, w + 0.7f, 0, 0.2f, -1f, 1f, Role.ACCENT, false);
            case "stairs" -> {
                add(-w - 1f, w + 1f, 0, 0.12f, -1.3f, 1.3f, Role.ACCENT, false);
                add(-w - 0.6f, w + 0.6f, 0.12f, 0.24f, -0.8f, 0.8f, Role.FRAME, false);
            }
            case "feet" -> {
                add(-w - 0.7f, -w + 0.1f, 0, 0.3f, -0.8f, 0.8f, Role.ACCENT, false);
                add(w - 0.1f, w + 0.7f, 0, 0.3f, -0.8f, 0.8f, Role.ACCENT, false);
            }
            default -> { }
        }
    }

    private void crown(String crown, float top, Random r) {
        switch (crown) {
            case "spikes" -> {
                for (int i = -1; i <= 1; i++) {
                    float x = i * w * 0.6f, tall = i == 0 ? 1.1f : 0.7f;
                    add(x - 0.12f, x + 0.12f, top, top + tall, -0.12f, 0.12f, Role.ACCENT, false);
                }
            }
            case "orb" -> add(-0.3f, 0.3f, top + 0.4f, top + 1f, -0.3f, 0.3f, Role.GLOW, true);
            case "horns" -> {
                for (float s : new float[] {-1, 1}) {
                    add(s * (w - 0.1f), s * (w + 0.15f), top, top + 0.7f, -0.12f, 0.12f, Role.ACCENT, false);
                    add(s * (w + 0.1f), s * (w + 0.45f), top + 0.55f, top + 0.8f, -0.12f, 0.12f, Role.ACCENT, false);
                }
            }
            case "steps" -> {
                add(-w * 0.7f, w * 0.7f, top, top + 0.25f, -0.2f, 0.2f, Role.ACCENT, false);
                add(-w * 0.35f, w * 0.35f, top + 0.25f, top + 0.5f, -0.2f, 0.2f, Role.FRAME, false);
            }
            case "antennae" -> {
                for (float s : new float[] {-1, 1}) {
                    float x = s * w * 0.5f;
                    add(x - 0.05f, x + 0.05f, top, top + 1f, -0.05f, 0.05f, Role.ACCENT, false);
                    add(x - 0.12f, x + 0.12f, top + 1f, top + 1.25f, -0.12f, 0.12f, Role.GLOW, false);
                }
            }
            case "halo" -> {
                float v = top + 0.5f + r.nextFloat() * 0.3f, reach = w * 0.7f;
                add(-reach, reach, v, v + 0.1f, -0.6f, -0.5f, Role.GLOW, false);
                add(-reach, reach, v, v + 0.1f, 0.5f, 0.6f, Role.GLOW, false);
                add(-reach, -reach + 0.1f, v, v + 0.1f, -0.6f, 0.6f, Role.GLOW, false);
                add(reach - 0.1f, reach, v, v + 0.1f, -0.6f, 0.6f, Role.GLOW, false);
            }
            default -> { }
        }
    }

    private void ornament(String ornament, Random r) {
        switch (ornament) {
            case "shards" -> {
                for (int i = 0, n = 2 + r.nextInt(3); i < n; i++) {
                    float s = i % 2 == 0 ? -1 : 1, x = s * (w + 0.9f + r.nextFloat() * 0.4f), v = 0.8f + r.nextFloat() * (h - 0.8f), size = 0.12f + r.nextFloat() * 0.12f;
                    add(x - size, x + size, v, v + 2 * size, -size, size, Role.ACCENT, true);
                }
            }
            case "lanterns" -> {
                for (float s : new float[] {-1, 1}) {
                    float x = s * (w + 0.75f);
                    add(x - 0.05f, x + 0.05f, 0, h * 0.6f, -0.05f, 0.05f, Role.FRAME, false);
                    add(x - 0.18f, x + 0.18f, h * 0.6f, h * 0.6f + 0.36f, -0.18f, 0.18f, Role.GLOW, false);
                }
            }
            case "buttress" -> {
                for (float s : new float[] {-1, 1}) {
                    add(s * (w + 0.2f), s * (w + 0.9f), 0, 0.8f, -0.2f, 0.2f, Role.ACCENT, false);
                    add(s * (w + 0.2f), s * (w + 0.55f), 0.8f, 1.5f, -0.2f, 0.2f, Role.ACCENT, false);
                }
            }
            default -> { }
        }
    }

    /** What fills the frame. Mostly open patterns, so it reads as a gate and not as a portal sheet. */
    private void opening(String opening, Random r) {
        float d = 0.04f;
        switch (opening) {
            case "bars" -> {
                int n = 4;
                for (int i = 0; i < n; i++) {
                    float x = -w + (i + 0.5f) * 2 * w / n;
                    add(x - 0.1f, x + 0.1f, 0, h, -d, d, Role.OPENING, false);
                }
            }
            case "louvres" -> {
                for (float v = 0.3f; v < h; v += 0.75f) add(-w, w, v, v + 0.3f, -d, d, Role.OPENING, false);
            }
            case "lattice" -> {
                for (float x : new float[] {-w * 0.5f, 0, w * 0.5f}) add(x - 0.06f, x + 0.06f, 0, h, -d, d, Role.OPENING, false);
                for (float v = h * 0.25f; v < h - 0.1f; v += h * 0.25f) add(-w, w, v - 0.06f, v + 0.06f, -d, d, Role.OPENING, false);
            }
            case "inset" -> {
                float m = 0.35f;
                add(-w, w, 0, m, -d, d, Role.OPENING, false);
                add(-w, w, h - m, h, -d, d, Role.OPENING, false);
                add(-w, -w + m, m, h - m, -d, d, Role.OPENING, false);
                add(w - m, w, m, h - m, -d, d, Role.OPENING, false);
                add(-0.3f, 0.3f, h / 2 - 0.3f, h / 2 + 0.3f, -d, d, Role.GLOW, true);
            }
            case "shards" -> {
                for (int i = 0; i < 6; i++) {
                    float x = -w + 0.3f + r.nextFloat() * (2 * w - 0.6f), v = 0.3f + r.nextFloat() * (h - 0.6f), size = 0.14f + r.nextFloat() * 0.14f;
                    add(x - size, x + size, v - size, v + size, -d, d, Role.OPENING, true);
                }
            }
            default -> add(-w, w, 0, h, -d, d, Role.OPENING, false);
        }
    }

    private static String pick(Random r, String[] from) {
        return from[r.nextInt(from.length)];
    }
}
