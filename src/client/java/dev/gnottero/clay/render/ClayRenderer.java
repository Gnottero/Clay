package dev.gnottero.clay.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.joml.Matrix4fc;

/** Draws a {@link ClayMesh}: {@link GpuRenderer} from GPU buffers, {@link CpuRenderer} by streaming vertices. See {@link ClayRenderers}. */
public interface ClayRenderer {
	/**
	 * @param palette the model-space transform of each palette entry (nodes, then skin joints); only the first
	 *                {@code mesh.paletteSize} are read, and not after this call returns
	 * @param tint    ARGB multiplied into every vertex color
	 */
	void submit(ClayMesh mesh, Matrix4fc[] palette, PoseStack.Pose pose, SubmitNodeCollector collector, int light, int overlay, int tint);

	String name();
}
