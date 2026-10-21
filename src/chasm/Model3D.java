package chasm;

import java.io.DataInputStream;
import java.io.InputStream;

/**
 * 3D Polygonal Mesh + Keyframe Vertex Animation + Dismemberment Submodels
 * for Chasm: The Rift (.CAR and .3O models).
 *
 * Each .CAR monster model includes:
 *  - Main mesh with authentic BodyPartsMask per polygon:
 *      1  = LeftHand
 *      2  = LeftHandSeparated (bloody left shoulder stump)
 *      4  = RightHand (right arm + weapon)
 *      8  = RightHandSeparated (bloody right shoulder stump)
 *      16 = Head
 *      32 = HeadSeparated (bloody neck stump)
 *      64 = Body (torso & legs)
 *      128= WeaponFire (muzzle flash)
 *  - 3 embedded detached body-part submodels (submodels[0..2]):
 *      submodels[0] = Detached Right Arm + Weapon
 *      submodels[1] = Detached Left Arm
 *      submodels[2] = Detached Head
 */
public final class Model3D {
    public static final int MASK_LEFT_HAND           = 1;
    public static final int MASK_LEFT_HAND_SEPARATED = 2;
    public static final int MASK_RIGHT_HAND          = 4;
    public static final int MASK_RIGHT_HAND_SEPARATED= 8;
    public static final int MASK_HEAD                = 16;
    public static final int MASK_HEAD_SEPARATED      = 32;
    public static final int MASK_BODY                = 64;
    public static final int MASK_WEAPON_FIRE         = 128;
    public static final int MASK_INTACT_DEFAULT      = MASK_BODY | MASK_LEFT_HAND | MASK_RIGHT_HAND | MASK_HEAD; // 85

    public static final int SUBMODEL_RIGHT_HAND = 0;
    public static final int SUBMODEL_LEFT_HAND  = 1;
    public static final int SUBMODEL_HEAD       = 2;

    public int vCount;
    public int tCount;
    public int fCount;

    public byte[] triV0;
    public byte[] triV1;
    public byte[] triV2;
    public byte[] triGroup;
    public byte[] triFlags;
    public byte[] triNX;
    public byte[] triNY;
    public byte[] triNZ;

    // Flat Shading single-color polygon RGB
    public int[] triFlatRGB;

    // Vertex Colors / Vertex Painting RGB for each of the 3 triangle vertices
    public int[] triVertRGB0;
    public int[] triVertRGB1;
    public int[] triVertRGB2;

    // Texture Mapping: per-triangle corner UVs (texel U 0..63, texel V 0..texH-1), 3 entries per triangle
    public byte[] triU;
    public short[] triV;

    // Texture atlas for texture mapping: 64 texels wide, 8-bit palette indices (index 0/255 = holes)
    public byte[] texData;
    public int texH;

    // Keyframe vertex positions: frameVerts[frame][v * 3 + 0..2] in 1/2048 tile units
    public short[][] frameVerts;

    // Detached limb submodels for .CAR monsters (0=RightHand, 1=LeftHand, 2=Head)
    public Model3D[] submodels;

    private static Model3D readMesh(DataInputStream dis) throws Exception {
        Model3D m = new Model3D();
        int vc = dis.readUnsignedShort();
        int tc = dis.readUnsignedShort();
        int fc = dis.readUnsignedShort();
        m.vCount = vc;
        m.tCount = tc;
        m.fCount = fc;

        m.triV0 = new byte[tc];
        m.triV1 = new byte[tc];
        m.triV2 = new byte[tc];
        m.triGroup = new byte[tc];
        m.triFlags = new byte[tc];
        m.triNX = new byte[tc];
        m.triNY = new byte[tc];
        m.triNZ = new byte[tc];
        m.triFlatRGB = new int[tc];
        m.triVertRGB0 = new int[tc];
        m.triVertRGB1 = new int[tc];
        m.triVertRGB2 = new int[tc];
        m.triU = new byte[tc * 3];
        m.triV = new short[tc * 3];

        // Texture atlas (texH = 0 for detached limbs, which reuse the parent's atlas)
        int th = dis.readUnsignedShort();
        m.texH = th;
        if (th > 0) {
            m.texData = new byte[th * 64];
            dis.readFully(m.texData);
        }

        for (int t = 0; t < tc; t++) {
            m.triV0[t] = dis.readByte();
            m.triV1[t] = dis.readByte();
            m.triV2[t] = dis.readByte();
            m.triGroup[t] = dis.readByte();
            m.triFlags[t] = dis.readByte();
            m.triNX[t] = dis.readByte();
            m.triNY[t] = dis.readByte();
            m.triNZ[t] = dis.readByte();

            int fr = dis.readUnsignedByte();
            int fg = dis.readUnsignedByte();
            int fb = dis.readUnsignedByte();
            m.triFlatRGB[t] = (fr << 16) | (fg << 8) | fb;

            int r0 = dis.readUnsignedByte(), g0 = dis.readUnsignedByte(), b0 = dis.readUnsignedByte();
            int r1 = dis.readUnsignedByte(), g1 = dis.readUnsignedByte(), b1 = dis.readUnsignedByte();
            int r2 = dis.readUnsignedByte(), g2 = dis.readUnsignedByte(), b2 = dis.readUnsignedByte();
            m.triVertRGB0[t] = (r0 << 16) | (g0 << 8) | b0;
            m.triVertRGB1[t] = (r1 << 16) | (g1 << 8) | b1;
            m.triVertRGB2[t] = (r2 << 16) | (g2 << 8) | b2;

            m.triU[t * 3] = dis.readByte();
            m.triU[t * 3 + 1] = dis.readByte();
            m.triU[t * 3 + 2] = dis.readByte();
            m.triV[t * 3] = dis.readShort();
            m.triV[t * 3 + 1] = dis.readShort();
            m.triV[t * 3 + 2] = dis.readShort();
        }

        m.frameVerts = new short[fc][vc * 3];
        for (int f = 0; f < fc; f++) {
            short[] fv = m.frameVerts[f];
            int len = vc * 3;
            for (int k = 0; k < len; k++) {
                fv[k] = dis.readShort();
            }
        }
        return m;
    }

    public static Model3D[] loadAllModels(String resourcePath) {
        try {
            InputStream is = Model3D.class.getResourceAsStream(resourcePath);
            if (is == null) return new Model3D[0];
            DataInputStream dis = new DataInputStream(is);
            int count = dis.readUnsignedShort();
            Model3D[] models = new Model3D[count];

            for (int i = 0; i < count; i++) {
                Model3D m = readMesh(dis);
                int subCount = dis.readUnsignedByte();
                if (subCount > 0) {
                    m.submodels = new Model3D[subCount];
                    for (int s = 0; s < subCount; s++) {
                        Model3D sub = readMesh(dis);
                        // Detached limbs are cut from the same .CAR, so they share its texture atlas.
                        sub.texData = m.texData;
                        sub.texH = m.texH;
                        m.submodels[s] = sub;
                    }
                }
                models[i] = m;
            }
            dis.close();
            return models;
        } catch (Exception e) {
            return new Model3D[0];
        }
    }
}
