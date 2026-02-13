package ch.oliverlanz.memento.infrastructure.worldscan

import ch.oliverlanz.memento.domain.worldmap.ChunkMetadataFact
import ch.oliverlanz.memento.domain.worldmap.ChunkMetadataIngestionPort

/**
 * Backward-compatible aliases for scan ingestion contracts.
 *
 * Ownership moved to domain world-map service. Keep aliases so existing scanner/file-provider
 * infrastructure can be migrated incrementally without semantic drift.
 */
typealias ScanMetadataFact = ChunkMetadataFact

typealias ScanMetadataIngestionPort = ChunkMetadataIngestionPort
