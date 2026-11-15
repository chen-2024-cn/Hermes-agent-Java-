package com.cyk.util;

/** Perspective wireframe rendered at 2x4 sub-cell resolution without an image protocol. */
final class RotatingCube {
    static final int COLUMNS = 17;
    static final int ROWS = 9;
    private static final int PIXEL_COLUMNS = COLUMNS * 2;
    private static final int PIXEL_ROWS = ROWS * 4;
    private static final int[][] DOT_BITS = {{1, 2, 4, 64}, {8, 16, 32, 128}};
    private static final String[] COLORS = {"", "\033[38;2;48;76;88m", "\033[38;2;69;145;163m", "\033[38;2;126;225;235m"};
    private static final double CAMERA_DISTANCE = 6.0;
    private static final double SCALE = 8.1;
    private static final double TILT = 0.43;
    private static final double REST_ANGLE = 0.62;

    private record Point(double x, double y, double z) { }
    private record Projection(double x, double y) { }

    static String[] render(int frame, int frameCount, boolean color) {
        double progress = Math.max(0, Math.min(1, frame / (double) Math.max(1, frameCount - 1)));
        // One quarter turn returns a cube to the same silhouette; ease out into a stable isometric pose.
        double eased = 1 - Math.pow(1 - progress, 2);
        // Canonicalize equivalent end poses so dotted hidden edges also finish pixel-identically.
        double angle = progress == 0 || progress == 1 ? REST_ANGLE : REST_ANGLE - Math.PI / 2 * (1 - eased);
        Point[] vertices = new Point[8];
        Projection[] projected = new Projection[8];
        for (int vertex = 0; vertex < vertices.length; vertex++) {
            vertices[vertex] = rotate(new Point(sign(vertex, 0), sign(vertex, 1), sign(vertex, 2)), angle);
            projected[vertex] = project(vertices[vertex]);
        }
        int[][] pixels = new int[PIXEL_ROWS][PIXEL_COLUMNS];
        // Draw occluded edges first. Brighter visible edges win at crossings and shared dots.
        for (boolean visiblePass : new boolean[]{false, true}) {
            for (int vertex = 0; vertex < vertices.length; vertex++) {
                for (int axis = 0; axis < 3; axis++) {
                    int other = vertex ^ (1 << axis);
                    if (vertex > other) continue;
                    boolean visible = visibleEdge(vertex, axis, vertices[vertex], angle);
                    if (visible != visiblePass) continue;
                    double depth = (vertices[vertex].z() + vertices[other].z()) / 2;
                    int brightness = visible ? depth > 0 ? 3 : 2 : 1;
                    drawLine(pixels, projected[vertex], projected[other], brightness, !visible);
                }
            }
        }
        return cells(pixels, color);
    }

    private static double sign(int vertex, int axis) { return (vertex & (1 << axis)) == 0 ? -1 : 1; }

    private static Point rotate(Point point, double angle) {
        double x = point.x() * Math.cos(angle) + point.z() * Math.sin(angle);
        double z = -point.x() * Math.sin(angle) + point.z() * Math.cos(angle);
        return new Point(x, point.y() * Math.cos(TILT) - z * Math.sin(TILT),
                point.y() * Math.sin(TILT) + z * Math.cos(TILT));
    }

    private static Projection project(Point point) {
        double perspective = CAMERA_DISTANCE / (CAMERA_DISTANCE - point.z());
        return new Projection((PIXEL_COLUMNS - 1) / 2.0 + point.x() * perspective * SCALE,
                (PIXEL_ROWS - 1) / 2.0 - point.y() * perspective * SCALE);
    }

    private static boolean visibleEdge(int vertex, int edgeAxis, Point point, double angle) {
        for (int axis = 0; axis < 3; axis++) {
            if (axis == edgeAxis) continue;
            double direction = sign(vertex, axis);
            Point normal = rotate(new Point(axis == 0 ? direction : 0,
                    axis == 1 ? direction : 0, axis == 2 ? direction : 0), angle);
            double facing = normal.x() * -point.x() + normal.y() * -point.y()
                    + normal.z() * (CAMERA_DISTANCE - point.z());
            if (facing > 0) return true;
        }
        return false;
    }

    private static void drawLine(int[][] pixels, Projection from, Projection to, int brightness, boolean hidden) {
        int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(to.x() - from.x()), Math.abs(to.y() - from.y()))));
        for (int step = 0; step <= steps; step++) {
            if (hidden && step % 4 >= 2 && step != steps) continue;
            double position = step / (double) steps;
            int x = (int) Math.round(from.x() + (to.x() - from.x()) * position);
            int y = (int) Math.round(from.y() + (to.y() - from.y()) * position);
            if (x >= 0 && x < PIXEL_COLUMNS && y >= 0 && y < PIXEL_ROWS) {
                pixels[y][x] = Math.max(pixels[y][x], brightness);
            }
        }
    }

    private static String[] cells(int[][] pixels, boolean color) {
        String[] lines = new String[ROWS];
        for (int row = 0; row < ROWS; row++) {
            StringBuilder line = new StringBuilder();
            int previousBrightness = 0;
            for (int column = 0; column < COLUMNS; column++) {
                int mask = 0;
                int brightness = 0;
                for (int x = 0; x < 2; x++) {
                    for (int y = 0; y < 4; y++) {
                        int pixel = pixels[row * 4 + y][column * 2 + x];
                        if (pixel > 0) mask |= DOT_BITS[x][y];
                        brightness = Math.max(brightness, pixel);
                    }
                }
                if (color && brightness != previousBrightness) {
                    line.append(brightness == 0 ? "\033[0m" : COLORS[brightness]);
                    previousBrightness = brightness;
                }
                line.append(mask == 0 ? ' ' : (char) (0x2800 + mask));
            }
            if (color && previousBrightness != 0) line.append("\033[0m");
            lines[row] = line.toString();
        }
        return lines;
    }

    private RotatingCube() { }
}
