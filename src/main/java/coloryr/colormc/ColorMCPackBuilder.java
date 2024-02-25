package coloryr.colormc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public class ColorMCPackBuilder {
    public static void setGrabbed(int mode, int value) {
        if (mode == 208897) {
            if (value == 212995) {
                ColorMCPackBuilder.setGrabbed(true);
            } else if (value == 212993) {
                ColorMCPackBuilder.setGrabbed(false);
            }
        }
    }

    public static void setGrabbed(boolean grab) {
        ColorMCASM.client.sendMessage(ColorMCPackBuilder.buildGrabbed(grab));
    }

    private static ByteBuf buildGrabbed(boolean grab) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(1);
        buf.writeInt(ColorMCASM.uuidBytes.length);
        buf.writeBytes(ColorMCASM.uuidBytes);
        buf.writeBoolean(grab);
        return buf;
    }
}
