package com.extantape.waystation;

import java.util.List;

/**
 * The station seen from above, as pixels, for the wall map. Pure arithmetic with no server, so it can be tested.
 *
 * <p>Each platform is a coloured block with its number written beside it, and each gate a bright mark on it. Colour
 * never carries meaning alone: the numbers match the platform signs. Only the gates the viewer can see are drawn, so the
 * picture tells nobody about worlds they can't reach.
 */
final class StationPicture {
    /** One gate to draw: its block position, which way its plane runs, its width and its platform's number. */
    record Mark(int x, int z, boolean alongX, int width, int platform) {}

    static final int BACKGROUND = 0x2b2b2b, FLOOR = 0x5a5a5a, CENTRE = 0xe0c068, GATE = 0xffffff, TEXT = 0xffffff;
    /** Platform colours, picked to stay apart for the common kinds of colour blindness; numbers carry the meaning. */
    static final int[] PLATFORM = {0x0072b2, 0xe69f00, 0x009e73, 0xcc79a7, 0x56b4e9, 0xd55e00, 0xf0e442, 0x9a9a9a};

    final int size;
    final int[] rgb;
    /** World block at the picture's top-left corner, and blocks per pixel. */
    final double originX, originZ, scale;

    private StationPicture(int size, double originX, double originZ, double scale) {
        this.size = size;
        this.rgb = new int[size * size];
        this.originX = originX;
        this.originZ = originZ;
        this.scale = scale;
        java.util.Arrays.fill(rgb, BACKGROUND);
    }

    /**
     * Draws the station. The picture is square, centred on the station's centre, and wide enough for every gate with a
     * margin, so the platforms fill it rather than sit in a corner.
     *
     * @param size pixels across: 128 per map tile
     */
    static StationPicture draw(int size, int centreX, int centreZ, List<Mark> marks, int depth) {
        int reach = 16;
        for (Mark m : marks) reach = Math.max(reach, Math.max(Math.abs(m.x() - centreX), Math.abs(m.z() - centreZ)) + depth + 6);
        double scale = (2.0 * reach) / size;
        StationPicture p = new StationPicture(size, centreX - reach, centreZ - reach, scale);
        // Floors first, then the centrepiece, then gates and numbers on top.
        for (Mark m : marks) {
            int colour = PLATFORM[Math.floorMod(m.platform() - 1, PLATFORM.length)];
            int half = m.width() / 2 + 1;
            if (m.alongX()) p.fillBlocks(m.x() - half, m.z() - depth, m.x() + half, m.z() + depth, colour);
            else p.fillBlocks(m.x() - depth, m.z() - half, m.x() + depth, m.z() + half, colour);
        }
        p.fillBlocks(centreX - 2, centreZ - 2, centreX + 2, centreZ + 2, CENTRE);
        java.util.Map<Integer, int[]> labelAt = new java.util.TreeMap<>();
        for (Mark m : marks) {
            int half = m.width() / 2;
            if (m.alongX()) p.fillBlocks(m.x() - half, m.z(), m.x() + half, m.z(), GATE);
            else p.fillBlocks(m.x(), m.z() - half, m.x(), m.z() + half, GATE);
            labelAt.merge(m.platform(), new int[] {m.x(), m.z(), 1}, (a, b) -> new int[] {a[0] + b[0], a[1] + b[1], a[2] + b[2]});
        }
        for (var e : labelAt.entrySet()) {
            int[] sum = e.getValue();
            int px = p.toPixelX(sum[0] / (double) sum[2]), pz = p.toPixelZ(sum[1] / (double) sum[2]);
            p.number(e.getKey(), px, pz);
        }
        return p;
    }

    int toPixelX(double worldX) { return (int) Math.floor((worldX - originX) / scale); }

    int toPixelZ(double worldZ) { return (int) Math.floor((worldZ - originZ) / scale); }

    int at(int x, int y) { return rgb[y * size + x]; }

    private void fillBlocks(int x0, int z0, int x1, int z1, int colour) {
        int px0 = toPixelX(x0), pz0 = toPixelZ(z0), px1 = toPixelX(x1 + 1) - 1, pz1 = toPixelZ(z1 + 1) - 1;
        for (int y = Math.max(0, pz0); y <= Math.min(size - 1, Math.max(pz0, pz1)); y++)
            for (int x = Math.max(0, px0); x <= Math.min(size - 1, Math.max(px0, px1)); x++) rgb[y * size + x] = colour;
    }

    /** A number in a 3×5 pixel font, doubled for reading from across the room, on a dark plate, centred on (cx, cy). */
    private void number(int n, int cx, int cy) {
        String s = Integer.toString(n);
        int w = s.length() * 8 - 2, h = 10;
        int x0 = cx - w / 2, y0 = cy - h / 2;
        for (int y = y0 - 2; y < y0 + h + 2; y++) for (int x = x0 - 2; x < x0 + w + 2; x++) set(x, y, BACKGROUND);
        for (int i = 0; i < s.length(); i++) {
            int[] glyph = DIGITS[s.charAt(i) - '0'];
            for (int row = 0; row < 5; row++) for (int col = 0; col < 3; col++) {
                if ((glyph[row] >> (2 - col) & 1) == 0) continue;
                for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) set(x0 + i * 8 + col * 2 + dx, y0 + row * 2 + dy, TEXT);
            }
        }
    }

    private void set(int x, int y, int colour) { if (x >= 0 && y >= 0 && x < size && y < size) rgb[y * size + x] = colour; }

    private static final int[][] DIGITS = {
        {7, 5, 5, 5, 7}, {2, 6, 2, 2, 7}, {7, 1, 7, 4, 7}, {7, 1, 7, 1, 7}, {5, 5, 7, 1, 1},
        {7, 4, 7, 1, 7}, {7, 4, 7, 5, 7}, {7, 1, 2, 2, 2}, {7, 5, 7, 5, 7}, {7, 5, 7, 1, 7},
    };
}
