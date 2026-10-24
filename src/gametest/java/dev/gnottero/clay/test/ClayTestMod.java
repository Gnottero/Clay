package dev.gnottero.clay.test;

import dev.gnottero.clay.ClayShapes;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A block drawn from a model that is over the chunk budget ("simplify": true) and shaped by {@link ClayShapes}. */
public final class ClayTestMod implements ModInitializer {
	static final Identifier ORB_ID = Identifier.fromNamespaceAndPath("clay_test", "orb");
	static final Block ORB = Registry.register(BuiltInRegistries.BLOCK, ORB_ID,
		new Orb(BlockBehaviour.Properties.of().setId(ResourceKey.create(Registries.BLOCK, ORB_ID)).noOcclusion()));

	@Override
	public void onInitialize() {
	}

	static final class Orb extends Block {
		private static final VoxelShape SHAPE = ClayShapes.fromModel(ORB_ID);

		Orb(Properties properties) {
			super(properties);
		}

		@Override
		protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
			return SHAPE;
		}
	}
}
