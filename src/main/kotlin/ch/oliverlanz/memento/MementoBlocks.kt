package ch.oliverlanz.memento

import ch.oliverlanz.memento.block.LorestoneBlock
import ch.oliverlanz.memento.block.WitherstoneBlock
import net.minecraft.block.Block
import net.minecraft.block.AbstractBlock
import net.minecraft.item.BlockItem
import net.minecraft.item.Item
import net.minecraft.registry.Registries
import net.minecraft.registry.Registry
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.sound.BlockSoundGroup
import net.minecraft.util.Identifier

object MementoBlocks {

    lateinit var LORESTONE: Block
    lateinit var WITHERSTONE: Block

    fun register() {
        // ----- Lorestone -----
        val loreId = Identifier.of("memento", "lorestone")
        val loreBlockKey: RegistryKey<Block> =
            RegistryKey.of(RegistryKeys.BLOCK, loreId)

        val loreSettings = AbstractBlock.Settings.create()
            .hardness(1.5f)
            .resistance(6.0f)
            .luminance { 8 }
            .sounds(BlockSoundGroup.STONE)
            .registryKey(loreBlockKey)

        LORESTONE = Registry.register(
            Registries.BLOCK,
            loreBlockKey,
            LorestoneBlock(loreSettings)
        )

        // BlockItem for Lorestone
        val loreItemKey: RegistryKey<Item> =
            RegistryKey.of(RegistryKeys.ITEM, loreId)

        val loreItemSettings = Item.Settings()
            .useBlockPrefixedTranslationKey()   // "block.memento.lorestone"
            .registryKey(loreItemKey)

        Registry.register(
            Registries.ITEM,
            loreItemKey,
            BlockItem(LORESTONE, loreItemSettings)
        )

        // ----- Witherstone -----
        val witherId = Identifier.of("memento", "witherstone")
        val witherBlockKey: RegistryKey<Block> =
            RegistryKey.of(RegistryKeys.BLOCK, witherId)

        val witherSettings = AbstractBlock.Settings.create()
            .hardness(2.5f)
            .resistance(8.0f)
            .luminance { 3 }
            .sounds(BlockSoundGroup.DEEPSLATE)
            .registryKey(witherBlockKey)

        WITHERSTONE = Registry.register(
            Registries.BLOCK,
            witherBlockKey,
            WitherstoneBlock(witherSettings)
        )

        // BlockItem for Witherstone
        val witherItemKey: RegistryKey<Item> =
            RegistryKey.of(RegistryKeys.ITEM, witherId)

        val witherItemSettings = Item.Settings()
            .useBlockPrefixedTranslationKey()
            .registryKey(witherItemKey)

        Registry.register(
            Registries.ITEM,
            witherItemKey,
            BlockItem(WITHERSTONE, witherItemSettings)
        )
    }
}
