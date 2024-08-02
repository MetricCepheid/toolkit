package toolkit.functions;

import cwlib.enums.CompressionFlags;
import cwlib.enums.Part;
import cwlib.enums.ResourceType;
import cwlib.io.Resource;
import cwlib.io.streams.MemoryOutputStream;
import cwlib.resources.RMesh;
import cwlib.resources.RPlan;
import cwlib.resources.RTexture;
import cwlib.singleton.ResourceSystem;
import cwlib.structs.inventory.InventoryItemDetails;
import cwlib.structs.mesh.Bone;
import cwlib.structs.things.Thing;
import cwlib.structs.things.parts.PGeneratedMesh;
import cwlib.structs.things.parts.PGroup;
import cwlib.structs.things.parts.PPos;
import cwlib.structs.things.parts.PRenderMesh;
import cwlib.structs.things.parts.PShape;
import cwlib.types.SerializedResource;
import cwlib.types.data.*;
import cwlib.types.databases.FileEntry;
import cwlib.types.swing.FileNode;
import cwlib.util.FileIO;
import cwlib.util.Resources;
import cwlib.util.Strings;
import toolkit.utilities.FileChooser;
import toolkit.windows.Toolkit;

import java.awt.event.ActionEvent;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;

import javax.imageio.ImageIO;
import javax.swing.JOptionPane;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import configurations.Config;

public class PusherCallbacks 
{
    /**
     * The port of the pusher server on the PS3.
     */
    public static final int PUSHER_PORT = 6504;

    public static class PusherMessageType
    {
        /**
         * Creates/removes/edits a thing in the world.
         */
        public static final int THING_DATA = 0x2;

        /**
         * Sets the current camera matrix.
         */
        public static final int SET_CAMERA_MATRIX = 0x3;

        /**
         * Sets the game to quit, reboots on debug builds.
         */
        public static final int SET_WANT_QUIT = 0x4;

        /**
         * Pushes a new set of color correction presets to PS3.
         */
        public static final int SET_COLOR_CORRECTION_PRESETS = 0x7;

        /**
         * Pushes a level to the PS3.
         */
        public static final int PUSH_LEVEL = 0x8;

        /**
         * Gets the current level data from the PS3.
         */
        public static final int GET_CURRENT_LEVEL_DATA = 0x9;

        /**
         * Renders a plan to an icon and returns the result.
         */
        public static final int GET_ITEM_ICON = 0xa;

        /**
         * Gets the resource data associated with a SHA1.
         */
        public static final int GET_RESOURCE_DATA = 0xb;
    }

    public static void getItemRender(ActionEvent event)
    {
        try (Socket client = new Socket(Config.instance.ps3IpAddress, PUSHER_PORT))
        {
            FileNode node = ResourceSystem.getSelected();
            ResourceInfo info = node.getEntry().getInfo();
            if (info == null || (info.getType() != ResourceType.PLAN && info.getType() != ResourceType.MESH && info.getType() != ResourceType.GFX_MATERIAL) || info.getResource() == null)
            {
                JOptionPane.showMessageDialog(Toolkit.INSTANCE, "Resource is either NULL or invalid!", "PS3 Pusher", JOptionPane.ERROR_MESSAGE);
                return;
            }

            byte[] resourceData = ResourceSystem.extract(node.getEntry());
            if (resourceData == null)
            {
                JOptionPane.showMessageDialog(Toolkit.INSTANCE, "Failed to extract data for render!", "PS3 Pusher", JOptionPane.ERROR_MESSAGE);
                return;
            }

            byte[] planData = resourceData;
            if (info.getType() == ResourceType.MESH)
            {
                // Matrix4f transform = new Matrix4f().identity().rotate((float) Math.toRadians(-90.0f), new Vector3f(1.0f, 0.0f, 0.0f), new Matrix4f());
                Matrix4f transform = new Matrix4f().identity();

                ResourceDescriptor descriptor = new ResourceDescriptor((GUID)node.getEntry().getKey(), ResourceType.MESH);
                RMesh mesh = info.getResource();
                ArrayList<Thing> things = new ArrayList<>();
                int thingUIDCounter = 0;
                Bone[] bones = mesh.getBones();
                for (Bone bone : bones)
                {
                    Thing thing = new Thing(++thingUIDCounter);
                    Matrix4f wpos = transform.mul(bone.skinPoseMatrix, new Matrix4f());
                    thing.setPart(Part.POS, new PPos(null, bone.animHash, wpos));
                    things.add(thing);
                }

                Thing root = things.get(0);
                root.setPart(Part.GROUP, new PGroup());
                root.setPart(Part.RENDER_MESH, new PRenderMesh(descriptor, things.toArray(Thing[]::new)));

                for (int i = 0; i < bones.length; ++i)
                {
                    Bone bone = bones[i];
                    Thing thing = things.get(i);
                    PPos pos = thing.getPart(Part.POS);
                    pos.thingOfWhichIAmABone = root;

                    if (i != 0)
                        thing.groupHead = root;
                    
                    if (bone.parent != -1)
                    {
                        thing.parent = things.get(bone.parent);
                        pos.recomputeLocalPos(thing);
                    }
                }

                RPlan plan = new RPlan(new Revision(0x132), CompressionFlags.USE_NO_COMPRESSION, things.toArray(Thing[]::new), new InventoryItemDetails());
                planData = SerializedResource.compress(plan.build());
            }
            else if (info.getType() == ResourceType.GFX_MATERIAL)
            {
                ResourceDescriptor descriptor = new ResourceDescriptor((GUID)node.getEntry().getKey(), ResourceType.GFX_MATERIAL);
                Thing root = new Thing(1);
                root.setPart(Part.POS, new PPos());
                root.setPart(Part.SHAPE, new PShape());
                root.setPart(Part.GENERATED_MESH, new PGeneratedMesh(descriptor, null));

                RPlan plan = new RPlan(new Revision(0x132), CompressionFlags.USE_NO_COMPRESSION, root, new InventoryItemDetails());
                planData = SerializedResource.compress(plan.build());            
            }



            byte[] message = new byte[0x1 + 0x14 + 0x4 + planData.length];
            message[0] = PusherMessageType.GET_ITEM_ICON;
            System.arraycopy(SHA1.fromBuffer(planData).getHash(), 0x0, message, 0x1, 0x14);
            message[0x15] = (byte)(planData.length >>> 24);
            message[0x16] = (byte)(planData.length >>> 16);
            message[0x17] = (byte)(planData.length >>> 8);
            message[0x18] = (byte)(planData.length & 0xFF);
            System.arraycopy(planData, 0, message, 0x19, planData.length);

            client.getOutputStream().write(message);

            byte[] result = null;
            try
            {
                DataInputStream stream = new DataInputStream(new BufferedInputStream(client.getInputStream()));
                byte[] sha1 = stream.readNBytes(0x14);
                int len = stream.readInt();
                if (len != 0)
                {
                    result = stream.readNBytes(len);
                }
            }
            catch (IOException ioex) { /* Ignore this error, we'll handle it in the next part */ }
            if (result == null)
            {
                JOptionPane.showMessageDialog(Toolkit.INSTANCE, "PS3 didn't send back any data!", "PS3 Pusher", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // Convert to PNG and write result to wherever user chooses
            String name = node.getName();
            File file = FileChooser.openFile(Strings.setExtension(name, ".png"), "png", true);
            if (file == null) return;

            try
            {
                RTexture texture = new RTexture(result);
                ImageIO.write(texture.getImage(), "png", file);
            }
            catch (Exception ex)
            {
                JOptionPane.showMessageDialog(Toolkit.INSTANCE, "An error occurred while reading resulting image data", "PS3 Pusher", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
        catch (UnknownHostException ex)
        {
            JOptionPane.showMessageDialog(Toolkit.INSTANCE, "Please make sure to use a valid IP address!", "PS3 Pusher", JOptionPane.ERROR_MESSAGE);
        }
        catch (IOException ex)
        {
            JOptionPane.showMessageDialog(Toolkit.INSTANCE, "An error occurred while connecting to PS3, make sure you're on a build that supports the pusher!", "PS3 Pusher", JOptionPane.ERROR_MESSAGE);
        }
        catch (Exception ex)
        {
            JOptionPane.showMessageDialog(Toolkit.INSTANCE, "An unknown error occurred", "PS3 Pusher", JOptionPane.ERROR_MESSAGE);
        }
    }
}
