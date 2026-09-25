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

    public static final class RawBlock {
        public int[] at;
        public String block;
    }
}
