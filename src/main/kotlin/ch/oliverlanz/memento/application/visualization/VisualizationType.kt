package ch.oliverlanz.memento.application.visualization

/**
 * Selector for operator- and lifecycle-driven visualization projections.
 *
 * This is a routing token only; behavior belongs in effect classes.
 */
enum class VisualizationType {
    PLACEMENT,
    INSPECTION,
    WITHERSTONE_WAITING,
}
