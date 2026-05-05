package com.drskunk.sh3dikea.glb;

import java.io.File;
import java.nio.file.Files;

/**
 * Run-from-CLI helper for testing the converter standalone:
 *
 *   java -cp build/classes com.drskunk.sh3dikea.glb.GlbCli in.glb out.zip
 *
 * Not bundled into the .sh3p plugin (no UI; only useful for development).
 */
public final class GlbCli {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: GlbCli <input.glb> <output.zip>");
            System.exit(2);
        }
        byte[] glb = Files.readAllBytes(new File(args[0]).toPath());
        GlbToObj.Result r = GlbToObj.convert(glb, new File(args[1]));
        System.out.println("Wrote " + args[1]);
        System.out.printf("Bounds (m): x=[%.3f, %.3f] y=[%.3f, %.3f] z=[%.3f, %.3f]%n",
                r.minX, r.maxX, r.minY, r.maxY, r.minZ, r.maxZ);
        System.out.printf("Size   (m): %.3f x %.3f x %.3f%n",
                r.widthMeters(), r.heightMeters(), r.depthMeters());
    }
}
