package chasm;

public final class WallSurface {

    public byte  upperTextureId;   // Texture for upper part (above player view)
    public byte  mainTextureId;    // Main wall texture (middle)
    public byte  lowerTextureId;   // Texture for lower part (below player view)

    private short sectorLinkId;    // Index in GameWorld.sectors[] — which sector this side faces

    public SectorData linkedSector; // Resolved after resolveSectorLink() — adjacent sector (portal)

    public short textureOffsetX;   // Horizontal texture offset (for switches, animated walls)
    public short textureOffsetY;   // Vertical texture offset
    public short textureRepeatX;   // Build wall repeat (8 = native horizontal scale)
    public short textureRepeatY;   // Build wall repeat (8 = native vertical scale)
    public byte lightLevel = -1;   // Optional per-wall light (0..15), -1 inherits sector

    /**
     * Constructor called when loading level data.
     *
     * @param upperTex  texture ID for upper unpegged area
     * @param mainTex   main wall texture
     * @param lowerTex  texture ID for lower unpegged area
     * @param linkId    index of the sector this wall side belongs to / looks into
     * @param offsetX   X texture offset
     * @param offsetY   Y texture offset
     */
    public WallSurface(byte upperTex, byte mainTex, byte lowerTex,
                       int linkId, short offsetX, short offsetY) {
        this(upperTex, mainTex, lowerTex, linkId, offsetX, offsetY, 8, 8);
    }

    public WallSurface(byte upperTex, byte mainTex, byte lowerTex,
                       int linkId, short offsetX, short offsetY,
                       int repeatX, int repeatY) {
        this(upperTex, mainTex, lowerTex, linkId, offsetX, offsetY,
                repeatX, repeatY, -1);
    }

    public WallSurface(byte upperTex, byte mainTex, byte lowerTex,
                       int linkId, short offsetX, short offsetY,
                       int repeatX, int repeatY, int lightLevel) {
        this.upperTextureId = upperTex;
        this.mainTextureId  = mainTex;
        this.lowerTextureId = lowerTex;
        this.sectorLinkId   = (short)linkId;
        this.textureOffsetX = offsetX;
        this.textureOffsetY = offsetY;
        this.textureRepeatX = (short)repeatX;
        this.textureRepeatY = (short)repeatY;
        this.lightLevel = (byte)lightLevel;
    }

    /**
     * Called after level loading.
     * Resolves the sector index into an actual SectorData reference.
     * This is the core of the portal system — every wall side knows which sector it "opens" into.
     */
    public final void resolveSectorLink(GameWorld world) {
        this.linkedSector = world.sectors[this.sectorLinkId & 0xFFFF];
    }
}