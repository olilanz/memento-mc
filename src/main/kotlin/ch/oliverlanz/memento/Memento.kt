package ch.oliverlanz.memento

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.item.Items
import net.minecraft.util.ActionResult
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World

object Memento : ModInitializer {

    override fun onInitialize() {
        println("Memento: Natural Renewal initializing...")

        registerCallbacks()

        // commands
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            Commands.register(dispatcher)
        }
    }

    private fun registerCallbacks() {
        UseBlockCallback.EVENT.register(UseBlockCallback { player, world, hand, hitResult ->

            // server side only
            if (world.isClient) return@UseBlockCallback ActionResult.PASS

            val held = player.getStackInHand(hand).item

            val type = when (held) {
                Items.TORCH -> AnchorType.LORE
                Items.SOUL_TORCH -> AnchorType.WITHER
                else -> return@UseBlockCallback ActionResult.PASS
            }

            // block they clicked on
            val pos: BlockPos = hitResult.blockPos

            Anchors.add(
                Anchor(
                    type = type,
                    worldRegistryKey = world.registryKey,
                    pos = pos
                )
            )

            player.sendMessage(
                net.minecraft.text.Text.of("Anchor added: $type at $pos"),
                false
            )

            // allow the right-click to still proceed normally
            ActionResult.SUCCESS
        })
    }
}
