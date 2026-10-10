package com.embers.packmod;

/** Deterministic locations in the shared, half-pixel rounded coverage atlas. */
public final class RoundedMaskLayout {
    public static final int MAX_RADIUS=20, WIDTH=512, HEIGHT=512;
    public record Mask(int x,int y,int size) {}
    public static final Mask[][] PATCHES=new Mask[MAX_RADIUS+1][2];
    public static final Mask[] SLOTS=new Mask[2];
    static {
        int x=1,y=1,row=0;
        for(int r=1;r<=MAX_RADIUS;r++)for(int outline=0;outline<2;outline++){
            int size=(r*2+2)*2;
            if(x+size+1>WIDTH){x=1;y+=row+2;row=0;}
            PATCHES[r][outline]=new Mask(x,y,size);x+=size+2;row=Math.max(row,size);
        }
        for(int outline=0;outline<2;outline++){
            int size=36;
            if(x+size+1>WIDTH){x=1;y+=row+2;row=0;}
            SLOTS[outline]=new Mask(x,y,size);x+=size+2;row=Math.max(row,size);
        }
        if(y+row+1>HEIGHT)throw new ExceptionInInitializerError("Rounded mask atlas overflow");
    }
    private RoundedMaskLayout() {}
}
