package org.valkyrienskies.tournament.blocks

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.DirectionalBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.material.Material
import net.minecraft.world.level.storage.loot.LootContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import org.joml.Vector3d
import org.valkyrienskies.core.api.ships.ServerShip
import org.valkyrienskies.mod.common.getShipManagingPos
import org.valkyrienskies.mod.common.getShipObjectManagingPos
import org.valkyrienskies.mod.common.util.toJOMLD
import org.valkyrienskies.mod.common.util.transformDirection
import org.valkyrienskies.tournament.TournamentItems
import org.valkyrienskies.tournament.TournamentProperties
import org.valkyrienskies.tournament.ship.TournamentShips
import org.valkyrienskies.tournament.util.DirectionalShape
import org.valkyrienskies.tournament.util.RotShapes
import org.valkyrienskies.tournament.util.extension.toBlock
import org.valkyrienskies.tournament.util.helper.Helper3d
import org.valkyrienskies.tournament.util.math.lerp
import java.util.*

class ThrusterBlock(
    private val mult: () -> Double,
    private val particle: ParticleOptions,
    private val maxTier: () -> Int
) : DirectionalBlock(
    Properties.of(Material.STONE)
        .sound(SoundType.STONE)
        .strength(1.0f, 2.0f)
) {

    private val SHAPE = RotShapes.box(3.0, 5.0, 4.0, 13.0, 11.0, 16.0)

    private val Thruster_SHAPE = DirectionalShape.south(SHAPE)

    init {
        registerDefaultState(defaultBlockState()
            .setValue(FACING, Direction.NORTH)
            .setValue(BlockStateProperties.POWER, 0)
            .setValue(TournamentProperties.TIER, 1)
        )
    }

    override fun getRenderShape(blockState: BlockState): RenderShape {
        return RenderShape.MODEL
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape {
        return Thruster_SHAPE[state.getValue(BlockStateProperties.FACING)]
    }

    override fun use(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult
    ): InteractionResult {
        if (level !is ServerLevel) return InteractionResult.PASS

        if (player.mainHandItem.item.asItem() == TournamentItems.UPGRADE_THRUSTER.get() && hand == InteractionHand.OFF_HAND) {
            val tier = state.getValue(TournamentProperties.TIER)
            if (tier < maxTier()) {
                disableThruster(level, pos)
                level.setBlockAndUpdate(pos, state.setValue(TournamentProperties.TIER, tier + 1))

                if (!player.isCreative) {
                    player.mainHandItem.shrink(1)
                }
                return InteractionResult.CONSUME
            }
        }

        return InteractionResult.PASS
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
        builder.add(BlockStateProperties.POWER)
        builder.add(TournamentProperties.TIER)
        super.createBlockStateDefinition(builder)
    }

    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, isMoving: Boolean) {
        super.onPlace(state, level, pos, oldState, isMoving)

        if (level !is ServerLevel) return

        val signal = level.getBestNeighborSignal(pos)
        if (state.getValue(BlockStateProperties.POWER) != signal) {
            level.setBlockAndUpdate(pos, state.setValue(BlockStateProperties.POWER, signal))
            return
        }

        if (signal > 0) {
            enableThruster(level, pos, state)
        }
    }

    override fun onRemove(state: BlockState, level: Level, pos: BlockPos, newState: BlockState, isMoving: Boolean) {
        if (level !is ServerLevel) return

        disableThruster(level, pos)

        super.onRemove(state, level, pos, newState, isMoving)
    }

    override fun getDrops(state: BlockState, builder: LootContext.Builder): MutableList<ItemStack> {
        val drops = super.getDrops(state, builder)

        val tier = state.getValue(TournamentProperties.TIER)
        if (tier > 1) {
            drops.add(ItemStack(TournamentItems.UPGRADE_THRUSTER.get(), tier - 1))
        }

        return drops
    }

    private fun getShipControl(level: Level, pos: BlockPos)  =
        ((level.getShipObjectManagingPos(pos)
            ?: level.getShipManagingPos(pos))
            as? ServerShip)?.let { TournamentShips.getOrCreate(it) }

    private fun enableThruster(level: ServerLevel, pos: BlockPos, state: BlockState) {
        getShipControl(level, pos)?.let {
            it.stopThruster(pos)
            it.addThruster(
                pos,
                state.getValue(TournamentProperties.TIER).toDouble(),
                state.getValue(FACING).normal.toJOMLD()
                    .mul(state.getValue(BlockStateProperties.POWER).toDouble()
                    * mult())
            )
        }
    }

    private fun disableThruster(level: ServerLevel, pos: BlockPos) {
        getShipControl(level, pos)?.stopThruster(pos)
    }

    override fun neighborChanged(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        block: Block,
        fromPos: BlockPos,
        isMoving: Boolean
    ) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving)

        if (level !is ServerLevel) return

        val signal = level.getBestNeighborSignal(pos)
        val prev = state.getValue(BlockStateProperties.POWER)

        if (signal == prev) return

        disableThruster(level, pos)

        level.setBlockAndUpdate(pos, state.setValue(BlockStateProperties.POWER, signal))
    }

    override fun getStateForPlacement(ctx: BlockPlaceContext): BlockState {
        var dir = ctx.nearestLookingDirection

        if(ctx.player != null && ctx.player!!.isShiftKeyDown)
            dir = dir.opposite

        return defaultBlockState()
            .setValue(BlockStateProperties.FACING, dir)
    }

    override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: Random) {
        super.animateTick(state, level, pos, random)

        val rp = Helper3d.getShipRenderPosition(level, pos.toJOMLD().add(0.5, 0.5, 0.5))
        if (level.isWaterAt(rp.toBlock())) {
            return
        }

        val power = state.getValue(BlockStateProperties.POWER)
        val jitter = 0.1
        val shipVel = level.getShipObjectManagingPos(pos) ?.velocity ?: Vector3d()


        if (power > 0) {
            val shipDir = state.getValue(FACING)
            val dir = Helper3d.getShipRenderDirection(level, pos.toJOMLD(), shipDir)


            for (i in 0 until power * 2) {
                val jitterX = level.random.nextFloat() * 2 * jitter - jitter
                val jitterY = level.random.nextFloat() * 2 * jitter - jitter
                val jitterZ = level.random.nextFloat() * 2 * jitter - jitter

                val spreadDir = Vector3d(dir.x + jitterX, dir.y + jitterY, dir.z + jitterZ).normalize()
                val speed = -lerp(0.075, 0.5, (power - 1) / 14.0)

                val speedX = spreadDir.x * speed + shipVel.x() * 0.05
                val speedY = spreadDir.y * speed + shipVel.y() * 0.05
                val speedZ = spreadDir.z * speed + shipVel.z() * 0.05

                level.addParticle(particle, rp.x, rp.y, rp.z, speedX, speedY, speedZ)
            }
        }
    }

}