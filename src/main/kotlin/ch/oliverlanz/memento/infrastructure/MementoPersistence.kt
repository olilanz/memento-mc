package ch.oliverlanz.memento.infrastructure

import ch.oliverlanz.memento.infrastructure.LegacyGuard
import net.minecraft.server.MinecraftServer

/**
 * Legacy persistence façade.
 *
 * The new implementation persists via StoneRegisterPersistence and friends.
 * This file exists only so legacy code can still compile while being unwired.
 */
object MementoPersistence {

    fun load(server: MinecraftServer) {
        LegacyGuard.fail("Legacy MementoPersistence.load() must not be called")
    }

    fun save(server: MinecraftServer) {
        LegacyGuard.fail("Legacy MementoPersistence.save() must not be called")
    }
}
