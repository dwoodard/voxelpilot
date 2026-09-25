package dev.dwoodard.voxelpilot.plan;

import java.util.List;

// One component in a plan, exactly as the model writes it (Gson-mapped). Fields are flat
// and optional rather than a per-type union because small local models fill a flat schema
// far more reliably. Which fields matter depends on "type"; see Shapes.
public final class PlanNode {
    public String id;
    public String type;
    public String on;          // id of an earlier node to stand on (origin = its top-left-near corner)
    public int[] at;           // [x, y, z] offset from the plan origin, or from "on"
    public int[] size;         // [width(right), height(up), depth(forward)]
    public String block;
    public String fill;        // box: solid | hollow | walls | outline
    public String direction;   // forward | back | left | right
    public Integer length;     // stairs
    public Integer width;      // stairs
    public String vertical;    // stairs: up | down
    public Boolean carve;      // stairs: clear 3 blocks of headroom above each step
    public String ridge;       // roof: block along an odd-width ridge
    public String ends;        // roof: block filling the gable triangles
    public List<RawBlock> blocks;

    // circle / sphere
    public Integer radius;
    public Integer radiusZ;     // circle: second radius along z for an oval
    public Integer thickness;   // circle: ring thickness when not filled
    public Boolean filled;
    public String fillBlock;   // circle: interior block when it differs from the rim
    public Boolean even;       // circle: even diameter (center between blocks)
    public Integer arcStart;   // circle: degrees, 0 = forward, 90 = right
    public Integer arcEnd;
    public Double squareness;  // circle: 0 = round .. 1 = square
    public Integer height;     // circle: extrude upward into a cylinder or round wall
    public Boolean dome;       // sphere: top half only

    // line: end point, in the same coordinates as "at"
    public int[] to;

    public static final class RawBlock {
        public int[] at;
        public String block;
    }
}
